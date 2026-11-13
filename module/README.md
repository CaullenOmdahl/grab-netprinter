# GrabNetPrinter support module (KernelSU / Magisk) - optional

The normal configuration path is the **GrabNetPrinter settings app**; this module is only needed
for managed/headless deployments.

What it does at boot:

- copies `config.prop` to `/data/local/tmp/grab-netprinter.conf` **if that file does not exist**;
- copies it to `<target app>/files/gnp.conf` **if that file does not exist** (the primary config
  channel the hook reads);
- creates a world-writable log file.

Edit `/data/adb/modules/grab-netprinter/config.prop`, then reboot or press the module **Action**
button in KernelSU/Magisk Manager.

Requirements: root with a Magisk-format module loader (KernelSU, Magisk, APatch), plus LSPosed and the
GrabNetPrinter APK. Uninstalling the module removes the published config/log (see `uninstall.sh`).
