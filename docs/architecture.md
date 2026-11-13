# Architecture

## Problem

GrabMerchant only supports receipt printing over **Bluetooth** (its own cloud printer aside).
The cafe has a **network** ESC/POS printer. The POS runs Bliss OS 17.2 (Android 14 x86_64) where
the Android Bluetooth HAL (`android.hardware.bluetooth.IBluetoothHci/default`) is not present in
the VINTF manifest, so enabling Bluetooth crash-loops `com.android.bluetooth`. Even with a working
BT stack, no userspace trick can make a remote peripheral appear to the local stack.

## Solution shape

Hook the **Java Bluetooth API inside the GrabMerchant process** and replace the printer that the
app sees and talks to:

1. `BluetoothAdapter.isEnabled()` reports `true` (the app refuses to list printers otherwise).
2. `BluetoothAdapter.getBondedDevices()` returns a fake device that looks exactly like a bonded
   ESC/POS printer:
   - address `fake_mac` (default `02:00:00:00:00:01`),
   - name `fake_name` (default `LAN Printer`),
   - `getBondState()` = BOND_BONDED,
   - `getBluetoothClass()` major `0x0600` (IMAGING) / device `0x0680` (PRINTER),
   - `getUuids()` = `00001101-0000-1000-8000-00805F9B34FB` (Serial Port / SPP).
   The target app's paired-printer filter (verified against GrabMerchant 4.196.0 by inspecting its
   bundled DantSu ESC/POS and `mex_bluetooth_printer` code) is strict about exactly these values.
3. `device.createRfcommSocketToServiceRecord(uuid)` (and `createRfcommSocket(int)` used by the
   app's fallback path) returns a fake `BluetoothSocket` instance whose:
   - `connect()` opens a TCP socket to `printer_host:printer_port`,
   - `getOutputStream()` returns a stream that writes the app's ESC/POS bytes straight to TCP,
   - `close()`/`isConnected()`/`getRemoteDevice()` behave sanely.
4. Discovery scans are answered with the same fake device: `startDiscovery()` returns true and the
   registered `BroadcastReceiver`s for `ACTION_FOUND` / `ACTION_UUID` /
   `ACTION_DISCOVERY_FINISHED` are invoked directly (broadcasts are not sent through the system,
   which keeps it robust and permission-free).

All of this happens in the app process via **LSPosed** (Xposed framework). The real BT HAL/stack
is never invoked.

## Data flow

```
Grab UI "Test print" / order receipt
  → mex_bluetooth_printer Dart → Kotlin plugin → DantSu ESC/POS (or prt/imin path)
    → BluetoothSocket (fake, Unsafe-allocated Android object)
      → OutputStream proxy
        → TCP socket → 192.168.1.50:9100 (raw ESC/POS)
```

## Components and trust boundaries

- **`GrabNetPrinter.apk`** (LSPosed module, package `dev.blacksheep.grabnetprinter`): pure Java;
  compiled only against Xposed API stubs. Runs inside `com.grab.merchant`.
- **`grab-netprinter` KernelSU module**: no code in the app; publishes
  `/data/local/tmp/grab-netprinter.conf` (readable by the app uid) at boot and rotates the log.
- **ZygiskNext + LSPosed**: the injection framework; standard, maintained root modules.
- **Config**: delivered into the hooked app's own files directory by the settings app (via root),
  with a read-only exported ContentProvider and a legacy `/data/local/tmp` file as fallbacks. The
  data is not sensitive (LAN printer address + cosmetic Bluetooth identity). See
  [configuration.md](configuration.md).

## Hook installation strategy

- `HookEntry.handleLoadPackage()` is called for packages in LSPosed scope. Because the config
  needs a `Context`, hook installation is deferred to `Application.onCreate()`; the config is then
  read by `ConfigReader` and checked against the configured target list (other packages return
  immediately, including `system_server` and `com.android.bluetooth` — we never hook the system).
- Every hook body is wrapped in try/catch and logs failures; a bad hook can make printing not
  work but must never crash the till app.
- Framework members are resolved by **name + parameter types via reflection** rather than
  compile-time references where they are hidden, so an Android security patch or a Grab update
  degrades gracefully (log + no-op) instead of failing to load.
- Fake objects are tracked by identity (`IdentityHashMap`); all other instances fall through to
  the original implementation, so legitimate Bluetooth code paths keep behaving normally.

## Failure modes (and what happens)

| Failure | Behaviour |
|---|---|
| LSPosed scope not enabled | No hooks; app behaves exactly as before (BT "off"). Log shows nothing. |
| Config missing/disabled | Hooks not installed; no behaviour change. |
| Printer offline | `socket.connect()` throws `IOException`; Grab shows connection failure, same as a dead BT printer. |
| Unsafe/reflection blocked by ROM | Module logs which step failed and installs no fake socket; app still lists no printer. |
| Grab app update changes printer logic | Framework-level hooks still hold as long as the app keeps using `android.bluetooth`; only the class-name filter assumption (0x0680) is app-specific and stable across releases. |
| Zygisk/LSPosed fails after update | Disable/remove the modules via adb (`deploy/rollback.sh`), reboot; the POS boots normally. |

## Security notes

- The module only affects the `com.grab.merchant` process; it does not hook the system, the POS
  app, or the Bluetooth service.
- The config file is not a secret, but keep it writable only via root (0644 is enough for the app
  to read; `service.sh` re-publishes it on every boot so tampering is overwritten).
- No network services are opened by the module; it is only a TCP *client* to the printer.
- Installing ZygiskNext/LSPosed is a system-root change; audit and pin versions (see
  `vendor/` checksums) and keep recovery/ADB access available.
