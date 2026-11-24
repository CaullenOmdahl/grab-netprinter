# Validation evidence

Reference environment: Android 14 (x86_64) Android-x86 POS terminal rooted with KernelSU, plus
LSPosed over Zygisk. Addresses and device identifiers in this document are placeholders.

## Build & artifact checks

```
tools/build.sh   -> dist/GrabNetPrinter.apk   (49,619 B)
                    dist/grab-netprinter-1.1.zip (4,091 B)
                    dist/gnp-diag.apk          (16,787 B)
tools/verify.sh  -> 39 checks passed, 0 failed
tests/jvm/run.sh -> 46 passed, 0 failed, 0 skipped
```

`tools/verify.sh` covers: APK package/version/label, Xposed meta-data (`xposedmodule`,
`xposeddescription`, `xposedminversion`, `xposedscope`), the settings launcher activity, the
read-only config provider authority, `assets/xposed_init`, packaged dex, absence of packaged Xposed
API classes, APK signatures, the module zip layout (`module.prop` at the zip root) and the
diagnostic APK.

`tests/jvm/run.sh` compiles the platform-independent classes against a JVM stub and checks config
parsing (comments, defaults, invalid MAC, `enabled=0`), override merging (settings over file),
TCP bridge round-trip/flush/close/EOF and connect-failure semantics, protocol mapping and the log
helpers.

## Android 14 API assumptions

Checked against AOSP `android-14.0.0_r1` sources:

| Assumption | Reality |
|---|---|
| `BluetoothClass.CREATOR.createFromParcel` reads one int | Confirmed. `hashCode()` returns `mClass`; `getMajorDeviceClass()` = `mClass & 0x1F00` (IMAGING 0x0600); `getDeviceClass()` = `mClass & 0x1FFC` (PRINTER 0x0680). The parcel trick therefore produces exactly the class values the target filter checks. |
| `BluetoothDevice` has `mAddress` and public address/name/bond/uuid/class getters | Confirmed; its constructors are package-private, so `adapter.getRemoteDevice(mac)` (public) is the primary creation path. |
| `BluetoothSocket` is a final class with hookable `connect/close/isConnected/getOutputStream/...` | Confirmed. |
| `BluetoothAdapter.getDefaultAdapter()` is non-null when BluetoothManagerService exists | Confirmed (reads the `bluetooth_manager` binder; does not require a working BT HAL). |
| `android.app.ContextImpl.registerReceiverInternal(BroadcastReceiver,int,IntentFilter,String,Handler,Context,int)` exists for discovery injection | Confirmed; receivers are matched by parameter type. |

## Target-app facts

The module was developed and hardened against GrabMerchant 4.196.0 (Flutter; targetSdk 36). Its
bundled printer libraries use the classic SPP path:

- paired-printer filter: `BluetoothAdapter.getBondedDevices()` → `getBluetoothClass()` must report
  major 0x0600 / device 0x0680 and `getUuids()` must be non-null;
- connect flow: `createRfcommSocketToServiceRecord(uuid)` → `connect()` → `getOutputStream()`, with a
  reflective `createRfcommSocket(1)` fallback after `IOException`;
- discovery: receivers for `ACTION_FOUND` / `ACTION_UUID` / `ACTION_DISCOVERY_FINISHED` after
  `startDiscovery()`.

Every relevant path goes through `android.bluetooth.*`, which is what the hooks intercept. Other POS
apps with the same print pipeline work by adding their package to the settings.

## Device facts of the reference terminal

- Android 14 / SDK 34, x86_64, userdebug, SELinux **permissive**, `adb root` available,
  KernelSU 0.9.2.
- Bluetooth hardware existed, but the Android BT HAL was not present in the VINTF manifest and
  `com.android.bluetooth` crash-looped — which is what motivated faking the printer in-process
  instead of relying on the real stack.
- Network printer: raw ESC/POS on TCP 9100; a `DLE EOT 1` status query answered `0x16`.

## Root prerequisites (see compat.md)

`zygisknext-v1.5.3.zip` (`zygisksu`) + `lsposed-v1.11.0-zygisk.zip` (`zygisk_lsposed`): both include
real x86_64 payloads, declare Android 8.1–16 support, install as Magisk/KernelSU modules and satisfy
KernelSU 0.9.2's version gates. Checksums and fallbacks (NeoZygisk, Vector) are pinned in
`docs/compat.md`; the zips are not redistributed.

## On-device diagnostic run

`dist/gnp-diag.apk` on the reference terminal:

```
STEP=B1 OK adapter=android.bluetooth.BluetoothAdapter
STEP=B2 OK address=02:00:00:00:00:01 bondState=10 (expect 10=none) uuids=null
STEP=B3 OK class=680 major=0x600 device=0x680 hashCode=1664
STEP=B4 OK unsafe=sun.misc.Unsafe instance=android.bluetooth.BluetoothSocket
STEP=B5 OK isConnected=false
STEP=B6 OK reply=0x16          (DLE EOT 1 status query to the printer)
STEP=B7 ERR SecurityException: not allowed to send broadcast ...FOUND
STEP=B8 OK count=0
STEP=B9 OK enabled=true state=12
```

Key outcomes: the BluetoothClass trick yields exactly the values the target filter requires;
`Unsafe.allocateInstance(BluetoothSocket.class)` works; the printer is reachable; and sending
`ACTION_FOUND` is blocked (protected broadcast), confirming that direct receiver injection — not
broadcast spoofing — is the correct discovery design.

## End-to-end deployment (build 1.0.x)

Deployed to the reference terminal (ZygiskNext + LSPosed + module APK, scope enabled for the target
app):

- hooks installed inside GrabMerchant (`GNP: hooks installed ...`), and re-installed on every app
  start (verified across restarts);
- the target app listed **"LAN Printer"** in its Bluetooth printer search, accepted it
  (`"Setup successful"`), and showed it as connected;
- a test receipt was forwarded: `TCP connected ...` then
  `TCP closed <printer>:9100 after 1045 bytes`, and the receipt physically printed;
- the LSPosed manager listed GrabNetPrinter as an enabled module.

### KernelSU 0.9.2 staging quirk

On this Android-x86 image, staging the Zygisk provider in the same `ksud module install` batch as
other modules (or before them) lost its payload on the next boot (only `module.prop` survived,
`zygiskd` missing, LSPosed logging `no response from bridge` / `System server injection fails`).
Installing the Zygisk provider alone, rebooting, then installing the rest was reliable; this is
documented in [troubleshooting.md](troubleshooting.md) and automated by `deploy/install.sh`.

## Build 1.1.x: settings UI and config delivery

Build 1.1 adds the settings screen and the per-target config file channel (see
[configuration.md](configuration.md)). Offline verification: 39/39 artifact checks, 46/46 JVM
tests, including override-precedence tests.

Deployed and verified on the reference terminal (build 1.1.1):

- the optional support module seeded `<target app>/files/gnp.conf` at boot (owned by the target
  app's uid, mode 0600);
- the settings screen was used **without granting it root**: pressing Save recorded the config as a
  pending file and reported that the support module would apply it; after a reboot the pending file
  was consumed, both config files had all 11 keys including `discovery_broadcast`, and the hook
  logged
  `config sources: target-file=11 settings-provider=0 legacy-file=11` and
  `GNP: hooks installed targets=com.grab.merchant printer=...:9100` — configuration is read from
  the target app's own file, with no root or SELinux special-casing at read time;
- with root granted to the settings app the same configuration is applied immediately (root command
  writes the files and the extra root-only actions become available);
- the print path (`BluetoothHooks` / `SocketBridge`) is unchanged from build 1.0, whose end-to-end
  test physically printed a receipt (1045 bytes forwarded over TCP). The 1.1 hook keeps the legacy
  file fallback, so existing file-configured deployments continue to work unchanged.

## Repeatable validation procedure

Run this per combination (device / Android / root framework / target-app version). It is safe on a
device you already own and operate: it changes no security settings, needs no new accounts or
hardware, and should not be run against a production POS during trading hours or with live orders.
Use the app's own test print, not real orders. Any combination without a completed record below stays
**untested**.

### Per-run checklist

1. **Environment capture** (record all):
   - device model, ABI (`adb shell getprop ro.product.cpu.abi`), Android release and API level
     (`ro.build.version.release`, `ro.build.version.sdk`);
   - SELinux mode: `adb shell getenforce`;
   - root framework and version (KernelSU / Magisk / APatch), Zygisk provider and version;
   - LSPosed version (manager or `module.prop`);
   - GrabNetPrinter build (versionName / versionCode);
   - target app versionName / versionCode:
     `adb shell dumpsys package com.grab.merchant | grep -E 'versionName|versionCode'`.
2. **Paired discovery / bonded devices:** with scope enabled, open the target app's Bluetooth
   printer search. Expect the fake printer (name/MAC from config) in the list and in the paired
   list; log shows `hooks installed` and the discovery injection.
3. **Connect / write / close:** select the printer, run the app's test print. Log should show
   `TCP connected ...` then `TCP closed ... after N bytes`. Confirm a receipt physically printed.
4. **Reconnect:** (a) close/reopen the connection and print again; (b) power-cycle the printer and
   print again; (c) briefly drop the network to the printer (e.g. unplug its LAN cable), confirm a
   connect failure is reported by the app, restore, print again.
5. **Configuration change:** change host, port, and/or fake name (settings app or config file),
   apply, restart the target app, and confirm the new values in the log
   (`config sources: ...`, `hooks installed targets=... printer=...`) and that a print reaches the
   new destination.
6. **Restart behavior:** force-stop and relaunch the target app, print; then reboot the device,
   wait for boot to complete, relaunch, print.
7. **Enforcing SELinux** (only if the device is already enforcing): do **not** run
   `setenforce 0` or otherwise relax policy. Run the steps above as-is and capture denials instead:
   `adb logcat -d | grep -i 'avc:'` and `adb shell dmesg | grep -i avc`. A failure with AVC denials
   is a result to record, not something to work around by weakening the device.

### Evidence to record

- Log excerpts for each step (hook install, discovery, TCP connect/close with byte count, config
  sources, any `GNP` errors), with addresses redacted to placeholders.
- A photo of the physical receipt for every step that claims printing, or the explicit text
  "not printed" if nothing came out. An end-to-end claim without receipt evidence is not recorded as
  passing.
- Any AVC denials (enforcing devices) or crash/ANR lines.

### Results record template

```
Date:
Tester:
Device / model:
ABI:                         Android release / API:
SELinux (getenforce):
Root framework + version:    Zygisk provider + version:
LSPosed version:
GrabNetPrinter versionName/versionCode:
Target app + versionName/versionCode:

Step                              Result (pass/fail/not run)  Evidence (log / photo / "not printed")
1 Environment captured
2 Paired discovery
3 Connect / write / close
4a Reconnect after close
4b Reconnect after printer power cycle
4c Reconnect after network drop
5 Configuration change + re-apply
6a Target-app restart
6b Device reboot
7 AVC denials (enforcing only)

Notes / deviations:
```

A combination is listed as tested in [compat.md](compat.md#compatibility-matrix) only after a
record like this exists for it. Combinations with no record, or with steps marked "not run", stay
"untested" / "not yet covered" for those steps. The reference-run evidence above covers steps 2-3
and 6 on one combination (Android 14 x86_64, permissive); steps 4 and 5 and enforcing-SELinux
coverage are not yet recorded in this repeatable form.
