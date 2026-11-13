# Configuration

Configuration is delivered to the hooked target app in this precedence order (later wins):

1. compiled defaults;
2. legacy file `/data/local/tmp/grab-netprinter.conf` (optional, written by the settings app or the
   KernelSU/Magisk module);
3. **read-only ContentProvider** `content://dev.blacksheep.grabnetprinter.config/config`
   (best effort; some Android 11+ target apps cannot see other packages' providers);
4. **per-target-app file** `<target app>/files/gnp.conf` — the primary channel. The settings app
   writes it with root; the KernelSU/Magisk module can seed it at boot. The hooked app reads a file
   it owns, so this works with SELinux enforcing and without package-visibility exceptions.

The effective source counts are logged at process start (`config sources: target-file=… provider=…
legacy-file=…`).

## Settings app

| Field | Key | Default | Notes |
|---|---|---|---|
| Enabled | `enabled` | `1` | `0` installs no hooks at all |
| Printer host | `printer_host` | *(empty)* | IP or hostname; **required** when enabled |
| Printer port | `printer_port` | `9100` | raw ESC/POS JetDirect port |
| Bluetooth name | `fake_name` | `LAN Printer` | what the target app displays |
| Bluetooth address | `fake_mac` | `02:00:00:00:00:01` | any valid unicast MAC; use `02:…` |
| Answer discovery scans | `discovery` | `1` | inject fake ACTION_FOUND/UUID/FINISHED |
| Verbose logging | `debug` | `0` | hex-dumps the first 4 KiB of print bytes |
| Target packages | `targets` | `com.grab.merchant` | comma separated; must match LSPosed scope |
| Protocol | `protocol` | `raw` | `lpd`/`ipp` reserved (currently falls back to raw) |
| Log path | `log` | `/data/local/tmp/grab-netprinter.log` | empty disables file logging |

Buttons: **Save** (persists prefs, records a pending config, then writes the per-target config file
and legacy file via one root command), **Test connection** (TCP connect), **Restart target app**
(root `am force-stop`, reopens `com.grab.merchant` when that is the target), **Open LSPosed**, and an
optional **Write legacy config file (root)**.

If root is not granted to the settings app, **Save** still records the configuration as a pending
file; the optional KernelSU/Magisk support module applies it to the target app(s) at the next boot
(so a reboot, rather than a root prompt, is the fallback).

## Manual / file configuration

For managed or headless deployments, edit the optional KernelSU/Magisk module's
`/data/adb/modules/grab-netprinter/config.prop`; its scripts seed the legacy file and the
per-target file at boot (existing per-target files are never overwritten). Or write
`/data/local/tmp/grab-netprinter.conf` yourself:

```properties
enabled=1
targets=com.example.pos
printer_host=192.168.1.50
printer_port=9100
protocol=raw
fake_mac=02:00:00:00:00:01
fake_name=LAN Printer
discovery=1
discovery_broadcast=1
debug=0
log=/data/local/tmp/grab-netprinter.log
```

Restart the target app afterwards (LSPosed injects at process start).

## Logs

- LSPosed log: `adb logcat -s LSPosed-Bridge` (or the manager's Logs tab).
- Module file log (if writable): `/data/local/tmp/grab-netprinter.log`.
- Startup line to grep for: `GNP: hooks installed targets=… printer=…:… fake=…`.
