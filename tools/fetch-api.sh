#!/usr/bin/env bash
#
# Fetch (or re-fetch) the official Xposed API v82 jar used as a compileOnly
# dependency by the GrabNetPrinter Xposed module.
#
# The jar is vendored in the repo at app/libs/xposed-api-82.jar so that
# tools/build.sh never needs network access. Run this script only if that file
# is missing or you want to audit/re-create it.
#
# The official host https://api.xposed.info/api-82.jar currently returns 404,
# so we use the Maven artifact de.robv.android.xposed:api:82 from public
# mirrors instead:
#   https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar
#   https://maven.aliyun.com/repository/jcenter/de/robv/android/xposed/api/82/api-82.jar
#
# Usage:
#   tools/fetch-api.sh           # no-op if the vendored jar matches SHA-256
#   tools/fetch-api.sh --force   # always download and replace
#   XPOSED_API_URL=<url> tools/fetch-api.sh --force
#
# Vendored artifact (de.robv.android.xposed:api:82, official 2016-04-15 build):
#   size   : 25478 bytes
#   sha256 : f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
DEST="$ROOT/app/libs/xposed-api-82.jar"

EXPECTED_SHA256="f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25"
EXPECTED_CLASSES=(
    "de/robv/android/xposed/IXposedHookLoadPackage.class"
    "de/robv/android/xposed/IXposedHookZygoteInit.class"
    "de/robv/android/xposed/XposedBridge.class"
    "de/robv/android/xposed/XposedHelpers.class"
    "de/robv/android/xposed/XC_MethodHook.class"
    "de/robv/android/xposed/XC_MethodReplacement.class"
    "de/robv/android/xposed/callbacks/XC_LoadPackage\$LoadPackageParam.class"
)

MIRRORS=(
    "https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar"
    "https://maven.aliyun.com/repository/jcenter/de/robv/android/xposed/api/82/api-82.jar"
    "https://api.xposed.info/api-82.jar"
)
if [ -n "${XPOSED_API_URL:-}" ]; then
    MIRRORS=("$XPOSED_API_URL")
fi

say() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        die "need shasum or sha256sum to verify the jar"
    fi
}

verify_jar() { # $1 = path; validates it is a zip with the expected classes
    local jar="$1" entries rc=0
    [ -f "$jar" ] || return 1
    entries="$(mktemp)"
    # Write the entry list to a file first: piping unzip into `grep -q` can
    # SIGPIPE unzip, which `set -o pipefail` would report as a failure.
    if ! unzip -Z1 "$jar" > "$entries" 2>/dev/null; then
        rm -f "$entries"
        return 1
    fi
    for c in "${EXPECTED_CLASSES[@]}"; do
        if ! grep -qxF -- "$c" "$entries"; then
            printf '  missing expected class: %s\n' "$c" >&2
            rc=1
            break
        fi
    done
    rm -f "$entries"
    return "$rc"
}

CHECK=1
FORCE=0
for a in "$@"; do
    case "$a" in
        --force) FORCE=1 ;;
        --no-sha-check) CHECK=0 ;;
        -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) die "unknown argument: $a (use --force)" ;;
    esac
done

if [ "$FORCE" -eq 0 ] && [ -f "$DEST" ]; then
    say "vendored jar already present: $DEST"
    if [ "$CHECK" -eq 1 ]; then
        actual="$(sha256_of "$DEST")"
        if [ "$actual" != "$EXPECTED_SHA256" ]; then
            die "sha256 mismatch for $DEST
     expected: $EXPECTED_SHA256
     actual:   $actual
     Re-run with --force to re-download, or --no-sha-check to keep it."
        fi
    fi
    verify_jar "$DEST" || die "vendored jar is not a valid Xposed API jar: $DEST"
    say "sha256 OK: $EXPECTED_SHA256"
    exit 0
fi

command -v curl >/dev/null 2>&1 || die "curl is required to download the jar"
command -v unzip >/dev/null 2>&1 || die "unzip is required to verify the jar"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for url in "${MIRRORS[@]}"; do
    say "downloading $url"
    if ! curl -fsSL --retry 2 --connect-timeout 15 -o "$TMP/api-82.jar" "$url"; then
        printf 'WARNING: download failed from %s\n' "$url" >&2
        continue
    fi
    if ! verify_jar "$TMP/api-82.jar"; then
        printf 'WARNING: not a valid Xposed API jar (HTML error page?), trying next mirror\n' >&2
        continue
    fi
    actual="$(sha256_of "$TMP/api-82.jar")"
    if [ "$CHECK" -eq 1 ] && [ "$actual" != "$EXPECTED_SHA256" ]; then
        printf 'WARNING: sha256 mismatch from %s\n  expected %s\n  actual   %s\n' \
            "$url" "$EXPECTED_SHA256" "$actual" >&2
        continue
    fi
    mkdir -p "$(dirname "$DEST")"
    cp "$TMP/api-82.jar" "$DEST"
    say "wrote $DEST"
    say "size:   $(stat -f%z "$DEST" 2>/dev/null || stat -c%s "$DEST") bytes"
    say "sha256: $actual"
    exit 0
done

die "could not fetch the Xposed API v82 jar from any mirror.
     The build does not require network access: app/libs/xposed-api-82.jar is
     normally vendored in the repo. Only if that file is missing do you need to
     restore it with tools/fetch-api.sh (or point XPOSED_API_URL somewhere valid)."
