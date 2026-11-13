#!/system/bin/sh
# Magisk installer hook. KernelSU ignores this file; it installs Magisk-format
# modules directly. Kept so the zip also installs cleanly under Magisk.
if command -v ui_print >/dev/null 2>&1; then
  ui_print "- GrabNetPrinter installed (KernelSU/Magisk module format)"
  ui_print "- Printer config: \$MODPATH/config.prop (copied to /data/local/tmp at boot)"
  ui_print "- Requires the GrabNetPrinter LSPosed module APK + LSPosed + Zygisk"
  ui_print "- Edit config, then reboot or press the module Action button"
fi
exit 0
