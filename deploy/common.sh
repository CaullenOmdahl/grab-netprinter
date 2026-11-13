#!/usr/bin/env bash
# Shared helpers for GrabNetPrinter deploy scripts.
# Override the target with SERIAL=host:port or --serial.
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SERIAL=${SERIAL:-192.168.1.100}
REMOTE=/data/local/tmp/gnp
KSUD=/data/adb/ksud

log()  { printf '\033[1;34m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

require_adb() {
  command -v adb >/dev/null 2>&1 || die "adb not found on PATH"
}

adb_dev() { adb -s "$SERIAL" "$@" 2>&1; }

connect_device() {
  [ -n "$SERIAL" ] || die "SERIAL is not set; use SERIAL=host:port or --serial host:port"
  adb_dev get-state >/dev/null 2>&1 || adb connect "$SERIAL" >/dev/null 2>&1
  for _ in $(seq 1 25); do
    adb_dev get-state >/dev/null 2>&1 && return 0
    sleep 1
  done
  die "cannot reach '$SERIAL'. Set SERIAL=host:port or pass --serial host:port (adb connect first if needed)."
}

root_adb() {
  if adb_dev shell id 2>/dev/null | grep -q 'uid=0'; then return 0; fi
  adb_dev root >/dev/null 2>&1 || true
  sleep 2
  for _ in $(seq 1 20); do
    adb_dev shell id 2>/dev/null | grep -q 'uid=0' && return 0
    sleep 1
  done
  die "adb root failed (device not userdebug or adb dropped)"
}

check_device() {
  local bliss sdk abil
  bliss=$(adb_dev shell getprop ro.bliss.build 2>/dev/null | tr -d '\r')
  sdk=$(adb_dev shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
  abil=$(adb_dev shell getprop ro.product.cpu.abilist 2>/dev/null | tr -d '\r')
  log "device : ${bliss:-unknown} (SDK ${sdk:-?}, ${abil:-?})"
  case "$sdk" in 34) ;; *) warn "expected Android 14/SDK 34, got '${sdk:-?}'";; esac
  case "$abil" in *x86_64*) ;; *) warn "expected x86_64 abilist, got '$abil'";; esac
  adb_dev shell test -x "$KSUD" || die "KernelSU ($KSUD) not found — this module expects KernelSU/Magisk root"
}

module_ids() {
  adb_dev shell 'for f in /data/adb/modules/*/module.prop; do [ -f "$f" ] && grep -h "^id=" "$f"; done' 2>/dev/null | tr -d '\r'
}

install_module_zip() {
  local zip="$1" remote="$REMOTE/$(basename "$1")"
  adb_dev shell mkdir -p "$REMOTE" >/dev/null 2>&1
  adb_dev push "$zip" "$remote" >/dev/null 2>&1 || { warn "push failed: $zip"; return 1; }
  local out
  out=$(adb_dev shell "$KSUD module install '$remote'" 2>&1 | tr -d '\r')
  if echo "$out" | grep -qiE 'error|fail|unknown command|usage'; then
    warn "ksud module install failed for $(basename "$zip"): $out"
    warn "fallback: install it from KernelSU Manager > Modules > Install from storage (file is at $remote)"
    return 1
  fi
  log "installed module zip: $(basename "$zip")"
  return 0
}
