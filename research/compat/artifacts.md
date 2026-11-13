# Prerequisite artifacts — Zygisk + Xposed framework

**Target:** POS terminal — Bliss OS 17.2 "Zenith", Android 14 (SDK 34), x86_64, userdebug,
SELinux permissive, **KernelSU 0.9.2** (`/data/adb/ksud`), **no Zygisk, no LSPosed**.
**Researched:** 2026-11-13 from the Mac (GitHub API + release assets downloaded and inspected
with `unzip`, `file`, `apkanalyzer`, `apksigner`, and by reading the module install scripts and
KernelSU 0.9.2 source). **Device was not probed** (192.168.1.100 was not reachable from the
research session); all device-version claims below are derived from the official KernelSU 0.9.2
release + source and must be confirmed on-device with the preflight in `docs/compat.md`.

Conclusion up front: **the LSPosed path is viable on this device.**
Recommended stack = **ZygiskNext v1.5.3** (Zygisk provider) + **LSPosed v1.11.0** (JingMatrix
fork, last LSPosed-branded release) with **Vector v2.2** and **NeoZygisk v2.4** as fallbacks.
Both chosen zips contain real x86_64 native code (verified ELF x86-64), both install as
Magisk/KernelSU modules through `ksud module install`, and both declare/observe minimum
KernelSU versions that the POS's 0.9.2 build satisfies.

## 1. Chosen artifacts (downloaded to `vendor/`, sha256 verified)

| # | Role | Artifact | Module id / versionCode | SHA-256 | Size |
|---|------|----------|-------------------------|---------|------|
| 1 | **Zygisk provider** | `zygisknext-v1.5.3.zip` — ZygiskNext v1.5.3 (880-22a1bf5-release) | `zygisksu` / 880 | `186a0a26c9fcb895af1954b42de831433c23d72d1bd0758b986069154359db67` | 6,638,473 |
| 2 | **Xposed framework** | `lsposed-v1.11.0-zygisk.zip` — LSPosed v1.11.0 (7209), JingMatrix fork | `zygisk_lsposed` / 7209 | `6b8455e08dfa13a8121619fe013a79b40e3e5a1291555c91cc7ee1870a56b65c` | 6,785,684 |
| 3 | Fallback framework | `vector-v2.2.zip` — Vector v2.2 (3080-88f8e1fa), renamed maintained fork | `zygisk_vector` / 3080 | `9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc` | 9,316,843 |
| 4 | Fallback Zygisk provider | `neozygisk-v2.4.zip` — NeoZygisk v2.4 (289-08080ef) | `zygisksu` / 289 ⚠ *same id as ZygiskNext — mutually exclusive* | `93a1425c67bb89f58a0dcb9fc823ec93f6472214f221e667a70b67c6a6e061a1` | 2,401,208 |

Exact release-download URLs (also embedded in `vendor/fetch.sh`):

- ZygiskNext v1.5.3 — <https://github.com/Dr-TSNG/ZygiskNext/releases/download/v1.5.3/Zygisk-Next-1.5.3-880-22a1bf5-release.zip>
- LSPosed v1.11.0 — <https://github.com/JingMatrix/Vector/releases/download/v1.11.0/LSPosed-v1.11.0-7209-zygisk-release.zip>
  (the maintained fork `JingMatrix/LSPosed` now redirects to `JingMatrix/Vector`; its old release
  assets live under the `Vector` repo name)
- Vector v2.2 — <https://github.com/JingMatrix/Vector/releases/download/v2.2/Vector-v2.2-3080-Release.zip>
- NeoZygisk v2.4 — <https://github.com/JingMatrix/NeoZygisk/releases/download/v2.4/NeoZygisk-v2.4-289-release.zip>

`vendor/fetch.sh` is idempotent (skips files whose sha256 already matches), aborts on any
checksum mismatch, and prints the version/size/sha256 table. `./fetch.sh --force` re-downloads.

All four zips are in `vendor/` (flat, as `deploy/install.sh` expects). The installer explicitly
selects the primary pair by filename; fallback files (`vector-*`, `neozygisk-*`) are only used with
`--framework-vector` / `--zygisk-neo`. Never install both members of a pair: NeoZygisk shares
module id `zygisksu` with ZygiskNext, and Vector disables `zygisk_lsposed`.

> License note: ZygiskNext ≥ v4-0.9.2 is *not* GPL — its README reserves all rights and forbids
> redistribution. Keep `vendor/*.zip` internal to this project; do not publish/redistribute.
> LSPosed/Vector/NeoZygisk are GPL-3.0.

## 2. Android 14 / x86_64 evidence (from the actual zip contents)

`file` on the libs extracted from the downloaded zips:

| Zip | x86_64 payload | `file` result |
|-----|----------------|---------------|
| ZygiskNext v1.5.3 | `lib/x86_64/{libzygisk.so,libzn_loader.so,libpayload.so}`, `bin/x86_64/zygiskd` | `ELF 64-bit LSB shared object, x86-64` / `ELF 64-bit LSB pie executable, x86-64` |
| LSPosed v1.11.0 | `lib/x86_64/liblspd.so`, `bin/x86_64/{dex2oat,liboat_hook.so}` | `ELF 64-bit LSB shared object, x86-64` / `ELF 64-bit LSB pie executable, x86-64` |
| Vector v2.2 | `lib/x86_64/libzygisk.so`, `bin/x86_64/{dex2oat,liboat_hook.so}` | same, x86-64 |
| NeoZygisk v2.4 | `lib/x86_64/{libzygisk.so,libzygisk_ptrace.so}`, `bin/x86_64/zygiskd` | same, x86-64 |

Each zip also carries the 32-bit x86 equivalents (`lib/x86/...`, `bin/x86/...`); the installers
extract both when `ro.product.cpu.abilist32` is non-empty, so a mixed x86/x86_64 Bliss userspace
is covered.

**ZygiskNext v1.5.3** — `module.prop`: `id=zygisksu`, `version=1.5.3 (880-22a1bf5-release)`,
`description=Standalone implementation of Zygisk.` Its `customize.sh` declares:
`MIN_KSU_VERSION=10940`, `MIN_KSUD_VERSION=11575`, `MIN_MAGISK_VERSION=26402`,
`MIN_APATCH_VERSION=10700`; it aborts if `API < 26` (supports Android 8.0+, so Android 14/SDK 34
is in range), aborts on unsupported `$ARCH` (`x64` is supported and extracts
`lib/x86_64/*` → `$MODPATH/lib64`, `bin/x86_64/zygiskd` → `bin/zygiskd64`), aborts in recovery
(needs `BOOTMODE`), and aborts on KernelSU if its sepolicy check fails. It installs a WebUI
(`webroot/`), a `sepolicy.rule`, and (on KernelSU) a `znctl` symlink in `/data/adb/ksu/bin`.
Release notes (v1.5.3): Android 17 QPR3 support, 16 KB-page fix — i.e. maintained well past
Android 14.

**LSPosed v1.11.0 (JingMatrix fork)** — `module.prop`: `id=zygisk_lsposed`,
`version=v1.11.0 (7209)`, `description=… Supports Android 8.1 ~ 16. Requires Magisk 26.0+ and
Zygisk enabled.` Bundled `README.md` confirms "Supported Versions: Android 8.1 ~ 16" — Android
14 (SDK 34) is covered. `customize.sh` behavior on KernelSU (checked against source):
- `enforce_install_from_magisk_app()` only requires `BOOTMODE` (ksud 0.9.2 sets it — see §3);
- `check_magisk_version()` is **skipped** when `$KSU` is set: `if [ -z "$KSU" ] && [ -z "$APATCH" ]`;
- `check_android_version()` requires API ≥ 27 → passes (34);
- `ARCH=x64` + `IS64BIT=true` (both set by ksud's installer) → extracts
  `lib/x86_64/liblspd.so` as `$MODPATH/zygisk/x86_64.so` and `bin/x86_64/{dex2oat,liboat_hook.so}`
  as `dex2oat64`/`liboat_hook64.so` (needed for the dex2oat wrapper on API ≥ 29);
- it ships `manager.apk` too (see §4). No Magisk-specific binary is required.

**Legacy Xposed API support (required by our module)** — verified by string-scanning the shipped
framework dex: both `lsposed-v1.11.0-zygisk.zip:framework/lspd.dex` and
`vector-v2.2.zip:framework/vector.dex` contain `IXposedHookLoadPackage`,
`IXposedHookZygoteInit`, `IXposedHookInitPackageResources` and `XposedBridge`; both module
daemons contain the `xposed_init` and `xposedminversion` loader strings; both managers understand
`xposedscope` meta-data. So an API-82-style module (`assets/xposed_init` +
`IXposedHookLoadPackage`, as used by this module) is supported by either framework.

**Vector v2.2 (fallback framework)** — same install mechanics (`id=zygisk_vector`, description
"Android 8.1 ~ 17", `lib/x86_64/libzygisk.so`, legacy+modern API strings present in
`framework/vector.dex`). Its `customize.sh` disables an existing `/data/adb/modules/zygisk_lsposed`
(`touch .../disable`) when installed. Manager package is `org.matrix.vector.manager` (v2.2,
versionCode 3080, minSdk 27, targetSdk 37). Because it is a full framework/manager rewrite
(new `vectord` daemon, new UI, libxposed API 101/102), it is kept as the fallback rather than the
primary for a legacy module on a production POS.

**NeoZygisk v2.4 (fallback Zygisk provider)** — ptrace-based Zygote injection, same module id
`zygisksu` as ZygiskNext (so the two can never coexist). `customize.sh` declares
`MIN_KSU_VERSION=10940`, `MIN_KSUD_VERSION=11425`, `MAX_KSU_VERSION=30000` (warn only), API ≥ 26,
`ARCH=x64` supported. It also runs `check_sepolicy` on KernelSU. Its README says it provides the
Zygisk API for APatch/KernelSU and is what Vector's own README suggests as the Zygisk environment.

## 3. KernelSU 0.9.2 compatibility (version-gate math)

ZygiskNext's installer reads the env vars `KSU_KERNEL_VER_CODE` and `KSU_VER_CODE` that
`ksud module install` provides. KernelSU's version-code scheme (source, tag `v0.9.2`):

- kernel `KSU_VERSION` = `10000 + git_commit_count + 200` (`kernel/Makefile`),
- manager/ksud versionCode = same formula (`manager/build.gradle.kts` `getVersionCode()`),
- the official KernelSU v0.9.2 release APK is named **`KernelSU_v0.9.2_11682-release.apk`**, so
  v0.9.2 = version code **11682** for both kernel and ksud.

Therefore the POS is expected to report `KSU_VER_CODE=11682` and
`KSU_KERNEL_VER_CODE=11682`:

| Requirement | ZygiskNext v1.5.3 | NeoZygisk v2.4 | POS (KernelSU 0.9.2) | Verdict |
|---|---|---|---|---|
| min kernel | 10940 | 10940 | 11682 | PASS |
| min ksud | 11575 | 11425 | 11682 | PASS |
| Magisk | 26402 (n/a) | 26402 (n/a) | — | n/a |
| APatch | 10700 (n/a) | 10762 (n/a) | — | n/a |

The required KernelSU version codes have been stable in ZygiskNext's README since v1.1.0
(checked v1.1.0, v1.3.4, v1.4.5, v1.5.3), so the newest release does not narrow 0.9.2
compatibility.

Why `ksud module install` satisfies both installers' preconditions on 0.9.2 (verified in
`userspace/ksud/src/module.rs` + `userspace/ksud/src/installer.sh` at tag v0.9.2):
- `exec_install_script` exports `KSU=true`, `KSU_VER`, `KSU_VER_CODE`,
  `KSU_KERNEL_VER_CODE`, `ZIPFILE`, `OUTFD`, `PATH`, `ASH_STANDALONE=1`;
- `installer.sh` auto-detects `BOOTMODE=true` when zygote is running (`[ -z $BOOTMODE ] && ps |
  grep zygote … && BOOTMODE=true`), which is the case while `ksud module install` runs on a
  booted POS — this satisfies ZygiskNext's `[ "$BOOTMODE" ] && [ "$KSU" ]` branch and LSPosed's
  `enforce_install_from_magisk_app` + skipped Magisk check;
- `installer.sh` exports `API`, `ARCH`, `IS64BIT`, defines the `extract`, `set_perm`,
  `set_perm_recursive`, `check_sepolicy` helpers used by both zips, and runs module scripts under
  KernelSU's BusyBox ash (so `sha256sum`, `unzip`, etc. exist).

`ksud` 0.9.2 CLI (from `cli.rs` at tag v0.9.2) — exact syntax used in `docs/compat.md`:
`ksud module install <zip>`, `ksud module list`, `ksud module enable <id>`,
`ksud module disable <id>`, `ksud module uninstall <id>`, `ksud module shrink`;
plus `ksud --version`, `ksud debug version` (kernel version code).

One KernelSU 0.9.2 mechanic that changes verification/rollback commands: installs are **staged**
into `/data/adb/ksu/modules_update.img` and become live on the next boot (`_install_module`,
`update_module` in `module.rs`). `ksud module list` reads the **live** `/data/adb/modules` (it
prints JSON), so a freshly installed module is not listed until after the reboot; the install
command's own output plus `/data/adb/ksu/modules_update.img` are the pre-reboot evidence.
`ksud module disable/uninstall` do act on the staged image and therefore work before the reboot.
`ksud module install` also refuses to run unless `sys.boot_completed=1`.

## 4. Manager question (is a separate manager APK required?)

No separate download/install of a manager is required.

- **LSPosed v1.11.0** bundles `manager.apk` in the module zip; `customize.sh` extracts it to the
  module dir (`/data/adb/modules/zygisk_lsposed/manager.apk`). It is a normal APK — package
  `org.lsposed.manager`, versionCode 7209 / versionName 1.11.0, minSdk 27, targetSdk 36,
  `MAIN`/`LAUNCHER` activity, signed with the AOSP **Android Debug** key
  (SHA-256 `32109438bd689537312fab9290e3c27fcf89774418a08b7cf1fbaf02e21ee7af`) — but LSPosed
  normally runs it as a **parasitic manager** (no launcher icon): the daemon posts a
  "LSPosed loaded" status notification whose tap opens the manager, and the module's `action.sh`
  exposes the same launch (`am start -c org.lsposed.manager.LAUNCH_MANAGER
  com.android.shell/.BugreportWarningActivity`; verified in the shipped `action.sh` and the
  manager APK's `LAUNCH_MANAGER` category). Installing `manager.apk` standalone is possible (it
  has a launcher activity, and the manager itself carries an `isParasitic` flag — it explicitly
  supports both modes) but is not needed and is unverified on this device. If you install it,
  uninstall any older `org.lsposed.manager` first (the bundled APK is debug-key signed).
- **Vector v2.2** similarly bundles `manager.apk` (package `org.matrix.vector.manager`, v2.2 /
  3080, minSdk 27, targetSdk 37, signer SHA-256
  `b10b03393e1f7df49a6dcd97cdb6478fb19cb5efd2024841be1129fd807697ed`), opened the same way
  (`action.sh` category `org.matrix.vector.manager.LAUNCH_MANAGER`).

LSPosed v1.11.0 **removed the KernelSU WebUI** ("WebUI Removal: Removed the WebUI integration as
it is no longer required" — release notes), so on this POS the manager is reached via the
notification/`action.sh`, not the KernelSU WebUI. (ZygiskNext itself does ship a WebUI
(`webroot/`) which KernelSU 0.9.2's manager can open — useful for Zygisk status.)

## 5. Rejected / not chosen alternatives

| Candidate | Why not chosen |
|---|---|
| **LSPosed upstream `LSPosed/LSPosed` v1.9.2** (2023-10-11) | Zip downloaded/verified: `sha256 0ebc6bcb465d1c4b44b7220ab5f0252e6b4eb7fe43da74650476d2798bb29622`, claims Android 8.1 ~ 14 and does ship `lib/x86_64/liblspd.so`. Technically viable for Android 14, but **stale since Oct 2023**: no Android 14 QPR/ART/dex2oat, UserManager, or KernelSU-era fixes that v1.10+/v1.11 include. Last-resort only. |
| **LSPosed "riru" flavor** (`-riru-` zips) | Requires Riru, which is a Magisk-only framework; KernelSU has no Riru integration. The `-zygisk-` flavor is the only fit. |
| **Magisk built-in Zygisk** | Not available on KernelSU. KernelSU FAQ: "KernelSU has no built-in Zygisk support, but you can use a module like ZygiskNext to support it." |
| **EdXposed / TaiChi / VirtualXposed / original Xposed** | Not Android 14 compatible (EdXposed/TaiChi are abandoned; TaiChi closed-source; original Xposed stopped at Android 8). |
| **LSPatch** (non-root Xposed by repackaging) | Would require repackaging `com.grab.merchant` (split APKs, Flutter, likely signature/integrity checks) — high risk of breaking the POS app. Rejected as primary; only a last resort if in-process root hooks are impossible. |
| **Vector v2.2 as primary framework** | Viable and current (same maintainer, Android 8.1–17, legacy API confirmed), but it is a major rewrite (new daemon/manager/API 101/102); LSPosed v1.11.0 is the conservative, better-documented choice for a legacy module. Kept as the first fallback. |
| **NeoZygisk v2.4 as primary Zygisk** | Viable per its own version gates (kernel ≥ 10940, ksud ≥ 11425 — 0.9.2 passes) and it is what Vector's README suggests; kept as fallback because it is ptrace-based (more invasive) and shares module id `zygisksu` with ZygiskNext, so only one may ever be installed. KernelSU's own FAQ documents the ZygiskNext pairing. |

## 6. Evidence-backed risks (must be handled in deploy)

1. **Device version not directly observed.** The 11682 / 10940 / 11575 numbers are derived from
   the official v0.9.2 release artifact and source. Confirm with
   `/data/adb/ksud --version` and `/data/adb/ksud debug version` before installing (see
   `docs/compat.md`). If the installer prints "ksud version is too old"/"KernelSU version is too
   old", stop — do not force-install.
2. **KernelSU sepolicy check.** ZygiskNext (and NeoZygisk) run `check_sepolicy` during install
   and abort if the kernel can't apply the rules. Bliss' non-GKI "zenith" kernel may or may not
   accept every rule. SELinux is permissive, so the rules are not needed for hooks to run, but
   the installer's abort is. See `docs/compat.md` for the mitigation/escalation path.
3. **ZygiskNext needs a working 64-bit zygote** (`x86_64` userspace). Confirm
   `ro.product.cpu.abilist` contains `x86_64` and a `zygote64` process exists.
4. **Boot-time helpers.** LSPosed/Vector start their daemon from `service.sh` inside
   `unshare --propagation slave -m …`; Android 14 toybox supports this, but verify `unshare`
   exists on Bliss (`which unshare`).
5. **Recovery.** KernelSU's kernel-side safe mode (press Volume Down 3× after the first boot
   screen; listener removed at `on_post_fs_data`) requires non-GKI kernel integration and
   physical volume keys — a POS terminal may have neither. Treat the adb-based
   disable/uninstall path as the primary rollback, and prefer bringing up ZygiskNext and LSPosed
   in two staged reboots for failure attribution.
6. **Mutual exclusion.** ZygiskNext and NeoZygisk share `id=zygisksu`; LSPosed and Vector both
   own `/data/adb/lspd` and similar module names. Never install both of either pair; uninstall
   one and reboot before installing the other.
