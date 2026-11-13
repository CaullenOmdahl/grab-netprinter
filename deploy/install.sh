#!/usr/bin/env bash
# One-time installer for the POS. Knows the validated bring-up sequence for this device:
#
#   phase 1  stage ZygiskNext alone            -> reboot -> verify payload is live
#   phase 2  stage LSPosed + grab-netprinter,
#            install the module APK, publish config      -> reboot -> verify lspd
#   phase 3  register module + scope in LSPosed -> reboot -> test print
#
# The script auto-detects which phase you are in, so it is safe to run repeatedly:
# on a device with ZygiskNext live it continues with LSPosed/our module; on a fresh
# device it stages ZygiskNext and tells you to reboot and re-run.
#
# Why the phases: KernelSU 0.9.2 stages module installs in modules_update.img. On the
# reference Android-x86 image, staging ZygiskNext together with other modules (or in a batch after it)
# lost its payload on the next boot (only module.prop remained), which broke Zygisk and
# LSPosed. Installing ZygiskNext on its own, then everything else, was reliable.
#
#   deploy/install.sh [--serial host:port] [--reboot] [--all-at-once]
#                     [--zygisk-neo] [--framework-vector]
#                     [--printer-host IP] [--printer-port N]
set -uo pipefail
source "$(dirname "$0")/common.sh"

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
DO_REBOOT=0; ALL_AT_ONCE=0; USE_NEO=0; USE_VECTOR=0
PRINTER_HOST=""; PRINTER_PORT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --reboot) DO_REBOOT=1; shift ;;
    --all-at-once) ALL_AT_ONCE=1; shift ;;
    --zygisk-neo) USE_NEO=1; shift ;;
    --framework-vector) USE_VECTOR=1; shift ;;
    --printer-host) PRINTER_HOST="$2"; shift 2 ;;
    --printer-port) PRINTER_PORT="$2"; shift 2 ;;
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) die "unknown arg: $1" ;;
  esac
done

require_adb
[ -f "$ROOT_DIR/dist/GrabNetPrinter.apk" ] || die "dist/GrabNetPrinter.apk missing — run tools/build.sh first"
MODULE_ZIP=$(ls "$ROOT_DIR"/dist/grab-netprinter-*.zip 2>/dev/null | head -1)
[ -n "$MODULE_ZIP" ] || die "module zip missing in dist/ — run tools/build.sh first"

pick_zip() {
  local hit
  hit=$(ls $1 2>/dev/null | head -1)
  [ -n "$hit" ] || hit=$(ls $2 2>/dev/null | head -1)
  printf '%s' "$hit"
}
ZYGISK_ZIP=$(pick_zip "$ROOT_DIR/vendor/zygisknext-*.zip" "$ROOT_DIR/vendor/neozygisk-*.zip")
[ "$USE_NEO" -eq 1 ] && ZYGISK_ZIP=$(pick_zip "$ROOT_DIR/vendor/neozygisk-*.zip" "$ROOT_DIR/vendor/zygisknext-*.zip")
FRAMEWORK_ZIP=$(pick_zip "$ROOT_DIR/vendor/lsposed-*.zip" "$ROOT_DIR/vendor/vector-*.zip")
[ "$USE_VECTOR" -eq 1 ] && FRAMEWORK_ZIP=$(pick_zip "$ROOT_DIR/vendor/vector-*.zip" "$ROOT_DIR/vendor/lsposed-*.zip")
[ -f "$ZYGISK_ZIP" ] || die "no Zygisk provider zip in vendor/ — run vendor/fetch.sh (docs/compat.md)"
[ -f "$FRAMEWORK_ZIP" ] || die "no Xposed framework zip in vendor/ — run vendor/fetch.sh (docs/compat.md)"

log "connecting to $SERIAL"
connect_device
root_adb
check_device
log "zygisk    : $(basename "$ZYGISK_ZIP")"
log "framework : $(basename "$FRAMEWORK_ZIP")"
log "module    : $(basename "$MODULE_ZIP")"

# Optional: bake a custom printer endpoint into the module's config before staging,
# so the boot-time copy from the module does not overwrite it.
if [ -n "$PRINTER_HOST" ]; then
  TMPZIP=/tmp/gnp-module-custom.zip
  rm -rf /tmp/gnp-module-staging && mkdir -p /tmp/gnp-module-staging
  unzip -q "$MODULE_ZIP" -d /tmp/gnp-module-staging
  sed -i.bak "s/^printer_host=.*/printer_host=$PRINTER_HOST/" /tmp/gnp-module-staging/config.prop
  [ -n "$PRINTER_PORT" ] && sed -i.bak "s/^printer_port=.*/printer_port=$PRINTER_PORT/" /tmp/gnp-module-staging/config.prop
  rm -f /tmp/gnp-module-staging/config.prop.bak
  ( cd /tmp/gnp-module-staging && zip -q -r "$TMPZIP" . )
  MODULE_ZIP="$TMPZIP"
  log "baked printer endpoint into module config: $PRINTER_HOST${PRINTER_PORT:+:$PRINTER_PORT}"
fi

ZYGISK_LIVE=0
adb_dev shell 'test -x /data/adb/modules/zygisksu/bin/zygiskd' >/dev/null 2>&1 && ZYGISK_LIVE=1
FRAMEWORK_LIVE=0
adb_dev shell 'pidof lspd' >/dev/null 2>&1 && FRAMEWORK_LIVE=1

# ---------------------------------------------------------------- phase 1 ---
if [ "$ZYGISK_LIVE" -eq 0 ] && [ "$ALL_AT_ONCE" -eq 0 ]; then
  log "phase 1: ZygiskNext is not live yet — staging it ALONE (validated sequence)"
  install_module_zip "$ZYGISK_ZIP" || die "could not stage ZygiskNext"
  echo
  cat <<EOS
Now reboot, then run this script again:

    adb -s $SERIAL reboot
    deploy/install.sh

After the reboot this script sees ZygiskNext live and stages LSPosed + grab-netprinter,
installs the module APK, then tells you to reboot again.
EOS
  [ "$DO_REBOOT" -eq 1 ] && { log "rebooting"; adb_dev reboot; }
  exit 0
fi
if [ "$ZYGISK_LIVE" -eq 1 ]; then
  log "ZygiskNext payload is live"
else
  warn "--all-at-once requested: staging ZygiskNext together with the rest"
  install_module_zip "$ZYGISK_ZIP" || warn "ZygiskNext staging failed"
fi

# ---------------------------------------------------------------- phase 2 ---
if [ "$FRAMEWORK_LIVE" -eq 0 ]; then
  log "phase 2: staging LSPosed/Vector"
  install_module_zip "$FRAMEWORK_ZIP" || die "could not stage the Xposed framework"
else
  log "LSPosed daemon already running; skipping framework staging"
fi

log "staging grab-netprinter module"
install_module_zip "$MODULE_ZIP" || die "could not stage the grab-netprinter module"

log "installing GrabNetPrinter LSPosed module APK"
APK_OUT=$(adb_dev install -r -g "$ROOT_DIR/dist/GrabNetPrinter.apk" || true)
echo "$APK_OUT" | tail -2
echo "$APK_OUT" | grep -q "Success" || warn "APK install may have failed; check: adb shell pm list packages | grep grabnetprinter"

if [ "$STANDALONE_MANAGER" = "1" ] 2>/dev/null; then :; fi
log "manager: LSPosed v1.11.0 uses its parasitic manager (no separate APK needed)"
log "         open it with: adb shell sh /data/adb/modules/zygisk_lsposed/action.sh"
log "         (if it does not open on this Android-x86 build, deploy/enable-scope.sh does the same job)"

log "publishing printer config to /data/local/tmp/grab-netprinter.conf"
SRC_CONF="$ROOT_DIR/module/config.prop"
[ -n "$PRINTER_HOST" ] && [ -f /tmp/gnp-module-staging/config.prop ] && SRC_CONF=/tmp/gnp-module-staging/config.prop
adb_dev push "$SRC_CONF" /data/local/tmp/grab-netprinter.conf >/dev/null
adb_dev shell 'chmod 0644 /data/local/tmp/grab-netprinter.conf; touch /data/local/tmp/grab-netprinter.log; chmod 0666 /data/local/tmp/grab-netprinter.log'

log "granting Bluetooth runtime permissions to com.grab.merchant (best effort)"
for perm in android.permission.BLUETOOTH_CONNECT android.permission.BLUETOOTH_SCAN; do
  adb_dev shell "pm grant com.grab.merchant $perm" >/dev/null 2>&1 || true
done

echo
log "KernelSU modules currently LIVE (pre-reboot):"
module_ids | sed 's/^/  /'
echo "  NOTE: KernelSU 0.9.2 stages new modules in /data/adb/ksu/modules_update.img;"
echo "        they appear in /data/adb/modules (and become active) only after the reboot."
echo
cat <<EOS
Next steps:

  1. Reboot (activates LSPosed + our module; first boot with both is a little slower):
         adb -s $SERIAL reboot

  2. Verify the framework:
         deploy/verify.sh
     Expect: ps -A | grep lspd and the "LSPosed loaded" notification on the POS.

  3. Register the module + scope (the reliable way if the manager UI does not open):
         deploy/enable-scope.sh
     ...which reboots. If the LSPosed manager DOES open, use it instead:
     Modules -> GrabNetPrinter -> enable + tick scope com.grab.merchant.

  4. After the final reboot:
         deploy/verify.sh
     and in GrabMerchant: Printer settings -> the "LAN Printer" device is already paired
     -> Test print. (Validated: the test receipt is forwarded over TCP and prints.)

Config: /data/adb/modules/grab-netprinter/config.prop; press the module Action button in
KernelSU Manager (or reboot) to re-publish after edits.
Rollback: deploy/rollback.sh [--all]
EOS

if [ "$DO_REBOOT" -eq 1 ]; then
  warn "rebooting in 10s — interrupt (Ctrl-C) if you are not ready"
  sleep 10
  log "rebooting POS"
  adb_dev reboot
fi
