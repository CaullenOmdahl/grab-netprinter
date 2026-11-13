# Troubleshooting

## Quick triage

```bash
adb connect 192.168.1.100 && adb root
deploy/verify.sh
adb shell 'logcat -d -s GrabNetPrinter'
adb shell 'cat /data/local/tmp/grab-netprinter.log'
```

The module logs one startup line per hooked process and one line per connection attempt.

## Deploy-day assumptions check (do this first on a new build)

`dist/gnp-diag.apk` is a throwaway diagnostic app that validates every device-side assumption the
hooks depend on, without installing the root modules:

```bash
adb install -r dist/gnp-diag.apk
adb shell pm grant dev.blacksheep.grabnetprinter.diag android.permission.BLUETOOTH_CONNECT
adb shell pm grant dev.blacksheep.grabnetprinter.diag android.permission.BLUETOOTH_SCAN
adb shell am start -n dev.blacksheep.grabnetprinter.diag/.DiagActivity
sleep 3; adb logcat -d -s GNP-DIAG
```

It logs `STEP=B1..B9 OK/ERR ...`. What the steps mean:

| Step | Expect | If it errors |
|---|---|---|
| B1 `getDefaultAdapter()` | non-null adapter | no BluetoothManagerService binder at all; module cannot fake it (add a fallback later) |
| B2 `getRemoteDevice(fakeMac)` | device, unbonded | adapter/permission problem; grant `BLUETOOTH_CONNECT` |
| B3 `BluetoothClass` Parcel trick | major 0x0600, device 0x0680 | would mean the grab filter cannot be satisfied; report it |
| B4 `Unsafe.allocateInstance(BluetoothSocket)` | instance created | hidden API blocked; this is the module's linchpin — see below |
| B5 socket `isConnected` on zeroed instance | false, no crash | hooks would handle it anyway, but a crash here means the class changed |
| B6 TCP write `192.168.1.50:9100` + status read | `0x16` reply | printer/network issue, not a hook issue |
| B7 `sendBroadcast(ACTION_FOUND)` | no `SecurityException` | discovery falls back to captured receivers (still works) |
| B8 `getBondedDevices()` | may throw (`Bluetooth not enabled`) | expected on this POS, the hook replaces the result |
| B9 `isEnabled()/getState()` | whatever, hook overrides to on/12 | — |

### If B4 (Unsafe) fails

`Unsafe.allocateInstance(BluetoothSocket)` is how the module creates an object of a final
framework class without a constructor. If the device blocks it (hidden API), the module logs
`FATAL: could not allocate a fake BluetoothSocket` and printing cannot work. Mitigations, in
order: (1) check the diag output under the *same* conditions (the module runs under LSPosed, where
hidden-API policy is usually relaxed; a failed B4 in a plain app is not conclusive);
(2) embed `org.lsposed.hiddenapibypass` in the module APK and call
`addHiddenApiExemptions("L")` in `HookEntry`; (3) fall back to hooking the app-level
`PrinterConnection.connect()` (Grab plugin class) to inject the TCP stream without a framework
socket object — app-version-specific, not implemented yet.

## "LAN Printer" does not appear in GrabMerchant

1. **Scope not enabled** (most common). LSPosed Manager → Modules → GrabNetPrinter: enabled, and
   **scope** `com.grab.merchant` ticked. Then `adb shell am force-stop com.grab.merchant`.
2. **No startup log** in `logcat -s GrabNetPrinter` → module not loaded. Check ZygiskNext + LSPosed
   are installed/enabled (KernelSU Manager module list) and that you rebooted after installing them.
3. **`enabled=0` or empty `printer_host`** → hooks are intentionally not installed. Check the
   settings app, and the module log line `config sources: target-file=… settings-provider=…
   legacy-file=…` (it shows which config channels were found and how many keys each provided).
4. **Config not reaching the app** → press **Save** in the settings app (it writes the per-target
   file via root and prints whether it applied). If root is unavailable, use the legacy file
   `/data/local/tmp/grab-netprinter.conf` (0644) instead. On SELinux-enforcing devices the target
   file channel is the reliable one; the ContentProvider can be blocked by Android package
   visibility.
5. The app caches its printer list. Force stop / reopen the printer settings screen, or reboot.

## Printer listed, but "connect failed" / test print fails

1. Use the settings app's **Test connection** button (TCP connect from the device), or run
   `deploy/verify.sh` which checks the configured printer endpoint from the device.
2. Check the printer is on and still has the same IP (`192.168.1.50`). DHCP may hand out a new
   address after a printer reboot — set a DHCP reservation on your router.
3. Port must be the raw ESC/POS port (usually 9100). IPP (631)/LPD (515) are not implemented in
   `protocol=raw` mode.
4. Log line `GNP: connect ... failed` shows the `IOException`. Nothing is printed if the TCP
   connect fails.

## Receipt prints garbage / partial / huge black blocks

- The printer is not raw ESC/POS on that port, or the print width (58 vs 80 mm) differs from what
  the app sends. Check the printer model's protocol; the app sends ESC/POS bitmap/text commands.
- Wrong `printer_port` (e.g. pointing at an IPP port) — use 9100.

## Discovery ("search for Bluetooth devices") shows nothing

- The paired-printer list path is the primary one; the app may still be on a scan screen.
  `discovery=1` injects fake `ACTION_FOUND` / `ACTION_UUID` / `ACTION_DISCOVERY_FINISHED` to
  receivers registered in the app. If the app version changes its scan flow, use the paired list
  (the device is reported as already bonded) instead.
- Set `debug=1` and check `GNP: discovery injected` in the log.

## ZygiskNext installed but Zygisk is not running (payload missing)

Symptom: `/data/adb/modules/zygisksu/` contains only `module.prop` (no `bin/zygiskd`, no
`lib64/`), `/data/adb/zygisksu/modules_info` is empty, and LSPosed logs
`no response from bridge` / `System server injection fails` at boot.

Cause (seen on this POS, KernelSU 0.9.2): staging ZygiskNext in the same `ksud module install`
batch as other modules (or before them) can lose its payload when the staged image is merged at
boot. Fix — the sequence the deploy script now automates:

```bash
adb push vendor/zygisknext-v1.5.3.zip /data/local/tmp/
adb shell '/data/adb/ksud module install /data/local/tmp/zygisknext-v1.5.3.zip'
adb reboot
# after boot verify:
adb shell 'ls -la /data/adb/modules/zygisksu/bin /data/adb/modules/zygisksu/lib64'
adb shell 'cat /data/adb/zygisksu/modules_info'    # expect "zygisk_lsposed 64 ..."
# then stage LSPosed + grab-netprinter and reboot again
```

## Support module update did not take effect (KernelSU 0.9.x)

On some Android-x86 images with KernelSU 0.9.x, `ksud module install` of **an update to an already
installed module** stages the payload into `modules_update.img` but the merge at boot does not replace
the live files (the module still reports the old version, and new scripts do not run). If an update
seems ignored, either update through the KernelSU Manager app, or replace the live module files
directly (root):

```bash
adb push module/post-fs-data.sh module/service.sh module/module.prop /data/local/tmp/upd/
adb shell su -c 'cp -f /data/local/tmp/upd/* /data/adb/modules/grab-netprinter/; \
  chmod 0755 /data/adb/modules/grab-netprinter/*.sh'
adb reboot
```

The same quirk is why `deploy/install.sh` installs the Zygisk provider alone before staging the rest.

## Zygisk/LSPosed problems

- `ls /data/adb/modules` should list the Zygisk module and LSPosed. If they are there but LSPosed
  Manager says "not active", reboot again; on Android-x86 some Zygisk builds need the module
  installed *before* LSPosed in the module order.
- Check `logcat -d | grep -iE 'zygisk|lsposed'`.
- Verify the zips you installed are the x86_64-capable ones pinned in `vendor/` (see
  [compat.md](compat.md)). A wrong-architecture module silently does nothing.
- **ZygiskNext/NeoZygisk abort at install** if `ksud sepolicy check` fails on a non-GKI vendor
  kernel (SELinux permissive does not prevent the installer's explicit check). Do not force it;
  read the install output, try the other Zygisk provider (`vendor/neozygisk-v2.4.zip`, same module
  id — uninstall/disable ZygiskNext first), or report the check output as a bug.
- The LSPosed manager is parasitic and may take a second launch to appear on this Android-x86
  build; if it will not open reliably, `deploy/enable-scope.sh` registers the module + scope by
  editing LSPosed's SQLite config (validated on the POS).
- LSPosed v1.11.0 removed KernelSU WebUI; ZygiskNext still has one. KernelSU Manager → Modules →
  Zygisk Next opens its WebUI, an independent Zygisk status check.

## The POS is slow or GrabMerchant crashes

- Disable the module (LSPosed Manager toggle or `deploy/rollback.sh`), force stop the app.
- Hook bodies are defensive (never throw into the app), so a crash is more likely LSPosed/Zygisk
  or another module. If it started right after our install, use `deploy/rollback.sh --all --reboot`.

## Recovery from a bad install

If the POS boot-loops or the desktop does not come up:

1. **Best case: ADB still answers.** `adb root` then:
   ```bash
   for d in /data/adb/modules/*/; do touch "${d}disable"; done   # disable all modules
   adb reboot
   ```
2. **No ADB:** boot the device from a Linux live USB, mount the data partition and create
   `/data/adb/modules/<id>/disable` for `zygisknext`, `lsposed` (and optionally
   `grab-netprinter`), then unmount and reboot. This image's `/data` is not encrypted by default.
3. **KernelSU Manager from another profile:** if the system boots, open KernelSU Manager and
   disable the modules there.
4. After it boots, delete the module directories once you are back in a working state
   (`deploy/rollback.sh --all --purge`).

Nothing in this add-on writes to `/system`, `/vendor` or the boot image; every change is a
KernelSU module directory plus one normal app, so removal always returns the POS to stock.

## Collecting a bug report

```bash
adb shell 'logcat -d -s GrabNetPrinter' > /tmp/gnp-logcat.txt
adb shell 'cat /data/local/tmp/grab-netprinter.log' > /tmp/gnp-file.log
adb shell 'cat /data/adb/modules/grab-netprinter/config.prop' > /tmp/gnp-config.txt
adb shell 'ls -l /data/adb/modules' > /tmp/gnp-modules.txt
```
