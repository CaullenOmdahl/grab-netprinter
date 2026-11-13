#!/system/bin/sh
# Remove the published config/log so no stale state survives an uninstall.
rm -f /data/local/tmp/grab-netprinter.conf
rm -f /data/local/tmp/grab-netprinter.log
rm -f /data/local/tmp/grab-netprinter.log.tmp
exit 0
