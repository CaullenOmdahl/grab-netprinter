#!/usr/bin/env bash
# Register/enable GrabNetPrinter in LSPosed and set its scope to com.grab.merchant,
# by editing LSPosed's SQLite config directly. This is the reliable fallback when the
# parasitic LSPosed manager cannot be opened on an Android-x86 build.
#
# Validated on Android 14 with LSPosed v1.11.0.
# Requires a REBOOT afterwards (the LSPosed daemon loads the config at startup).
#
#   deploy/enable-scope.sh [--serial host:port] [--target com.grab.merchant] [--no-reboot]
set -uo pipefail
source "$(dirname "$0")/common.sh"

MODULE_PKG=dev.blacksheep.grabnetprinter
TARGET=com.grab.merchant
DO_REBOOT=1
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --target) TARGET="$2"; shift 2 ;;
    --no-reboot) DO_REBOOT=0; shift ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) die "unknown arg: $1" ;;
  esac
done

command -v sqlite3 >/dev/null 2>&1 || die "host sqlite3 is required (brew install sqlite)"
require_adb
connect_device
root_adb

command -v adb >/dev/null || die "adb missing"
adb_dev shell 'test -d /data/adb/modules/zygisk_lsposed' >/dev/null 2>&1 \
  || die "LSPosed is not installed (docs/deploy.md step 2)"

APK=$(adb_dev shell "pm path $MODULE_PKG" 2>/dev/null | sed 's/^package://' | tr -d '\r' | head -1)
[ -n "$APK" ] || die "$MODULE_PKG is not installed — run deploy/install.sh first"
log "module APK: $APK"

TMP=$(mktemp -d /tmp/gnp-lspd.XXXXXX)
trap 'rm -rf "$TMP"' EXIT

# Quiesce the daemon so the DB/WAL are consistent, then pull all three files.
log "stopping lspd (it is restarted by the reboot)"
adb_dev shell 'kill -9 $(pidof lspd) 2>/dev/null' >/dev/null 2>&1 || true
sleep 1
for f in modules_config.db modules_config.db-wal modules_config.db-shm; do
  adb_dev pull "/data/adb/lspd/config/$f" "$TMP/$f" >/dev/null 2>&1 || true
done
[ -f "$TMP/modules_config.db" ] || die "could not pull /data/adb/lspd/config/modules_config.db"

log "writing module + scope rows"
sqlite3 "$TMP/modules_config.db" <<SQL || die "sqlite update failed"
PRAGMA wal_checkpoint(TRUNCATE);
INSERT OR IGNORE INTO modules(module_pkg_name,apk_path,enabled,auto_include)
  VALUES('$MODULE_PKG','$APK',1,0);
UPDATE modules SET apk_path='$APK', enabled=1 WHERE module_pkg_name='$MODULE_PKG';
INSERT OR IGNORE INTO scope(mid,app_pkg_name,user_id)
  SELECT mid,'$TARGET',0 FROM modules WHERE module_pkg_name='$MODULE_PKG';
INSERT OR IGNORE INTO scope(mid,app_pkg_name,user_id)
  SELECT mid,'android',0 FROM modules WHERE module_pkg_name='$MODULE_PKG';
PRAGMA journal_mode=DELETE;
SQL
log "resulting config:"
sqlite3 -header "$TMP/modules_config.db" "select mid,module_pkg_name,enabled from modules; select mid,app_pkg_name,user_id from scope;" | sed 's/^/  /'

adb_dev push "$TMP/modules_config.db" /data/adb/lspd/config/modules_config.db >/dev/null
adb_dev shell 'rm -f /data/adb/lspd/config/modules_config.db-wal /data/adb/lspd/config/modules_config.db-shm; chown root:root /data/adb/lspd/config/modules_config.db; chmod 0600 /data/adb/lspd/config/modules_config.db' >/dev/null

echo
if [ "$DO_REBOOT" -eq 1 ]; then
  log "rebooting so lspd loads the new module/scope"
  adb_dev reboot
  echo "After boot: deploy/verify.sh   (expect GNP: hooks installed in logcat -s GrabNetPrinter)"
else
  warn "reboot required for the change to take effect: adb -s $SERIAL reboot"
fi
