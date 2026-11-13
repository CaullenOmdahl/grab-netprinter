# Research notes

- `compat/artifacts.md` — pinned Zygisk/LSPosed builds, checksums and KernelSU compatibility notes
  used to bring up the reference device. These binaries are **not** redistributed; `vendor/fetch.sh`
  downloads them from their upstream releases.
- The target app's printer code paths were analysed by inspecting the app's bundled libraries
  (DantSu ESC/POS, `mex_bluetooth_printer`, `com.grab.iot.printer`) and observing its public
  Bluetooth API usage. No proprietary app code is included in this repository.
