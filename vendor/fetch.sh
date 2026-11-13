#!/usr/bin/env bash
# fetch.sh — download the exact prerequisite module zips for grab-netprinter.
#
# Target device: Bliss OS 17.2 "Zenith" (Android 14 / SDK 34, x86_64),
# KernelSU 0.9.2, SELinux permissive, no Zygisk, no LSPosed.
#
# Idempotent: an existing file whose sha256 matches is left untouched.
# Usage: ./fetch.sh [--force]
#   --force   re-download every asset even if the local sha256 matches.
#
# Output: vendor/<name>.zip plus a printed version/sha256/size table.
# NOTE: deploy/install.sh explicitly picks the primary pair by filename; the
# fallback zip names (vector-*, neozygisk-*) are only used with --framework-vector
# / --zygisk-neo. NeoZygisk shares module id `zygisksu` with ZygiskNext and Vector
# disables LSPosed, so never install the fallbacks together with the primaries.
set -euo pipefail

VENDOR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

# ---------------------------------------------------------------------------
# Artifact table. Keep in sync with research/compat/artifacts.md.
# Format: local-name|dest-subdir ("" = vendor/ root)|version|sha256|url
# ---------------------------------------------------------------------------
ARTIFACTS=(
  # PRIMARY Zygisk provider: ZygiskNext v1.5.3 (module id: zygisksu)
  "zygisknext-v1.5.3||ZygiskNext v1.5.3 (880-22a1bf5)|186a0a26c9fcb895af1954b42de831433c23d72d1bd0758b986069154359db67|https://github.com/Dr-TSNG/ZygiskNext/releases/download/v1.5.3/Zygisk-Next-1.5.3-880-22a1bf5-release.zip"
  # PRIMARY Xposed framework: LSPosed v1.11.0, JingMatrix fork (module id: zygisk_lsposed)
  "lsposed-v1.11.0-zygisk||LSPosed v1.11.0 (7209, JingMatrix)|6b8455e08dfa13a8121619fe013a79b40e3e5a1291555c91cc7ee1870a56b65c|https://github.com/JingMatrix/Vector/releases/download/v1.11.0/LSPosed-v1.11.0-7209-zygisk-release.zip"
  # FALLBACK Xposed framework: Vector v2.2 (renamed maintained fork; module id: zygisk_vector)
  "vector-v2.2||Vector v2.2 (3080-88f8e1fa)|9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc|https://github.com/JingMatrix/Vector/releases/download/v2.2/Vector-v2.2-3080-Release.zip"
  # FALLBACK Zygisk provider: NeoZygisk v2.4 (module id: zygisksu — CONFLICTS with ZygiskNext)
  "neozygisk-v2.4||NeoZygisk v2.4 (289-08080ef)|93a1425c67bb89f58a0dcb9fc823ec93f6472214f221e667a70b67c6a6e061a1|https://github.com/JingMatrix/NeoZygisk/releases/download/v2.4/NeoZygisk-v2.4-289-release.zip"
)

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    echo "ERROR: neither shasum nor sha256sum available" >&2
    exit 1
  fi
}

size_of() {
  # macOS/BSD stat then GNU stat
  stat -f%z "$1" 2>/dev/null || stat -c%s "$1"
}

download() {
  local name="$1" subdir="$2" ver="$3" want="$4" url="$5"
  local dir="$VENDOR_DIR${subdir:+/$subdir}"
  local dest="$dir/$name.zip"
  local tmp="$dest.part"
  mkdir -p "$dir"

  if [ -f "$dest" ] && [ "$FORCE" -eq 0 ]; then
    local got
    got="$(sha256_of "$dest")"
    if [ "$got" = "$want" ]; then
      printf '  [skip] %-26s already present, sha256 OK\n' "$name.zip"
      return 0
    fi
    printf '  [redo] %-26s local sha256 mismatch (%s), re-downloading\n' "$name.zip" "$got"
  fi

  printf '  [get ] %-26s %s\n' "$name.zip" "$url"
  rm -f "$tmp"
  curl -fSL --retry 3 --retry-delay 2 -o "$tmp" "$url"

  local got
  got="$(sha256_of "$tmp")"
  if [ "$got" != "$want" ]; then
    echo "ERROR: sha256 mismatch for $name.zip" >&2
    echo "  expected: $want" >&2
    echo "  got:      $got" >&2
    rm -f "$tmp"
    exit 1
  fi
  mv "$tmp" "$dest"
  printf '  [ ok ] %-26s sha256 verified\n' "$name.zip"
}

echo "grab-netprinter vendor fetch"
echo "target dir: $VENDOR_DIR"
echo

for entry in "${ARTIFACTS[@]}"; do
  IFS='|' read -r name subdir ver want url <<<"$entry"
  download "$name" "$subdir" "$ver" "$want" "$url"
done

echo
echo "== vendor/ contents =="
printf '%-28s %-34s %-12s %s\n' "FILE" "VERSION" "SIZE" "SHA256"
for entry in "${ARTIFACTS[@]}"; do
  IFS='|' read -r name subdir ver want url <<<"$entry"
  printf '%-28s %-34s %-12s %s\n' "$name.zip" "$ver" "$(size_of "$VENDOR_DIR/$name.zip")" "$want"
done
echo
echo "All artifacts verified. Install the PRIMARY pair with ksud (see docs/compat.md):"
echo "  adb push vendor/zygisknext-v1.5.3.zip vendor/lsposed-v1.11.0-zygisk.zip /data/local/tmp/"
echo "  adb shell '/data/adb/ksud module install /data/local/tmp/zygisknext-v1.5.3.zip'"
echo "  adb shell '/data/adb/ksud module install /data/local/tmp/lsposed-v1.11.0-zygisk.zip'"
