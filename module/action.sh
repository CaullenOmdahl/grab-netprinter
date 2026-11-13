#!/system/bin/sh
# GrabNetPrinter KernelSU action button: re-publish config and restart GrabMerchant
# so new settings take effect without a full reboot.
MODDIR=${0%/*}
SRC="$MODDIR/config.prop"
DST=/data/local/tmp/grab-netprinter.conf
LOG=/data/local/tmp/grab-netprinter.log
PKG=com.grab.merchant
ACT=com.grab.mex.activity.MainActivity

# Never clobber a file maintained by the settings screen; only seed a missing one.
[ -f "$SRC" ] && [ ! -f "$DST" ] && cp -f "$SRC" "$DST" 2>/dev/null
chmod 0644 "$DST" 2>/dev/null
touch "$LOG" 2>/dev/null
chmod 0666 "$LOG" 2>/dev/null

am force-stop "$PKG" 2>/dev/null
sleep 1
am start --user 0 -n "$PKG/$ACT" >/dev/null 2>&1
echo "GrabNetPrinter: configuration re-applied, $PKG restarted." >&2
exit 0
