#!/usr/bin/env bash
# Post-reboot verification.
#
#   deploy/verify.sh [--serial host:port]
#
# Checks the root prerequisites (Zygisk + LSPosed), our KernelSU module + config, printer
# reachability from the POS, and whether GrabNetPrinter installed its hooks into
# com.grab.merchant. Everything is read-only except force-stopping/starting GrabMerchant.
set -uo pipefail
source "$(dirname "$0")/common.sh"

while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) die "unknown arg: $1" ;;
  esac
done

require_adb
connect_device
root_adb
check_device

echo
log "live KernelSU modules:"
module_ids | sed 's/^/  /'
for id in zygisksu zygisk_lsposed grab-netprinter; do
  if adb_dev shell "test -d /data/adb/modules/$id" >/dev/null 2>&1; then
    log "  module dir present: /data/adb/modules/$id"
  else
    warn "  module dir MISSING: /data/adb/modules/$id"
  fi
done

echo
log "Zygisk + LSPosed daemons:"
LSPD=$(adb_dev shell 'ps -A 2>/dev/null | grep -w lspd | head -1' | tr -d '\r')
if [ -n "$LSPD" ]; then log "  lspd running: $LSPD"; else warn "  lspd NOT running (framework not started)"; fi
adb_dev shell 'ls -la /data/adb/zygisksu 2>/dev/null' | head -6 | sed 's/^/  /'
if adb_dev shell 'ls /data/adb/lspd/log/* 2>/dev/null' | grep -q .; then
  log "  latest LSPosed log:"
  adb_dev shell 'ls -t /data/adb/lspd/log/* 2>/dev/null | head -1 | xargs tail -8 2>/dev/null' | sed 's/^/  /'
else
  warn "  no /data/adb/lspd/log entries yet"
fi

echo
log "our config (/data/local/tmp/grab-netprinter.conf):"
adb_dev shell 'cat /data/local/tmp/grab-netprinter.conf 2>/dev/null | grep -vE "^#|^$"' | sed 's/^/  /'

echo
PH=$(adb_dev shell 'grep "^printer_host=" /data/local/tmp/grab-netprinter.conf 2>/dev/null | cut -d= -f2' | tr -d '\r')
PP=$(adb_dev shell 'grep "^printer_port=" /data/local/tmp/grab-netprinter.conf 2>/dev/null | cut -d= -f2' | tr -d '\r')
if [ -z "$PH" ]; then
  warn "printer_host is not configured in /data/local/tmp/grab-netprinter.conf; skipping check"
  warn "  (set it in the GrabNetPrinter settings app, or edit the module's config.prop)"
else
  [ -n "$PP" ] || PP=9100
  log "printer reachability ($PH:$PP):"
  if adb_dev shell "timeout 3 nc -w 2 $PH $PP </dev/null" >/dev/null 2>&1; then
    log "  reachable"
  else
    warn "  NOT reachable via nc (printer off? wrong IP? see docs/troubleshooting.md)"
  fi
fi

echo
log "restarting GrabMerchant and looking for hook startup logs"
adb_dev shell 'am force-stop com.grab.merchant' >/dev/null 2>&1
adb_dev shell 'am start --user 0 -n com.grab.merchant/com.grab.mex.activity.MainActivity' >/dev/null 2>&1
sleep 8
HOOKLOG=$(adb_dev logcat -d -s GrabNetPrinter 2>/dev/null | tail -40)
[ -n "$HOOKLOG" ] && echo "$HOOKLOG" | sed 's/^/  /'
echo
log "module log file (authoritative: LSPosed routes XposedBridge.log to its own log, not logcat):"
adb_dev shell 'tail -30 /data/local/tmp/grab-netprinter.log 2>/dev/null' | sed 's/^/  /'
echo
log "hook startup lines in LSPosed log:"
adb_dev shell 'grep -h -F "GNP: hooks installed" /data/adb/lspd/log/verbose_* 2>/dev/null | tail -3; grep -h -F "TCP connected" /data/local/tmp/grab-netprinter.log 2>/dev/null | tail -2' 2>/dev/null | sed 's/^/  /'

echo
if adb_dev shell 'grep -q -F "GNP: hooks installed" /data/local/tmp/grab-netprinter.log 2>/dev/null' >/dev/null 2>&1 \
   || adb_dev shell 'grep -q -F "GNP: hooks installed" /data/adb/lspd/log/verbose_* 2>/dev/null' >/dev/null 2>&1; then
  log "RESULT: GrabNetPrinter hooks ARE installed in com.grab.merchant"
else
  warn "RESULT: no hook startup line. Checklist:"
  warn "  - LSPosed Manager: GrabNetPrinter enabled AND scope com.grab.merchant ticked"
  warn "    (open: adb shell sh /data/adb/modules/zygisk_lsposed/action.sh)"
  warn "  - lspd daemon running (see above); ZygiskNext module live"
  warn "  - /data/local/tmp/grab-netprinter.conf readable, enabled=1, printer_host set"
  warn "  - GrabMerchant was force-stopped after enabling the scope"
  warn "  See docs/troubleshooting.md"
fi

echo
log "recent framework errors (if any):"
# toybox grep on this image segfaults on some -E patterns, so pull the log and grep on the host.
adb_dev shell 'logcat -d -f /data/local/tmp/gnp-logcat.txt 2>/dev/null' >/dev/null 2>&1
if adb_dev pull /data/local/tmp/gnp-logcat.txt /tmp/gnp-logcat.txt >/dev/null 2>&1; then
  grep -iE 'lsposed|zygisk|GrabNetPrinter.*(error|fail|FATAL)' /tmp/gnp-logcat.txt 2>/dev/null | tail -15 | sed 's/^/  /'
  adb_dev shell 'rm -f /data/local/tmp/gnp-logcat.txt' >/dev/null 2>&1
fi
