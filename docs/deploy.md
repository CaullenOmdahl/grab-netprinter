# Deployment

This guide is for a rooted Android device (Magisk, KernelSU+ZygiskNext/NeoZygisk, or APatch) with
**LSPosed** (Zygisk variant) already installed and working. The settings app plus LSPosed is enough;
the optional KernelSU/Magisk support module is only for managed/headless deployments.

## 0. Build (or download a release)

```bash
tools/build.sh && tools/verify.sh
# dist/GrabNetPrinter.apk, dist/grab-netprinter-<ver>.zip, dist/gnp-diag.apk
```

## 1. Optional: preflight the device assumptions

```bash
adb install -r dist/gnp-diag.apk
adb shell pm grant dev.blacksheep.grabnetprinter.diag android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
adb shell pm grant dev.blacksheep.grabnetprinter.diag android.permission.BLUETOOTH_SCAN 2>/dev/null || true
adb shell am start -n dev.blacksheep.grabnetprinter.diag/.DiagActivity \
  --es printer_host 192.168.1.50 --es printer_port 9100
sleep 3; adb logcat -d -s GNP-DIAG
```

Expect the BluetoothClass and `Unsafe.allocateInstance(BluetoothSocket)` steps to be `OK`; the TCP
step needs a configured printer. See [troubleshooting.md](troubleshooting.md) for what each step
means.

## 2. Install the module

```bash
adb install -r dist/GrabNetPrinter.apk
```

In **LSPosed Manager → Modules**:

1. enable **GrabNetPrinter**;
2. set its **scope** to your POS app's package (default `com.grab.merchant`) — an empty scope is
   the most common reason "nothing happens";
3. force-stop/restart the target app.

## 3. Configure

Open the **GrabNetPrinter** app from the launcher. Set:

- **Printer host/port**: your network receipt printer (raw ESC/POS, usually port `9100`);
- **Bluetooth name**: what the POS app should display (default `LAN Printer`);
- **Target packages**: the package whose scope you enabled.

Press **Save** (a root prompt appears once; the config is written into the target app's private
files dir) and then **Restart target app**. If root is denied, the settings app records the config
as pending and the optional support module applies it on the next boot. Details and all keys: [configuration.md](configuration.md).
If the device is not rooted in a way that grants the module app su, write the legacy config file
instead (see configuration.md) — the hook has a file fallback.

## 4. Pair in the POS app

POS app → its Bluetooth printer settings → **LAN Printer** (already listed as a paired printer) →
connect → print a test receipt. The module log shows:

```
TCP connected to 192.168.1.50:9100 (protocol=raw, timeout=8000ms)
...
TCP closed 192.168.1.50:9100 after N bytes
```

## Optional KernelSU/Magisk support module

`dist/grab-netprinter-<ver>.zip` is an optional Magisk-format module. It seeds the legacy config
file and the per-target config file at boot from
`/data/adb/modules/grab-netprinter/config.prop`, and creates a world-writable log file. Useful when
you want configuration applied without opening the settings app, or on devices where root prompts
are inconvenient.

```bash
adb push dist/grab-netprinter-1.1.zip /data/local/tmp/
adb shell su -c '/data/adb/ksud module install /data/local/tmp/grab-netprinter-1.1.zip'   # KernelSU
# or: adb shell su -c 'magisk --install-module /data/local/tmp/grab-netprinter-1.1.zip'
adb reboot
```

KernelSU 0.9.x stages module installs until the next boot; on some Android-x86 images, staging a
Zygisk provider together with other modules can lose its payload — install/verify Zygisk first.
Details: [troubleshooting.md](troubleshooting.md).

## Verify

```bash
adb shell 'ps -A | grep lspd'                         # LSPosed daemon
adb logcat -d -s LSPosed-Bridge | grep GrabNetPrinter  # module logs (LSPosed routing)
adb shell 'cat /data/local/tmp/grab-netprinter.log'    # optional file log
```

Expected startup line: `GNP: hooks installed targets=… printer=…:…`.

## Changing the printer later

Use the settings app (**Save** → **Restart target app**), or edit the module's `config.prop` and
reboot / press the module Action button in the KernelSU/Magisk manager.

## Rollback

```bash
deploy/rollback.sh                     # if using the helper scripts (needs SERIAL/--serial)
```

or manually:

```bash
adb uninstall dev.blacksheep.grabnetprinter
adb shell su -c 'ksud module disable grab-netprinter'   # optional support module
adb shell su -c 'rm -f /data/adb/modules/grab-netprinter/disable'  # to re-enable
```

Disabling the module in LSPosed, or removing it from the target app's scope, immediately returns
the POS app to stock behaviour on its next start.

## Convenience scripts

`deploy/` contains scripts used to bring up the reference device (stage Zygisk/LSPosed, register
the module/scope via LSPosed's SQLite config when the manager UI is not reachable, verify, roll
back). They take the device address from `SERIAL=host:port` or `--serial`, never hardcode one.
They are optional; the manual steps above are the source of truth.
