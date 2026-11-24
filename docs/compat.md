# Compat prerequisites — ZygiskNext + LSPosed on the POS terminal (one-time install)

Status: **researched/verified 2026-11-13 from the exact release zips** (`vendor/`, see
`research/compat/artifacts.md` for sha256 + evidence). The install steps have since been used on the
reference terminal (see [validation.md](validation.md#end-to-end-deployment-build-10x)); only that one
combination is tested (see the [compatibility matrix](#compatibility-matrix)). Commands and expected
outputs are written so a deployer can execute and verify them on other devices.
Paragraphs marked **[EXPECTED]** are inferences that must be confirmed on the device; everything
else was read directly out of the artifact, the project's source, or official KernelSU docs.

Reference device: Android 14 (SDK 34), x86_64, Android-x86, `userdebug`, SELinux **permissive**,
**KernelSU 0.9.2**, no Zygisk, no LSPosed, `adb root` works. `192.168.1.100:5555` below is a
placeholder device address.

What gets installed (module ids matter for enable/disable/uninstall):

| Order | Module | module id | vendor file |
|---|---|---|---|
| 1 | ZygiskNext v1.5.3 (Zygisk provider) | `zygisksu` | `vendor/zygisknext-v1.5.3.zip` |
| 2 | LSPosed v1.11.0 (Xposed framework, JingMatrix fork) | `zygisk_lsposed` | `vendor/lsposed-v1.11.0-zygisk.zip` |
| 3 | `dist/GrabNetPrinter.apk` (our Xposed module — an ordinary app, installed via `adb install`) | — | built by `tools/build.sh` |

Do **not** install ZygiskNext and NeoZygisk together (both are `id=zygisksu`), and do not install
LSPosed and Vector together (both own `/data/adb/lspd`). Fallbacks are in §10.

---

## Compatibility matrix

Four categories are kept apart on purpose: what the build **declares**, what has been **tested**,
what is **untested** (no evidence either way), and what is **out of scope**. "Untested" means
"not yet covered", not "fails" and not "supported". Evidence for the tested rows is in
[validation.md](validation.md).

### Declared support (as declared, not tested)

| Item | Declared | Notes |
|---|---|---|
| App `minSdk` | 27 (Android 8.1) | From `app/build.gradle.kts`; a build-time floor, not a test result. |
| Hook mechanism | Java `android.bluetooth.*` via LSPosed | Members resolved by name + parameter types; missing ones are logged and skipped. |
| Root framework payloads | ZygiskNext v1.5.3 / LSPosed v1.11.0 zips declare Android 8.1-16 | Declaration read from the zips; only Android 14 x86_64 has been exercised. |

### Tested combinations

Only combinations with recorded evidence in [validation.md](validation.md).

| Device | Arch | Android | SELinux | Root / framework | Result |
|---|---|---|---|---|---|
| Android-x86 POS terminal (userdebug) | x86_64 | 14 (SDK 34) | permissive | KernelSU 0.9.2, ZygiskNext v1.5.3, LSPosed v1.11.0 | Hooks installed; target app listed and connected the fake printer; receipt physically printed (build 1.0.x); 1.1.1 config delivery verified. |

### Tested target-app versions

| App | Version | Result |
|---|---|---|
| GrabMerchant (`com.grab.merchant`) | 4.196.0 | Classic-SPP print flow hooked and a receipt printed. No other version has been tested. |

### Untested / expected (not yet covered)

None of these has been run; no support is claimed and no failure is implied.

| Area | Status |
|---|---|
| arm64 (and other ABIs) | Untested. The code is pure Java; the root-framework payloads are ABI-specific. |
| Enforcing SELinux | Untested. The only run was permissive (see section 11). |
| Android versions other than 14 | Untested, including the declared floor (8.1). |
| Magisk, APatch, or KernelSU versions other than 0.9.2 | Untested. |
| LSPosed / Zygisk providers other than the pinned ones (NeoZygisk, Vector fallbacks) | Untested with this module. |
| GrabMerchant versions other than 4.196.0 | Untested. |
| Other POS apps (added through the targets setting) | Untested; expected to work only if they use the same classic-SPP flow. |
| Paired-printer reconnect after printer power cycle / network drop, reboot persistence | Not yet recorded as a repeatable run; see [validation.md](validation.md#repeatable-validation-procedure). |

### Out of scope (not supported unless implemented and verified)

| Environment | Why |
|---|---|
| BLE / GATT printers or apps | Hooks cover the classic RFCOMM socket path only. |
| NDK / native-code Bluetooth or printing pipelines | Native code bypasses the Java `android.bluetooth.*` hooks. |
| IPP-only or LPD-only apps and printers | `protocol=lpd`/`ipp` are accepted in config but currently fall back to raw TCP; no verified LPD/IPP transport. |
| Non-rooted devices / no LSPosed | The module is an LSPosed module; it cannot load without it. |
| iOS and non-Android platforms | Android only. |

### Update resilience

The hooks sit at `android.bluetooth.*`, so they survive target-app updates only while the app keeps
using the Java classic-SPP flow (`createRfcommSocketToServiceRecord` -> `connect` ->
`getOutputStream`, plus the paired-printer class/UUID filter). If an update moves to BLE, native
code, or another print pipeline, the hooks no longer see the print path and printing breaks until
the module is changed and re-validated. After any target-app, Android, or root-framework update,
re-run the procedure in [validation.md](validation.md#repeatable-validation-procedure) and record
the new combination.

---

## 0. Before you start (5 safety rules)

1. **Never reboot blindly.** Before every reboot, know the rollback commands in §9 and confirm
   `adb devices` sees the POS. `adb reboot` is only in this doc so the deployer can run it after
   checking the pre-reboot state.
2. **Bring-up is staged.** Recommended: install ZygiskNext → reboot → verify → install LSPosed →
   reboot → verify → install module APK. This isolates a bad module. The minimal path (install
   both, one reboot) is in §3B and matches the spec's "one reboot", but on first bring-up the
   2-reboot path is safer.
3. The device may have unrelated vendor modules; do not touch them.
4. KernelSU 0.9.2 uses a **staged image**: modules installed with `ksud module install` are held
   in `/data/adb/ksu/modules_update.img` and only become live on the next boot. `ksud module list`
   prints JSON for **live** modules only, so right after install it may still show only the old
   modules — that is normal, not a failure.
5. KernelSU 0.9.2's install/enable/disable commands refuse to run unless `sys.boot_completed=1`.
   Do all of this from a fully booted POS, not during boot.

---

## 1. Preflight (read-only, run all of it before pushing anything)

```sh
adb connect <device>:5555
adb devices -l
adb root                 # restarts adbd as root on userdebug; harmless, expected
adb wait-for-device
adb shell id             # expect uid=0(root)
```

Expected values (stop and report if any differ materially):

```sh
adb shell getprop ro.build.version.sdk            # 34
adb shell getprop ro.product.cpu.abilist          # must contain x86_64 (and typically x86)
adb shell getprop ro.build.version.security_patch # any 2024+ date
adb shell getenforce                              # Permissive
adb shell 'ps -A | grep -E "zygote"'              # a zygote64 line must exist
adb shell 'ps -A | grep -E "zygote64"'            # [EXPECTED] present
adb shell 'ls -la /data/adb/modules'              # live modules; an unrelated vendor module etc.
adb shell '/data/adb/ksud --version'              # [EXPECTED] v0.9.2-ish
adb shell '/data/adb/ksud debug version'          # [EXPECTED] 11682  (KSU kernel version code)
adb shell '/data/adb/ksud module list'            # JSON array of live modules
adb shell 'which unshare'                         # must print /system/bin/unshare (LSPosed daemon uses it)
adb shell 'ls -la /data/adb/ksu'                  # modules.img / modules_update.img live here
```

Version gate (from the artifacts, see `research/compat/artifacts.md` §3): ZygiskNext v1.5.3
requires KernelSU kernel ≥ **10940** and ksud ≥ **11575**; KernelSU v0.9.2 is version code
**11682** (official v0.9.2 release APK is `KernelSU_v0.9.2_11682-release.apk`). So 11682 passes
both, but **verify** with `ksud debug version`. If it prints < 11575, **stop**: ZygiskNext's
installer will abort with "ksud version is too old"/"KernelSU version is too old" — do not
force it.

Also sanity-check free space (`adb shell df -h /data`); the zips are 6.4 MB + 6.8 MB and the
modules image is sparse.

---

## 2. Stage the zips on the device

```sh
adb push vendor/zygisknext-v1.5.3.zip        /data/local/tmp/
adb push vendor/lsposed-v1.11.0-zygisk.zip   /data/local/tmp/
adb shell 'ls -l /data/local/tmp/*.zip'
adb shell 'sha256sum /data/local/tmp/zygisknext-v1.5.3.zip /data/local/tmp/lsposed-v1.11.0-zygisk.zip'
```

Compare to the sha256 table at the top of `research/compat/artifacts.md`. (`vendor/fetch.sh`
already verified the host-side copies; if the device's toybox lacks `sha256sum`, skip that line —
the zips' install scripts verify every file they extract internally and abort on corruption.)

---

## 3. Install the modules with `ksud`

`ksud` 0.9.2 syntax (verified from `userspace/ksud/src/cli.rs` at tag v0.9.2):
`ksud module install <zip>`, `ksud module list`, `ksud module enable <id>`,
`ksud module disable <id>`, `ksud module uninstall <id>`.
Run as root (after `adb root`). The binary is at `/data/adb/ksud` (not necessarily on `PATH`).

### 3A. Recommended staged bring-up (two reboots)

```sh
# --- ZygiskNext -------------------------------------------------------------
adb shell '/data/adb/ksud module install /data/local/tmp/zygisknext-v1.5.3.zip'
```

Expected output includes (exact strings from `vendor/zygisknext-v1.5.3.zip:customize.sh`):

```
- Preparing image
- Module size: ...
- Mounting image
- Installing from KernelSU app
- KernelSU version: 11682 (kernel) + 11682 (ksud)
- Installing Zygisk Next 1.5.3 (880-22a1bf5-release)
- Device sdk: 34
- Device platform: x64
- Extracting verify.sh
- Checking SELinux patches
- Extracting module files
- Extracting x86 libraries            <-- only if ro.product.cpu.abilist32 is non-empty
- Extracting x64 libraries
- Setting permissions
- Ensure config dir created
- Install znctl for KernelSU
- ...
```

If it aborts, the text is explicit: "KernelSU version is too old!", "ksud version is too old!",
"Unable to apply SELinux patches!", or "Unsupported platform". Stop on any abort (see §9 / §10).

Then reboot and verify Zygisk is active (§5), then come back:

```sh
adb reboot
adb wait-for-device
adb shell 'getprop sys.boot_completed'      # wait until 1
```

```sh
# --- LSPosed ----------------------------------------------------------------
adb root
adb shell '/data/adb/ksud module install /data/local/tmp/lsposed-v1.11.0-zygisk.zip'
```

Expected output (strings from `vendor/lsposed-v1.11.0-zygisk.zip:customize.sh`):

```
- Preparing image / - Module size: ...
- LSPosed version v1.11.0 (7209)
- Android SDK version: 34
- Installing from Magisk app          <-- misleading text; harmless, BOOTMODE is true
- Device platform: x64
- Extracting module files
- Extracting dex2oat binaries
- Patching binaries
- Welcome to LSPosed!
```

(The "Magisk app" line is only LSPosed's `enforce_install_from_magisk_app` label; on KernelSU
`$KSU` is set and the Magisk version check is skipped by `customize.sh` — verified in source.)

Then reboot (§4) and continue with §6.

### 3B. Minimal single-reboot path (spec's "then one reboot")

```sh
adb shell '/data/adb/ksud module install /data/local/tmp/zygisknext-v1.5.3.zip'
adb shell '/data/adb/ksud module install /data/local/tmp/lsposed-v1.11.0-zygisk.zip'
adb shell 'ls -l /data/adb/ksu/modules_update.img'    # staging image must exist
adb reboot
```

Both installs are staged into the same `modules_update.img` (the second install copies the first
one's staging image and adds to it — verified in KernelSU 0.9.2 `module.rs`). If the second
install fails, the first is still staged; abort and fix before rebooting, or disable the staged
module with `ksud module disable <id>`.

Pre-reboot checks (all doable without a reboot):
- the two install commands exited 0 and printed their "Installing …" / "Welcome …" lines;
- `adb shell 'ls -l /data/adb/ksu/modules_update.img'` exists and is non-zero;
- `adb shell 'ls -la /data/adb/modules'` still shows the old modules only (staging is not live
  until the reboot) — this is expected;
- `adb shell 'ls -la /data/adb/zygisksu'` exists (ZygiskNext's `customize.sh` creates it at
  install time, even before the reboot).

There is no reliable way to `ls` the staged module directories: the staging image is only mounted
during the `ksud` operation. That is why the install command's own output is the pre-reboot
evidence.

---

## 4. Reboot (one reboot after all staging)

```sh
adb reboot
adb wait-for-device
# poll until boot completes (the POS may take noticeably longer on the first Zygisk boot)
adb shell 'getprop sys.boot_completed'   # expect "1"
adb shell 'ps -A | grep -E "zygote|lspd"'
```

What should happen on a successful first boot:
- KernelSU mounts the staged modules; `/data/adb/modules/zygisksu/` and
  `/data/adb/modules/zygisk_lsposed/` now exist.
- ZygiskNext's `post-fs-data.sh` starts `zygiskd daemon`, which injects into zygote; LSPosed's
  `service.sh` starts the `lspd` daemon in an isolated mount namespace.
- On the POS display, LSPosed posts its **"LSPosed loaded"** notification ("LSPosed status"
  channel; Android 13+ notification; if you don't see it, use §6B).

If the POS does not finish booting (or `sys.boot_completed` never becomes 1), go to §9 rollback /
bootloop recovery **before** doing anything else.

---

## 5. Verify Zygisk + LSPosed are actually active

Run these after the reboot. `[VERIFIED]` = strings/paths read from the artifacts/source;
`[EXPECTED]` = expected on-device result.

```sh
# 1) modules are live
adb shell 'ls -la /data/adb/modules'                          # zyxisksu, zygisk_lsposed present
adb shell 'cat /data/adb/modules/zygisksu/module.prop'        # version=1.5.3 ...
adb shell 'cat /data/adb/modules/zygisk_lsposed/module.prop'  # version=v1.11.0 (7209)
adb shell '/data/adb/ksud module list'                        # JSON; both modules enabled=true

# 2) Zygisk injected (ZygiskNext writes these state files in post-fs-data.sh) [VERIFIED]
adb shell 'ls -la /data/adb/zygisksu'                    # [EXPECTED] znctx, modules_info
adb shell 'ls -la /data/adb/zygisksu/lib64'              # [EXPECTED] libzygisk.so
adb shell 'ls -la /data/adb/modules/zygisksu/lib64 /data/adb/modules/zygisksu/bin'
#                                                 # [EXPECTED] lib64/libzygisk.so, bin/zygiskd -> zygiskd64

# 3) LSPosed daemon running (nice-name "lspd", from the packaged daemon script) [VERIFIED]
adb shell 'ps -A | grep lspd'                            # [EXPECTED] one lspd process
adb shell 'ls -la /data/adb/lspd'                        # [EXPECTED] config/ log/
adb shell 'ls -la /data/adb/lspd/log'                    # [EXPECTED] verbose_*.log, modules_*.log
adb shell 'cat /data/adb/lspd/log/verbose_*.log | tail -40'   # [EXPECTED] LSPosed startup lines
adb logcat -d | grep -iE 'lsposed|lspd' | tail -40       # [EXPECTED] LSPosed tags
```

(Log path `/data/adb/lspd/log/<prefix>_<timestamp>.log` is verified in LSPosed source
`ConfigFileManager.java`; the daemon marks itself `lspd` via `--nice-name=lspd`.)

The single strongest end-to-end proof is **`ps -A | grep lspd` plus the LSPosed manager opening
and showing version v1.11.0**. ZygiskNext's own WebUI is a second, independent Zygisk check: if
the KernelSU manager app is installed (`adb shell pm list packages | grep -i kernelsu`, package
`me.weishu.kernelsu`), open the KernelSU app → Modules → Zygisk Next; taps open its WebUI
(`webroot/` is shipped in the zip) which shows Zygisk status and loaded Zygisk modules.

---

## 6. Open the LSPosed manager (no APK install needed)

LSPosed v1.11.0 bundles its manager (`manager.apk`, package `org.lsposed.manager`, v1.11.0 /
versionCode 7209, minSdk 27) inside the module zip — it is extracted to
`/data/adb/modules/zygisk_lsposed/manager.apk` by the installer. You do **not** download or
install a separate manager. It normally runs as a **parasitic manager** (not a launcher icon),
launched by the daemon.

### 6A. Normal way
Tap the **"LSPosed loaded"** notification on the POS screen ("LSPosed status" channel, tap to open
manager). It opens the manager UI.

### 6B. From adb (same thing the module's `action.sh` does)
```sh
adb shell 'sh /data/adb/modules/zygisk_lsposed/action.sh'
# equivalently:
adb shell 'am start -c org.lsposed.manager.LAUNCH_MANAGER com.android.shell/.BugreportWarningActivity'
```
The category `org.lsposed.manager.LAUNCH_MANAGER` is the parasitic-launch hook (verified in the
shipped `action.sh` and manager APK strings).

### 6C. Last resort — standalone install (UNVERIFIED on this device)
The bundled `manager.apk` is a normal APK with a `MAIN`/`LAUNCHER` activity, so it can be
installed:
```sh
adb pull /data/adb/modules/zygisk_lsposed/manager.apk /tmp/lsposed-manager.apk
adb install -r /tmp/lsposed-manager.apk
adb shell 'am start -n org.lsposed.manager/.ui.activity.MainActivity'
```
Caveat: the bundled manager is signed with the AOSP **Android Debug** key
(SHA-256 `32109438…e7af`); uninstall any older `org.lsposed.manager` first or you get
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. LSPosed's daemon normally verifies/loads the manager APK
from the module dir, so the parasitic path (6A/6B) is preferred; only use 6C if neither works.

If the manager opens but shows "LSPosed is not running", the daemon failed to start — go back to
§5 step 3 and check `/data/adb/lspd/log/` and `adb logcat`.

---

## 7. Install our module APK and enable its scope for `com.grab.merchant`

1. Install the Xposed module APK (built by `tools/build.sh`):
   ```sh
   adb install -r dist/GrabNetPrinter.apk
   adb shell 'pm list packages | grep grabnetprinter'   # dev.blacksheep.grabnetprinter
   ```
2. In the LSPosed manager, open **Modules** (pull to refresh if needed). `GrabNetPrinter` appears
   only if its manifest has the Xposed meta-data (`xposedmodule`, `xposedminversion`) and its
   `assets/xposed_init` is present.
3. **Enable it:** tick the checkbox on the `GrabNetPrinter` row (the row is dimmed while
   disabled — verified in LSPosed v1.11.0 `ModulesFragment`: the row alpha and check box drive
   `enableModule`).
4. **Set the scope:** tap the `GrabNetPrinter` row → the app list opens (LSPosed v1.11.0
   `ModulesFragment` navigates to an app-list fragment on row tap). Search/find
   `com.grab.merchant` and tick it. Tick `com.example.pos.the POS app` too only if that app
   is expected to print through the same path. Back out when done.
5. **Restart the target app** (LSPosed injects at process start):
   ```sh
   adb shell 'am force-stop com.grab.merchant'
   adb shell 'monkey -p com.grab.merchant -c android.intent.category.LAUNCHER 1'
   ```
6. Optional but useful: the module's manifest `xposedscope` pre-selects `com.grab.merchant`, but
   **always confirm the tick in step 4** — an empty scope is the #1 reason an LSPosed module
   "does nothing" (LSPosed only loads modules into apps that are in scope).

---

## 8. Verify our module is actually hooking

```sh
adb logcat -c                                   # clear, then exercise the app
adb logcat -s LSPosed-Bridge                    # XposedBridge.log() output (tag verified in LSPosed XposedBridge.java)
adb logcat -d | grep -iE 'grabnetprinter|gnp' | tail -50
adb shell 'cat /data/local/tmp/grab-netprinter.log' 2>/dev/null | tail -50   # module's own log file
```

Also, in the LSPosed manager: **Logs** tab (module log/verbose log files listed in §5). A loaded
module logs its `IXposedHookLoadPackage` entry lines there. If `LSPosed-Bridge` shows nothing and
the manager Logs tab is empty for the app, check: module enabled, scope ticked, app force-stopped
and relaunched, and `ps -A | grep com.grab.merchant` shows a **new** PID after the restart.

---

## 9. Rollback / disable / uninstall

### 9A. Soft disable (module stops loading on next boot)
```sh
adb shell '/data/adb/ksud module disable zygisk_lsposed'   # framework off, Zygisk stays
adb shell '/data/adb/ksud module disable zygisksu'         # Zygisk provider off
adb reboot
```
This works both for live modules and for staged (pre-first-reboot) modules. Raw equivalent:
`touch /data/adb/modules/<id>/disable`. The flags take effect at the next boot; nothing changes
in the current boot. Disable LSPosed first if you only want to debug the Zygisk layer.

### 9B. Uninstall
```sh
adb shell '/data/adb/ksud module uninstall zygisk_lsposed'   # marks /data/adb/modules/<id>/remove
adb shell '/data/adb/ksud module uninstall zygisksu'
adb reboot
```
`ksud module uninstall` creates a `remove` marker; the module is deleted on the next boot
(verified in KernelSU 0.9.2 `module.rs`). Run them one at a time and check the command's exit
status. ZygiskNext's `uninstall.sh` and LSPosed's `uninstall.sh` clean `/data/adb/zygisksu` and
`/data/adb/lspd` respectively.

### 9C. Bootloop after the reboot but adb is reachable
KernelSU 0.9.2's `ksud module ...` commands refuse to run while `sys.boot_completed != 1`, but
`/data/adb/modules` is already mounted early. So with adb:
```sh
adb connect <device>:5555
adb root
adb shell 'touch /data/adb/modules/zygisksu/disable /data/adb/modules/zygisk_lsposed/disable'
adb reboot
```
If only one of them exists, touch only that one. **[EXPECTED]** — this recovery depends on adbd
being up during the loop, which is normal for a zygote/system_server crash-loop but not
guaranteed.

### 9D. KernelSU safe mode (do not rely on it on this hardware)
KernelSU's built-in safe mode disables **all** modules; per official KernelSU docs you trigger it
by pressing **Volume Down 3 times (press-release, not hold)** right after the first boot screen,
before the boot animation (the kernel unregisters the volume-key listener at `on_post_fs_data`).
Caveats for this device: the safe mode is implemented in the kernel and **non-GKI vendor kernels
may not have the required integration**, and some devices may have **no physical volume keys at
all**. So: know §9C, but do not plan on 9D. If safe mode does trigger,
all modules (including `an unrelated vendor module`) are disabled and must be re-enabled afterwards in the
KernelSU manager.

### 9E. KernelSU manager app route
If the KernelSU manager app is installed, Modules → Zygisk Next / LSPosed → toggle/uninstall does
the same thing; the reboot prompt it shows is required for the change to take effect.

---

## 10. Fallback matrix (only if the primary pair fails)

All four zips sit in `vendor/` with verified sha256 (`vendor/fetch.sh`). `deploy/install.sh`
explicitly selects the primary pair; fallbacks are opt-in via `--framework-vector` /
`--zygisk-neo`. Never install both members of a pair (NeoZygisk has the same module id as
ZygiskNext; Vector disables LSPosed).

| Symptom | Try | Notes |
|---|---|---|
| ZygiskNext install aborts on KernelSU version/sepolicy | **NeoZygisk v2.4** (`vendor/neozygisk-v2.4.zip`, id `zygisksu`, sha256 `93a1425c…`) | MIN_KSU 10940 / MIN_KSUD 11425 — lower gates than ZygiskNext. **Uninstall/disable ZygiskNext first** (same module id), reboot, then install. `ksud module uninstall zygisksu` + reboot. |
| LSPosed v1.11.0 installs but daemon never starts / manager unusable | **Vector v2.2** (`vendor/vector-v2.2.zip`, id `zygisk_vector`, sha256 `9ee83235…`) | Current maintained line (repo renamed `JingMatrix/LSPosed` → `JingMatrix/Vector`), supports Android 8.1–17. Uninstall LSPosed (`ksud module uninstall zygisk_lsposed`), reboot, then install Vector; its installer also disables a leftover `zygisk_lsposed`. Manager package becomes `org.matrix.vector.manager`; open via its notification, or `sh /data/adb/modules/zygisk_vector/action.sh`. |
| Both frameworks fail on Android-x86 | **Native Zygisk module approach** (write a small Zygisk module that hooks via LSPlant/Dobby, or use LSPatch — no root) | Last resort: LSPatch would repackage `com.grab.merchant` (split APKs, Flutter, likely signature/integrity checks) and is not recommended; a native Zygisk hook module is real C++ work. Report to Lead before going here. |

Rejected options and why (upstream LSPosed v1.9.2 staleness, Riru flavor, Magisk Zygisk, EdXposed/
TaiChi, LSPatch) are documented in `research/compat/artifacts.md` §5.

---

## 11. SELinux / permissions notes

- `getenforce` should be `Permissive`. That is fine and expected: permissive means no SELinux
  denial will block the LSPosed hooks or Zygisk injection, which is why this device is a good
  target for in-process hooking.
- Even in permissive mode, KernelSU still applies each module's `sepolicy.rule` at boot via its
  own sepolicy patcher, and the installers run `ksud sepolicy check <file>` and **abort** if the
  kernel can't apply the rules (ZygiskNext and NeoZygisk; LSPosed/Vector don't run the check but
  still ship rules). If you hit "Unable to apply SELinux patches!": do not force-install — check
  `adb shell '/data/adb/ksud debug version'`, confirm KernelSU is healthy, and escalate. A newer
  KernelSU **manager** app can update userspace `ksud`, but the kernel stays 0.9.2; do not flash
  a new kernel as part of this task.
- Do not `setenforce 1` on this POS during bring-up; keep it permissive until the printer
  passthrough is proven.
- LSPosed's rules include `allow shell shell dir write` (used for the parasitic manager) and
  `xposed_file`/`xposed_data` contexts for its binaries/config.

---

## 12. Known Android-x86 / Android 14 pitfalls (and what to check)

1. **64-bit userspace required.** LSPosed's `x86_64.so` and ZygiskNext's `lib64/libzygisk.so`
   only load into a 64-bit zygote. Check `getprop ro.product.cpu.abilist` (must contain `x86_64`)
   and `ps -A | grep zygote64`. The zips also ship x86 (32-bit) payloads, so a mixed system is
   covered too.
2. **Emulator-like props / WSA.** No emulator/WSA/AVD gate was found in the shipped install
   scripts or module props — LSPosed does not refuse to run because `ro.kernel.qemu` is set. The
   real Android-x86 risk is the *target app* (Grab) mis-detecting an emulator, which is an
   app-level concern, not an LSPosed blocker. Some Android-x86 images ship KernelSU, and community reports confirm
   LSPosed + ZygiskNext working on recent Android-x86 builds.
3. **`unshare` must exist.** Both LSPosed's and Vector's `service.sh` start the daemon inside
   `unshare --propagation slave -m …` (Android 14 toybox has it). Verify `which unshare`; if it
   is missing the daemon never starts (symptom: no `lspd` process, nothing in
   `/data/adb/lspd/log`).
4. **No KernelSU WebUI for LSPosed 1.11.0.** The 1.11.0 release notes explicitly removed the
   WebUI integration. Use the notification / `action.sh`. (ZygiskNext *does* ship a WebUI, which
   KernelSU 0.9.2 can open.)
5. **Manager signature.** LSPosed 1.11.0's manager is signed with the AOSP Android Debug key
   (SHA-256 `32109438…`). If you choose the standalone-install fallback (§6C), uninstall the old
   manager first; and never replace `manager.apk` inside the module dir — the daemon verifies it
   (`InstallerVerifier`).
6. **Scope is mandatory.** LSPosed only injects into apps selected in the module's scope. Empty
   scope = silent no-op (most common "it doesn't work"). Also force-stop/relaunch the target app
   after changing scope; LSPosed does not retro-inject a running process.
7. **Module not listed / listed but grey.** The manager lists installed APKs that declare the
   Xposed meta-data. If `GrabNetPrinter` is missing: reinstall the APK, check
   `assets/xposed_init` and the `xposedmodule`/`xposedminversion` meta-data, then pull-to-refresh
   the Modules tab.
8. **First boot is slower** and the POS may briefly show no UI / an ANR-like delay while Zygisk
   injects and LSPosed's dex2oat wrapper initialises. Don't power-cycle during this window.
9. **`targetSdk` > device API.** LSPosed's manager APK is targetSdk 36 (Vector's 37) on an SDK 34
   device. This is legal for installation/execution (minSdk is 27); it is not an error.
10. **`adb root` after every reconnect.** `adb root` persists for the adbd session only; re-run it
    after unplugging/reconnecting before using `ksud`. On a userdebug build this works; if it
    says "adbd cannot run as root", use `adb shell su -c '…'` instead (KernelSU grants root).
11. **Two-zygote/injection details are ZygiskNext's job.** Do not debug zygote internals by hand;
    use the checks in §5 and ZygiskNext's WebUI. If Zygisk modules fail to load, ZygiskNext 1.3.4+
    reports the failing module in its module status/WebUI.

---

## 13. Copy-paste one-time sequence (primary path, single reboot)

```sh
# --- preflight -------------------------------------------------------------
adb connect <device>:5555
adb root && adb wait-for-device && adb shell id
adb shell getprop ro.build.version.sdk                 # 34
adb shell getprop ro.product.cpu.abilist               # x86_64 in list
adb shell getenforce                                   # Permissive
adb shell '/data/adb/ksud --version'
adb shell '/data/adb/ksud debug version'               # >= 11575 expected (0.9.2 ~ 11682)

# --- stage -----------------------------------------------------------------
adb push vendor/zygisknext-v1.5.3.zip      /data/local/tmp/
adb push vendor/lsposed-v1.11.0-zygisk.zip /data/local/tmp/

# --- install (staged until reboot) ----------------------------------------
adb shell '/data/adb/ksud module install /data/local/tmp/zygisknext-v1.5.3.zip'
adb shell '/data/adb/ksud module install /data/local/tmp/lsposed-v1.11.0-zygisk.zip'

# --- reboot ----------------------------------------------------------------
adb reboot && adb wait-for-device
adb shell 'getprop sys.boot_completed'                 # poll until 1

# --- verify -----------------------------------------------------------------
adb shell 'ls /data/adb/modules'                       # zyxisksu zygisk_lsposed
adb shell 'ps -A | grep lspd'                          # daemon alive
adb shell 'ls /data/adb/lspd/log'                      # LSPosed logs

# --- open manager (or tap the LSPosed notification) ------------------------
adb shell 'sh /data/adb/modules/zygisk_lsposed/action.sh'

# --- our module: install, then in the manager Modules tab:
#     enable GrabNetPrinter + scope com.grab.merchant (+ force-stop the app)
adb install -r dist/GrabNetPrinter.apk
adb shell 'am force-stop com.grab.merchant'

# --- prove the hook --------------------------------------------------------
adb logcat -s LSPosed-Bridge
```

Rollback if anything goes wrong: §9A (disable + reboot) or §9C (bootloop, adb available).
