#!/usr/bin/env bash
#
# GrabNetPrinter — fully offline build (no Gradle, no network, no wrapper).
#
# Uses the Android SDK build-tools directly:
#   aapt2 (resources/manifest) + javac (Java 8 bytecode, android.jar as
#   bootclasspath) + d8 (dex) + zipalign + apksigner.
#
# Outputs (all under dist/):
#   GrabNetPrinter.apk                     Xposed/LSPosed module APK
#   grab-netprinter-<version>.zip          KernelSU/Magisk module zip (module.prop at root)
#   gnp-diag.apk                           standalone diagnostic APK (tag GNP-DIAG)
#
# Usage:
#   tools/build.sh                 # build everything
#   tools/build.sh apk             # only the Xposed module APK
#   tools/build.sh diag            # only the diagnostic APK
#   tools/build.sh module          # only the KernelSU/Magisk module zip
#   tools/build.sh apk diag        # any combination
#
# Environment overrides:
#   JAVA_HOME          default /opt/homebrew/opt/openjdk@17 (falls back to javac on PATH)
#   ANDROID_SDK        default ~/Library/Android/sdk
#   BUILD_TOOLS        default $ANDROID_SDK/build-tools/35.0.0
#   ANDROID_JAR        default $ANDROID_SDK/platforms/android-34/android.jar
#   APP_VERSION_NAME   default 1.0.0
#   APP_VERSION_CODE   default 1
#   MODULE_VERSION     default: version= from module/module.prop (else 1.0)
#   XPOSED_JAR         default app/libs/xposed-api-82.jar
#   KEYSTORE           default tools/keystore/debug.jks (auto-created, throwaway key)
#   GNP_EXTRA_JAVA_SRC extra Java source roots, colon separated (see below)
#
# Toolchain smoke test while app/src/main/java/ is still empty (HookEntry is
# owned by another task) — create a throwaway entry point outside the repo:
#
#   mkdir -p /tmp/gnp-hook-src/dev/blacksheep/grabnetprinter
#   cat > /tmp/gnp-hook-src/dev/blacksheep/grabnetprinter/HookEntry.java <<'EOF'
#   package dev.blacksheep.grabnetprinter;
#   import de.robv.android.xposed.*;
#   import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
#   public class HookEntry implements IXposedHookLoadPackage {
#     @Override public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
#       XposedBridge.log("placeholder");
#     }
#   }
#   EOF
#   GNP_EXTRA_JAVA_SRC=/tmp/gnp-hook-src tools/build.sh apk
#
# The Xposed API v82 jar is compileOnly: it is only on javac's classpath and is
# never packaged (LSPosed provides it at runtime). tools/fetch-api.sh can
# re-create app/libs/xposed-api-82.jar if it is ever lost.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

# --------------------------------------------------------------- settings ---
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_SDK/build-tools/35.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_SDK/platforms/android-34/android.jar}"

XPOSED_JAR="${XPOSED_JAR:-$ROOT/app/libs/xposed-api-82.jar}"
KEYSTORE="${KEYSTORE:-$ROOT/tools/keystore/debug.jks}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-androiddebugkey}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"

APP_DIR="$ROOT/app"
APP_SRC="$APP_DIR/src/main/java"
APP_RES="$APP_DIR/src/main/res"
APP_ASSETS="$APP_DIR/src/main/assets"
APP_MANIFEST="$APP_DIR/src/main/AndroidManifest.xml"
DIAG_DIR="$ROOT/diag"
DIAG_SRC="$DIAG_DIR/src"
DIAG_MANIFEST="$DIAG_DIR/AndroidManifest.xml"
MODULE_DIR="$ROOT/module"

BUILD_DIR="$ROOT/build"
DIST_DIR="$ROOT/dist"

MIN_SDK=27
TARGET_SDK=34
APP_VERSION_NAME="${APP_VERSION_NAME:-1.1.1}"
APP_VERSION_CODE="${APP_VERSION_CODE:-3}"
ZIP="${ZIP:-zip}"

# ------------------------------------------------------------------ helpers ---
say()  { printf '==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

file_size() {
    if [ -f "$1" ]; then stat -f%z "$1" 2>/dev/null || stat -c%s "$1"; else echo 0; fi
}

require_file() { [ -f "$1" ] || die "missing required file: $1 ($2)"; }
require_dir()  { [ -d "$1" ] || die "missing required directory: $1 ($2)"; }

# --------------------------------------------------------------- toolchain ---
# Resolve JDK: prefer $JAVA_HOME, else fall back to javac found on PATH.
if [ -x "$JAVA_HOME/bin/javac" ]; then
    :
elif command -v javac >/dev/null 2>&1; then
    JAVA_HOME="$(cd "$(dirname "$(command -v javac)")/.." && pwd)"
    warn "JAVA_HOME not usable; falling back to javac on PATH ($JAVA_HOME)"
else
    die "no javac found. Set JAVA_HOME to a JDK 17 install, e.g. JAVA_HOME=/opt/homebrew/opt/openjdk@17"
fi

JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"
AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

[ -x "$JAVAC" ]  || die "missing javac: $JAVAC (set JAVA_HOME)"
[ -x "$KEYTOOL" ] || die "missing keytool: $KEYTOOL (set JAVA_HOME)"
[ -d "$BUILD_TOOLS" ] || die "missing build-tools dir: $BUILD_TOOLS (set ANDROID_SDK or BUILD_TOOLS)"
for t in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
    [ -x "$t" ] || die "missing Android build tool: $t (set BUILD_TOOLS to e.g. \$ANDROID_SDK/build-tools/35.0.0)"
done
[ -f "$ANDROID_JAR" ] || die "missing android.jar: $ANDROID_JAR (set ANDROID_JAR or ANDROID_SDK)"

say "JDK:          $JAVA_HOME"
say "Android SDK:  $ANDROID_SDK"
say "Build tools:  $BUILD_TOOLS"
say "android.jar:  $ANDROID_JAR"

# --------------------------------------------------------------- keystore ---
ensure_keystore() {
    if [ -f "$KEYSTORE" ]; then
        return 0
    fi
    say "creating throwaway debug keystore: $KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair \
        -keystore "$KEYSTORE" \
        -alias "$KEYSTORE_ALIAS" \
        -storepass "$KEYSTORE_PASS" \
        -keypass "$KEYSTORE_PASS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" \
        -storetype PKCS12
}

sign_apk() { # $1 = unsigned+aligned input, $2 = final output
    "$APKSIGNER" sign \
        --ks "$KEYSTORE" \
        --ks-key-alias "$KEYSTORE_ALIAS" \
        --ks-pass "pass:$KEYSTORE_PASS" \
        --key-pass "pass:$KEYSTORE_PASS" \
        --v4-signing-enabled false \
        --out "$2" \
        "$1"
    "$APKSIGNER" verify --verbose "$2" >/dev/null \
        || die "signature verification failed for $2"
}

# ------------------------------------------------------------- module zip ---
# A KernelSU/Magisk module is a plain ZIP whose ROOT contains module.prop.
# KernelSU's `ksud module install <zip>` does not need an update-binary.
build_module() {
    say "building KernelSU/Magisk module zip"
    local staging="$BUILD_DIR/module-staging"
    rm -rf "$staging"
    mkdir -p "$staging"

    # Copy whatever the Lead authored in module/ (may be empty at first).
    if [ -d "$MODULE_DIR" ]; then
        cp -R "$MODULE_DIR"/. "$staging"/ 2>/dev/null || true
    fi
    find "$staging" -name '.DS_Store' -delete 2>/dev/null || true

    if [ ! -f "$staging/module.prop" ]; then
        warn "module/module.prop not found — packaging a minimal placeholder module.prop"
        cat > "$staging/module.prop" <<'PROP'
id=grab-netprinter
name=GrabNetPrinter
version=1.0
versionCode=1
author=GrabNetPrinter contributors
description=Present a LAN ESC/POS printer (TCP 9100) to GrabMerchant as a bonded Bluetooth SPP printer.
PROP
        cat > "$staging/README.placeholder" <<'NOTE'
Placeholder module zip generated by tools/build.sh because module/module.prop
was missing at build time. Rebuild once module/ is authored.
NOTE
    fi

    local version="${MODULE_VERSION:-}"
    if [ -z "$version" ]; then
        version="$(awk -F= '/^version=/{sub(/^version=/, ""); print; exit}' "$staging/module.prop")"
    fi
    version="${version:-1.0}"
    # Keep the filename shell-safe.
    version="$(printf '%s' "$version" | tr -c 'A-Za-z0-9._+-' '_')"

    local out="$DIST_DIR/grab-netprinter-$version.zip"
    rm -f "$DIST_DIR"/grab-netprinter-*.zip
    rm -f "$out"
    # cd into the staging dir so module.prop ends up at the ZIP root.
    ( cd "$staging" && "$ZIP" -q -r -X "$out" . -x '*.DS_Store' )

    # Sanity: module.prop must be at the root, not under a subdirectory.
    # (write the listing to a file: piping unzip into `grep -q` can SIGPIPE
    # unzip, which `set -o pipefail` would misreport as a failure)
    unzip -Z1 "$out" > "$BUILD_DIR/module-entries.txt" 2>/dev/null || true
    if ! grep -qx 'module.prop' "$BUILD_DIR/module-entries.txt"; then
        die "module zip does not contain module.prop at its root: $out"
    fi
    say "module zip:   $out ($(file_size "$out") bytes, version=$version)"
}

# ------------------------------------------------------------- diagnostic ---
build_diag() {
    say "building diagnostic APK (gnp-diag.apk)"
    require_file "$DIAG_MANIFEST" "diag AndroidManifest.xml"
    require_dir "$DIAG_SRC" "diag Java sources"

    local dex="$BUILD_DIR/diag-dex"
    local classes="$BUILD_DIR/diag-classes"
    local gen="$BUILD_DIR/diag-gen"
    local list="$BUILD_DIR/diag-sources.txt"
    rm -rf "$dex" "$classes" "$gen"
    mkdir -p "$dex" "$classes" "$gen"

    # 1. manifest-only resource link (diag has no res/ by design)
    "$AAPT2" link \
        -o "$BUILD_DIR/diag-base.apk" \
        -I "$ANDROID_JAR" \
        --manifest "$DIAG_MANIFEST" \
        --min-sdk-version "$MIN_SDK" \
        --target-sdk-version "$TARGET_SDK" \
        --version-code 1 \
        --version-name "$APP_VERSION_NAME" \
        --java "$gen"

    if [ -d "$gen" ]; then
        find "$gen" -name '*.java' -type f > "$list" 2>/dev/null || : > "$list"
    else
        : > "$list"
    fi
    find "$DIAG_SRC" -name '*.java' -type f >> "$list" 2>/dev/null
    [ -s "$list" ] || die "no diag Java sources under $DIAG_SRC"
    [ -f "$gen/dev/blacksheep/grabnetprinter/diag/R.java" ] || warn "no generated R.java for diag (fine: diag uses no resources)"

    # 2. compile against android.jar only (no Xposed API needed)
    "$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
        -bootclasspath "$ANDROID_JAR" \
        -d "$classes" \
        @"$list"

    # 3. dex
    "$D8" --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$dex" \
        $(find "$classes" -name '*.class' -type f)

    # 4. add classes.dex at APK root, align, sign
    cp "$BUILD_DIR/diag-base.apk" "$BUILD_DIR/diag-unsigned.apk"
    ( cd "$dex" && "$ZIP" -q -X "$BUILD_DIR/diag-unsigned.apk" classes.dex )
    "$ZIPALIGN" -f -p 4 "$BUILD_DIR/diag-unsigned.apk" "$BUILD_DIR/diag-aligned.apk"
    ensure_keystore
    sign_apk "$BUILD_DIR/diag-aligned.apk" "$DIST_DIR/gnp-diag.apk"
    say "diag apk:     $DIST_DIR/gnp-diag.apk ($(file_size "$DIST_DIR/gnp-diag.apk") bytes)"
}

# ------------------------------------------------------------ module APK ---
build_apk() {
    say "building Xposed module APK (GrabNetPrinter.apk)"
    require_file "$APP_MANIFEST" "app AndroidManifest.xml"
    require_dir "$APP_SRC" "app Java sources"
    require_dir "$APP_RES" "app res/"
    require_dir "$APP_ASSETS" "app assets/"

    local reszip="$BUILD_DIR/app-res.zip"
    local gen="$BUILD_DIR/app-gen"
    local classes="$BUILD_DIR/app-classes"
    local dex="$BUILD_DIR/app-dex"
    local list="$BUILD_DIR/app-sources.txt"
    rm -rf "$gen" "$classes" "$dex" "$reszip" "$list"
    mkdir -p "$gen" "$classes" "$dex"

    # 1. compile + link resources and manifest (also emits R.java)
    "$AAPT2" compile --dir "$APP_RES" -o "$reszip"
    "$AAPT2" link \
        -o "$BUILD_DIR/app-base.apk" \
        -I "$ANDROID_JAR" \
        --manifest "$APP_MANIFEST" \
        "$reszip" \
        -A "$APP_ASSETS" \
        --java "$gen" \
        --min-sdk-version "$MIN_SDK" \
        --target-sdk-version "$TARGET_SDK" \
        --version-code "$APP_VERSION_CODE" \
        --version-name "$APP_VERSION_NAME"

    # 2. gather Java sources: app sources + generated R.java + optional extras
    find "$APP_SRC" -name '*.java' -type f > "$list" 2>/dev/null || : > "$list"
    find "$gen" -name '*.java' -type f >> "$list" 2>/dev/null || true
    if [ -n "${GNP_EXTRA_JAVA_SRC:-}" ]; then
        local oldifs="$IFS"
        IFS=':'
        for extra in $GNP_EXTRA_JAVA_SRC; do
            if [ -n "$extra" ]; then
                [ -d "$extra" ] || { IFS="$oldifs"; die "GNP_EXTRA_JAVA_SRC entry is not a directory: $extra"; }
                say "extra Java source root: $extra"
                find "$extra" -name '*.java' -type f >> "$list"
            fi
        done
        IFS="$oldifs"
    fi
    if [ ! -s "$list" ]; then
        die "no Java sources for the Xposed module: app/src/main/java is empty and GNP_EXTRA_JAVA_SRC is unset.
     The hook implementation (dev.blacksheep.grabnetprinter.HookEntry + helpers) is owned by
     another task. For a toolchain smoke test, pass a throwaway source root:
       GNP_EXTRA_JAVA_SRC=/tmp/gnp-hook-src tools/build.sh apk"
    fi

    # 3. compile: Xposed API v82 is compileOnly (classpath only, never packaged)
    require_file "$XPOSED_JAR" "Xposed API jar (run tools/fetch-api.sh or set XPOSED_JAR)"
    "$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
        -bootclasspath "$ANDROID_JAR" \
        -classpath "$XPOSED_JAR" \
        -d "$classes" \
        @"$list"

    # 4. the module entry point must exist (assets/xposed_init names it)
    if [ ! -f "$classes/dev/blacksheep/grabnetprinter/HookEntry.class" ]; then
        die "compiled classes do not contain dev.blacksheep.grabnetprinter.HookEntry
     (assets/xposed_init points at it). Check the hook sources under app/src/main/java."
    fi
    if ! grep -qx 'dev.blacksheep.grabnetprinter.HookEntry' "$APP_ASSETS/xposed_init"; then
        die "app/src/main/assets/xposed_init must contain exactly: dev.blacksheep.grabnetprinter.HookEntry"
    fi

    # 5. dex (missing Xposed API classes are expected warnings at runtime-provisioned deps)
    "$D8" --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$dex" \
        $(find "$classes" -name '*.class' -type f)

    # 6. add classes.dex + assets are already in (aapt2 -A), align, sign
    cp "$BUILD_DIR/app-base.apk" "$BUILD_DIR/app-unsigned.apk"
    ( cd "$dex" && "$ZIP" -q -X "$BUILD_DIR/app-unsigned.apk" classes.dex )
    "$ZIPALIGN" -f -p 4 "$BUILD_DIR/app-unsigned.apk" "$BUILD_DIR/app-aligned.apk"
    ensure_keystore
    sign_apk "$BUILD_DIR/app-aligned.apk" "$DIST_DIR/GrabNetPrinter.apk"
    say "module apk:   $DIST_DIR/GrabNetPrinter.apk ($(file_size "$DIST_DIR/GrabNetPrinter.apk") bytes)"
}

# ---------------------------------------------------------------- summary ---
print_summary() {
    echo
    echo "=== build complete ==="
    local found=0
    for f in "$DIST_DIR"/*; do
        [ -f "$f" ] || continue
        found=1
        printf '  %-52s %10s bytes\n' "$f" "$(file_size "$f")"
    done
    [ "$found" -eq 1 ] || warn "no artifacts in $DIST_DIR"
    echo
    echo "Verify with: tools/verify.sh"
}

# ------------------------------------------------------------------- main ---
if [ "$#" -eq 0 ]; then
    set -- all
fi

BUILD_ALL=0
BUILD_APK=0
BUILD_DIAG=0
BUILD_MODULE=0
for t in "$@"; do
    case "$t" in
        all)    BUILD_ALL=1 ;;
        apk)    BUILD_APK=1 ;;
        diag)   BUILD_DIAG=1 ;;
        module) BUILD_MODULE=1 ;;
        *)      die "unknown target '$t' (use: all | apk | diag | module)" ;;
    esac
done

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR" "$DIST_DIR"

if [ "$BUILD_ALL" -eq 1 ] || [ "$BUILD_MODULE" -eq 1 ]; then build_module; fi
if [ "$BUILD_ALL" -eq 1 ] || [ "$BUILD_DIAG" -eq 1 ]; then build_diag; fi
if [ "$BUILD_ALL" -eq 1 ] || [ "$BUILD_APK" -eq 1 ]; then build_apk; fi

print_summary
