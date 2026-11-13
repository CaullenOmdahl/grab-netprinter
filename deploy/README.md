# Deploy scripts

Run from the repo root. The POS defaults to `192.168.1.100`; override with
`--serial host:port` or `SERIAL=...`.

| Script | Purpose | Changes device? |
|---|---|---|
| `install.sh` | stage ZygiskNext v1.5.3 + LSPosed v1.11.0 + `grab-netprinter` module, install the module APK, publish config, grant BT permissions; `--zygisk-neo` / `--framework-vector` / `--printer-host` / `--printer-port` / `--reboot` options | yes (root modules, APK) |
| `verify.sh` | post-reboot check: modules, config, printer reachability, hook startup logs | launches GrabMerchant |
| `rollback.sh` | uninstall APK / disable or purge our module / optionally disable all root modules | yes |
| `printer-test.sh` | send an ESC/POS test page straight to the printer | **prints paper** |

`install.sh` is phased: on a fresh device it stages **ZygiskNext alone** and asks you to reboot
(a KernelSU 0.9.2 staging quirk on this image loses its payload if batched with other modules);
re-run it after the reboot and it stages LSPosed + the grab-netprinter module + APK.
Then `enable-scope.sh` (or the LSPosed manager) registers the module + scope and reboots.
KernelSU stages modules: nothing is live until the reboot. After rebooting, open the LSPosed manager — it is parasitic,
no separate APK needed:

```bash
adb shell sh /data/adb/modules/zygisk_lsposed/action.sh   # or tap the "LSPosed loaded" notification
```

**Modules → GrabNetPrinter → enable**, tap the row, tick the scope `com.grab.merchant`, then run
`verify.sh`. If the manager window does not stay open, `deploy/enable-scope.sh` does the same
thing by editing LSPosed's SQLite config (validated). The first boot with Zygisk is the risky step: read [../docs/compat.md](../docs/compat.md)
(§0 safety rules, §9 rollback) and prefer the two-reboot staged bring-up on a production till.

For a stuck/bootlooping POS see [../docs/troubleshooting.md](../docs/troubleshooting.md).
