# GrabNetPrinter — tools/

All builds are **manual, offline and Gradle-free**. Gradle files at the repo
root are IDE convenience only; `tools/build.sh` is authoritative.

## Requirements

| Tool | Default path |
|---|---|
| JDK 17 (`javac`, `keytool`) | `/opt/homebrew/opt/openjdk@17` (`JAVA_HOME`) |
| Android SDK | `~/Library/Android/sdk` (`ANDROID_SDK`) |
| android.jar | `$ANDROID_SDK/platforms/android-34/android.jar` (`ANDROID_JAR`) |
| build-tools 35.0.0 (`aapt2`, `d8`, `zipalign`, `apksigner`) | `$ANDROID_SDK/build-tools/35.0.0` (`BUILD_TOOLS`) |
| `zip`, `unzip`, `curl`, `shasum` | system |

Override any of them through the environment, e.g.:

```sh
BUILD_TOOLS="$HOME/Library/Android/sdk/build-tools/36.0.0" tools/build.sh
```

The Xposed API v82 jar is **vendored** at `app/libs/xposed-api-82.jar`
(sha256 `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`), so
the build needs no network. `tools/fetch-api.sh` re-creates it if lost.

## Build

```sh
tools/build.sh            # everything (module zip + diag APK + Xposed APK)
tools/build.sh apk        # dist/GrabNetPrinter.apk
tools/build.sh diag       # dist/gnp-diag.apk
tools/build.sh module     # dist/grab-netprinter-<version>.zip
```

Outputs:

| Artifact | Contents |
|---|---|
| `dist/GrabNetPrinter.apk` | Signed Xposed/LSPosed module, `assets/xposed_init` → `dev.blacksheep.grabnetprinter.HookEntry` |
| `dist/grab-netprinter-<version>.zip` | KernelSU/Magisk module: `module.prop` at ZIP root (version from `module/module.prop`) |
| `dist/gnp-diag.apk` | Standalone diagnostics app, logcat tag `GNP-DIAG` |

Intermediates (`aapt2` output, generated `R.java`, `classes.dex`, keystore) stay
in `build/` and `tools/keystore/`. The debug keystore is generated on first
build (alias `androiddebugkey`, password `android`) — it is a throwaway key,
never use it for anything published.

### Smoke-testing the toolchain before HookEntry exists

`app/src/main/java/` is owned by the hook-implementation task. To prove the
toolchain while it is still empty, pass a throwaway source root outside the
repo:

```sh
tools/build.sh diag module
GNP_EXTRA_JAVA_SRC=/tmp/gnp-hook-src tools/build.sh apk
```

`build.sh` fails with a clear message if no sources are found or if the
compiled classes do not contain `dev.blacksheep.grabnetprinter.HookEntry`.

## Verify

```sh
tools/verify.sh
```

Checks the APK badging (package/version/label), the four xposed meta-data
entries, `classes.dex` + `assets/xposed_init` (and its exact content), absence
of packaged Xposed API classes, APK signatures, and the module zip layout
(`module.prop` at root; content printed). Exits non-zero on any failure.

## Diagnostics on a device

```sh
tools/build.sh diag
adb install -r dist/gnp-diag.apk
adb shell pm grant dev.blacksheep.grabnetprinter.diag android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
adb shell pm grant dev.blacksheep.grabnetprinter.diag android.permission.BLUETOOTH_SCAN 2>/dev/null || true
adb logcat -c
# B6 is optional: pass the printer endpoint to enable the TCP check
adb shell am start -n dev.blacksheep.grabnetprinter.diag/.DiagActivity \
  --es printer_host 192.168.1.50 --es printer_port 9100
adb logcat -s GNP-DIAG
```

Each check logs one greppable line, `STEP=B1 OK ...` or `STEP=B1 ERR ...`:

| Step | Check |
|---|---|
| B0 | runtime permission state (Android 12+), logged when running |
| B1 | `BluetoothAdapter.getDefaultAdapter()` null? |
| B2 | `getRemoteDevice("02:00:00:00:00:01")`: address/name/bondState/uuids |
| B3 | `BluetoothClass.CREATOR.createFromParcel(Parcel.writeInt(0x0680))` |
| B4 | `sun.misc.Unsafe.theUnsafe` + `allocateInstance(BluetoothSocket.class)` |
| B5 | `BluetoothSocket.isConnected()` on the uninitialised instance |
| B6 | TCP to the supplied `printer_host`/`printer_port`, write DLE EOT 1 (status query), read reply; skipped if not supplied |
| B7 | `sendBroadcast(ACTION_FOUND + EXTRA_DEVICE)` — logs SecurityException |
| B8 | `getBondedDevices()` count / exception |
| B9 | `isEnabled()` / `getState()` |

## Fetching the Xposed API jar

```sh
tools/fetch-api.sh            # verify vendored jar (no-op if present + matching)
tools/fetch-api.sh --force    # download from Maven mirrors and replace
```

`https://api.xposed.info/api-82.jar` currently returns 404, so the script uses
`de.robv.android.xposed:api:82` from the Aliyun Maven mirrors and verifies the
SHA-256 above plus the presence of the expected classes.
