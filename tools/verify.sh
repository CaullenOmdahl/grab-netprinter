#!/usr/bin/env bash
#
# GrabNetPrinter — verify the built artifacts in dist/.
#
# Checks (non-zero exit if any fails):
#   dist/GrabNetPrinter.apk   package/version/label via aapt2 dump badging,
#                             xposed meta-data (xposedmodule, xposeddescription,
#                             xposedminversion=93, xposedscope) + scope array,
#                             classes.dex + assets/xposed_init present,
#                             xposed_init content is the HookEntry class name,
#                             Xposed API classes NOT packaged (compileOnly),
#                             apksigner verify (v1/v2/v3 schemes reported).
#   dist/grab-netprinter-*.zip module.prop at ZIP ROOT, module.prop content printed,
#                             zip integrity, key=value sanity.
#   dist/gnp-diag.apk         package dev.blacksheep.grabnetprinter.diag,
#                             launcher activity, BT permissions, classes.dex,
#                             apksigner verify.
#
# Usage: tools/verify.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

ANDROID_SDK="${ANDROID_SDK:-$HOME/Library/Android/sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_SDK/build-tools/35.0.0}"
AAPT2="$BUILD_TOOLS/aapt2"
APKSIGNER="$BUILD_TOOLS/apksigner"
DIST="$ROOT/dist"

APK="$DIST/GrabNetPrinter.apk"
DIAG_APK="$DIST/gnp-diag.apk"

PASS=0
FAIL=0
ok()   { PASS=$((PASS + 1)); printf '  [ OK ] %s\n' "$*"; }
bad()  { FAIL=$((FAIL + 1)); printf '  [FAIL] %s\n' "$*" >&2; }
info() { printf '  [info] %s\n' "$*"; }
hdr()  { printf '\n=== %s ===\n' "$*"; }

[ -x "$AAPT2" ]     || { echo "ERROR: missing aapt2 at $AAPT2 (set BUILD_TOOLS)" >&2; exit 2; }
[ -x "$APKSIGNER" ] || { echo "ERROR: missing apksigner at $APKSIGNER (set BUILD_TOOLS)" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

file_size() { stat -f%z "$1" 2>/dev/null || stat -c%s "$1"; }

# list_zip <zip> <outfile>  — write sorted entry list, no pipes (pipefail-safe)
list_zip() {
    if unzip -Z1 "$1" > "$2" 2>/dev/null; then
        sort -o "$2" "$2"
        return 0
    fi
    : > "$2"
    return 1
}

# check_grep <label> <text> <extended-regex>
check_grep() {
    if grep -Eq -- "$3" <<<"$2"; then
        ok "$1"
    else
        bad "$1 (pattern not found: $3)"
    fi
}

# check_no_grep <label> <text> <extended-regex>
check_no_grep() {
    if grep -Eq -- "$3" <<<"$2"; then
        bad "$1 (unexpected match: $3)"
    else
        ok "$1"
    fi
}

# check_line_after <label> <text> <anchor-regex> <value-regex>
# aapt2 xmltree prints an attribute's name and value on consecutive lines.
check_line_after() {
    local ctx
    ctx="$(grep -A1 -E -- "$3" <<<"$2" || true)"
    if grep -qE -- "$4" <<<"$ctx"; then
        ok "$1"
    else
        bad "$1 (no '$4' after '$3')"
    fi
}

# check_entry <label> <listing-file> <exact ZIP entry>
check_entry() {
    if grep -qxF -- "$3" "$2"; then
        ok "$1"
    else
        bad "$1 (missing ZIP entry '$3')"
    fi
}

# check_no_entry <label> <listing-file> <extended-regex over entry names>
check_no_entry() {
    if grep -Eq -- "$3" "$2"; then
        bad "$1 (unexpected ZIP entries matching: $3)"
    else
        ok "$1"
    fi
}

# ---------------------------------------------------------------- main APK ---
hdr "dist/GrabNetPrinter.apk"
if [ ! -f "$APK" ]; then
    bad "missing $APK (run tools/build.sh apk)"
else
    BADGE="$("$AAPT2" dump badging "$APK" 2>&1)"
    check_grep "package name is dev.blacksheep.grabnetprinter" "$BADGE" "^package: name='dev\.blacksheep\.grabnetprinter'"
    check_grep "versionCode present" "$BADGE" "versionCode='[0-9]+'"
    check_grep "versionName present" "$BADGE" "versionName='[^']+'"
    check_grep "minSdkVersion 27" "$BADGE" "^minSdkVersion:'27'"
    check_grep "targetSdkVersion 34" "$BADGE" "^targetSdkVersion:'34'"
    check_grep "application label GrabNetPrinter" "$BADGE" "application-label:'GrabNetPrinter'"
    check_grep "settings launcher activity present" "$BADGE" "^launchable-activity: name='dev\.blacksheep\.grabnetprinter\\.SettingsActivity'"

    XMLTREE="$("$AAPT2" dump xmltree "$APK" --file AndroidManifest.xml 2>&1)"
    check_grep "meta-data xposedmodule" "$XMLTREE" 'xposedmodule'
    check_grep "meta-data xposeddescription" "$XMLTREE" 'xposeddescription'
    check_grep "meta-data xposedminversion" "$XMLTREE" 'xposedminversion'
    check_line_after "xposedminversion integer value 93" "$XMLTREE" 'xposedminversion' 'value\(0x01010024\)=93'
    check_grep "meta-data xposedscope" "$XMLTREE" 'xposedscope'
    check_grep "settings activity declared" "$XMLTREE" "SettingsActivity"
    check_grep "config provider declared" "$XMLTREE" "ConfigProvider"
    check_grep "config provider authority" "$XMLTREE" 'dev\.blacksheep\.grabnetprinter\.config'
    check_grep "xposeddescription text" "$XMLTREE" 'Make a LAN ESC/POS printer appear as a bonded Bluetooth SPP printer to GrabMerchant'

    RESOURCES="$("$AAPT2" dump resources "$APK" 2>&1)"
    check_grep "xposed_scope array contains com.grab.merchant" "$RESOURCES" 'com\.grab\.merchant'
    check_grep "app_name string = GrabNetPrinter" "$RESOURCES" '"GrabNetPrinter"'

    APK_LIST="$WORK/apk.list"
    list_zip "$APK" "$APK_LIST" || true
    check_entry "contains classes.dex" "$APK_LIST" 'classes.dex'
    check_entry "contains assets/xposed_init" "$APK_LIST" 'assets/xposed_init'

    INIT_CONTENT="$(unzip -p "$APK" assets/xposed_init 2>/dev/null | tr -d '\r\n' || true)"
    if [ "$INIT_CONTENT" = "dev.blacksheep.grabnetprinter.HookEntry" ]; then
        ok "assets/xposed_init = dev.blacksheep.grabnetprinter.HookEntry"
    else
        bad "assets/xposed_init content is '$INIT_CONTENT' (expected dev.blacksheep.grabnetprinter.HookEntry)"
    fi

    # compileOnly: the Xposed API must NOT be bundled in the APK.
    check_no_entry "Xposed API classes not packaged (compileOnly)" "$APK_LIST" '^de/robv/android/xposed/'

    if SIG="$("$APKSIGNER" verify --verbose "$APK" 2>&1)"; then
        if grep -q '^Verifies' <<<"$SIG"; then
            ok "apksigner verify: signed"
        else
            bad "apksigner verify: unexpected output"
        fi
    else
        bad "apksigner verify failed"
        printf '%s\n' "$SIG" | sed 's/^/         /' >&2
    fi
    check_grep "APK Signature Scheme v2 present" "$SIG" 'Verified using v2 scheme \(APK Signature Scheme v2\): true'
    info "size: $(file_size "$APK") bytes"
fi

# --------------------------------------------------------------- module zip ---
hdr "dist/grab-netprinter-<version>.zip"
ZIPS=0
for z in "$DIST"/grab-netprinter-*.zip; do
    [ -f "$z" ] || continue
    ZIPS=$((ZIPS + 1))
    info "checking $z"
    if ! unzip -t "$z" >/dev/null 2>&1; then
        bad "zip is corrupt: $z"
        continue
    fi
    ok "zip integrity"

    ZIP_LIST="$WORK/module-$ZIPS.list"
    list_zip "$z" "$ZIP_LIST" || true
    check_entry "module.prop is at ZIP root (KernelSU/Magisk layout)" "$ZIP_LIST" 'module.prop'
    check_no_entry "module.prop is not nested under a directory" "$ZIP_LIST" '^[^/]+/module\.prop$'

    PROPFILE="$(unzip -p "$z" module.prop 2>/dev/null || true)"
    if grep -q '^id=' <<<"$PROPFILE"; then
        ok "module.prop has id="
    else
        bad "module.prop missing id="
    fi
    if grep -q '^version=' <<<"$PROPFILE"; then
        ok "module.prop has version="
    else
        bad "module.prop missing version="
    fi
    if grep -q '^versionCode=' <<<"$PROPFILE"; then
        ok "module.prop has versionCode="
    else
        bad "module.prop missing versionCode="
    fi
    echo "  --- module.prop ---"
    printf '%s\n' "$PROPFILE" | sed 's/^/  /'
    echo "  -------------------"
    info "size: $(file_size "$z") bytes"
    info "entries:"
    sed 's/^/           /' "$ZIP_LIST"
done
if [ "$ZIPS" -eq 0 ]; then
    bad "no dist/grab-netprinter-*.zip found (run tools/build.sh module)"
fi

# ----------------------------------------------------------------- diag APK ---
hdr "dist/gnp-diag.apk"
if [ ! -f "$DIAG_APK" ]; then
    bad "missing $DIAG_APK (run tools/build.sh diag)"
else
    DBADGE="$("$AAPT2" dump badging "$DIAG_APK" 2>&1)"
    check_grep "package is dev.blacksheep.grabnetprinter.diag" "$DBADGE" "^package: name='dev\.blacksheep\.grabnetprinter\.diag'"
    check_grep "has launcher activity" "$DBADGE" "^launchable-activity:"
    check_grep "minSdkVersion 27" "$DBADGE" "^minSdkVersion:'27'"
    check_grep "targetSdkVersion 34" "$DBADGE" "^targetSdkVersion:'34'"
    check_grep "declares BLUETOOTH_CONNECT" "$DBADGE" "uses-permission: name='android\.permission\.BLUETOOTH_CONNECT'"
    check_grep "declares BLUETOOTH_SCAN" "$DBADGE" "uses-permission: name='android\.permission\.BLUETOOTH_SCAN'"
    check_grep "declares INTERNET" "$DBADGE" "uses-permission: name='android\.permission\.INTERNET'"

    DIAG_LIST="$WORK/diag.list"
    list_zip "$DIAG_APK" "$DIAG_LIST" || true
    check_entry "contains classes.dex" "$DIAG_LIST" 'classes.dex'

    if DSIG="$("$APKSIGNER" verify --verbose "$DIAG_APK" 2>&1)"; then
        if grep -q '^Verifies' <<<"$DSIG"; then
            ok "apksigner verify: signed"
        else
            bad "apksigner verify: unexpected output"
        fi
    else
        bad "apksigner verify failed"
        printf '%s\n' "$DSIG" | sed 's/^/         /' >&2
    fi
    info "size: $(file_size "$DIAG_APK") bytes"
fi

# ------------------------------------------------------------------ summary ---
hdr "summary"
printf '  %d checks passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
    echo "RESULT: FAILED" >&2
    exit 1
fi
echo "RESULT: ALL CHECKS PASSED"
