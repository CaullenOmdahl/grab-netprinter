#!/usr/bin/env bash
# Roll back the add-on. Default: disable our module + uninstall the APK (safe, reversible).
#   deploy/rollback.sh [--serial host:port] [--purge] [--all] [--reboot]
#     --purge  remove the grab-netprinter module directory instead of just disabling it
#     --all    also disable ZygiskNext + LSPosed (use if the POS misbehaves after install)
set -uo pipefail
source "$(dirname "$0")/common.sh"

PURGE=0; ALL=0; DO_REBOOT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --purge) PURGE=1; shift ;;
    --all) ALL=1; shift ;;
    --reboot) DO_REBOOT=1; shift ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) die "unknown arg: $1" ;;
  esac
done

require_adb
connect_device
root_adb

log "uninstalling GrabNetPrinter APK (if present)"
adb_dev uninstall dev.blacksheep.grabnetprinter >/dev/null 2>&1 || true

if [ "$PURGE" -eq 1 ]; then
  log "removing grab-netprinter module directory"
  adb_dev shell 'rm -rf /data/adb/modules/grab-netprinter' 2>/dev/null || true
else
  log "disabling grab-netprinter module (kept on disk; delete /data/adb/modules/grab-netprinter/disable to re-enable)"
  adb_dev shell 'mkdir -p /data/adb/modules/grab-netprinter; touch /data/adb/modules/grab-netprinter/disable' 2>/dev/null || true
fi
adb_dev shell 'rm -f /data/local/tmp/grab-netprinter.conf /data/local/tmp/grab-netprinter.log*' 2>/dev/null || true

if [ "$ALL" -eq 1 ]; then
  log "disabling every non-core module (ZygiskNext, LSPosed, ...) except grab-*"
  adb_dev shell 'for d in /data/adb/modules/*/; do id=$(basename "$d"); case "$id" in grab-netprinter) continue;; esac; touch "${d}disable"; echo "disabled $id"; done'
fi

if [ "$DO_REBOOT" -eq 1 ]; then
  log "rebooting POS"
  adb_dev reboot
else
  warn "reboot for the change to take effect"
fi
