# GrabNetPrinter

[![build](https://github.com/CaullenOmdahl/grab-netprinter/actions/workflows/build.yml/badge.svg)](https://github.com/CaullenOmdahl/grab-netprinter/actions/workflows/build.yml)

Make a **LAN ESC/POS receipt printer** show up inside a POS/merchant app as an already-paired
**Bluetooth SPP printer**, and transparently forward the app's print bytes to the printer over TCP.

Many restaurant/POS apps only print to Bluetooth printers. This is an LSPosed (Xposed) module that
fakes the Bluetooth printer **inside the target app's process** — no real Bluetooth stack, no
kernel driver and no hardware emulation involved — and redirects the ESC/POS byte stream to a
network printer such as a Wi-Fi/Ethernet thermal receipt printer on port 9100.

```
 target app (e.g. GrabMerchant, Flutter/Kotlin, DantSu ESC/POS library)
   ├─ BluetoothAdapter.getBondedDevices()   ──► fake bonded "LAN Printer"
   ├─ device.createRfcommSocketToServiceRecord(uuid)
   │      └─ BluetoothSocket.connect()      ──► TCP connect to your printer
   └─ socket.getOutputStream().write(bytes) ──► raw ESC/POS over TCP ──► printer
```

## Why this works

The module hooks `android.bluetooth.*` inside the target app only:

- `BluetoothAdapter` reports enabled, and `getBondedDevices()` returns a fabricated device with
  the Bluetooth class of an imaging/printer device (`0x0680`) and the Serial Port Profile UUID;
- `BluetoothDevice` getters answer for that fake device (name, address, bonded state, UUIDs);
- `BluetoothSocket` methods are intercepted for fake sockets, and the output stream is bridged
  to a TCP socket — the app's print firmware/library is unchanged;
- optional discovery injection answers the app's scan UI with the same fake device.

Because everything happens in-process, the real (possibly broken) Bluetooth stack is never used.
This also means the module is **narrow**: it only affects the app packages you list.

## Requirements

- Android 8.1+ (`minSdkVersion 27`); tested on Android 14 (x86_64).
- **Root + LSPosed** (Zygisk). Magisk, KernelSU (with ZygiskNext/NeoZygisk) or APatch all work.
- A network receipt printer that accepts **raw ESC/POS over TCP** (usually port 9100). Some office
  printers only speak IPP/LPD and will not work with this module.
- A target app that uses the classic Bluetooth SPP path (the module was developed against
  `com.grab.merchant` 4.196.0, which bundles the DantSu ESC/POS library; the same mechanism covers
  many other POS apps — put their package name in the settings).

## Install

1. Build `dist/GrabNetPrinter.apk` (see [Building](#building)) or download a release.
2. Install the APK, then in **LSPosed Manager → Modules** enable **GrabNetPrinter** and tick the
   scope of your POS app (default: `com.grab.merchant`).
3. Open the **GrabNetPrinter** settings app (launcher icon) and configure:
   - printer host/port (e.g. `192.168.1.50`, `9100`),
   - optional fake Bluetooth name/address,
   - target package(s), discovery/debug toggles.
4. Press **Save** (root is used to push the config into the target app's own files dir), then
   **Restart target app**.
5. In the target app open its Bluetooth printer settings: `LAN Printer` should be listed as a
   paired printer. Connect it and print a test receipt.

The optional KernelSU/Magisk support module (see [`module/`](module)) can seed a default config at
boot for managed deployments; the settings app is all most users need.

## Configuration

The settings app is the normal way. The configuration is delivered to the target app through a
per-target config file (written by root into the target app's files directory) — this needs no
package-visibility exemptions and no SELinux permissive mode. A read-only ContentProvider and the
legacy `/data/local/tmp/grab-netprinter.conf` file are also supported as fallbacks.
See [docs/configuration.md](docs/configuration.md) for every key and the precedence rules.

## Building

The build is **offline and Gradle-free** (aapt2 + javac + d8 + zipalign + apksigner). Requirements:
JDK 17, Android SDK with `platforms/android-34` and `build-tools/35.0.0`.

```bash
tools/build.sh      # dist/GrabNetPrinter.apk, dist/grab-netprinter-<ver>.zip, dist/gnp-diag.apk
tools/verify.sh     # artifact/component/signature checks
tests/jvm/run.sh    # offline config + TCP bridge tests
```

The Xposed API 82 jar is vendored in `app/libs/` (Apache-2.0, see [THIRD_PARTY.md](THIRD_PARTY.md)).
Gradle files are provided only for IDE convenience; `tools/build.sh` is authoritative.

## Diagnostics

`tools/build.sh` also builds `dist/gnp-diag.apk`, a small app that validates the device-side
assumptions (adapter present, `getRemoteDevice`, the BluetoothClass parcel trick,
`Unsafe.allocateInstance(BluetoothSocket)`, and TCP reachability of the printer). See
[docs/troubleshooting.md](docs/troubleshooting.md).

## Limitations

- Only the classic Bluetooth SPP write path is emulated. BLE-GATT printing is not supported.
- Raw TCP ESC/POS is implemented. `lpd`/`ipp` transports are reserved but not implemented; the
  protocol setting currently always behaves as `raw`.
- Faking a Bluetooth device is done at the Java API boundary inside the target app. Apps that
  implement printing in native code (NDK) bypass the hooks.
- The target app must be in LSPosed scope, and its process must be restarted after config changes.

## Disclaimers

- Not affiliated with, endorsed by, or supported by Grab or any POS vendor. Use only on devices and
  accounts you own or administer, and respect the terms of service of the apps you modify.
- No warranty; see [LICENSE](LICENSE).

## Credits

- Built and validated against a Bliss OS (Android-x86) POS with KernelSU.
- Uses the Xposed API (Apache-2.0) and is installed with LSPosed (GPL-3.0). ZygiskNext/LSPosed are
  downloaded separately by the user and are **not** redistributed here (ZygiskNext's licence forbids
  redistribution); see [docs/compat.md](docs/compat.md) for pinned versions and checksums.
- Thanks to the DantSu ESC/POS Android library and the LSPosed/Vector projects for making this kind
  of instrumentation practical.
