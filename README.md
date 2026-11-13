# GrabNetPrinter

[![build](https://github.com/CaullenOmdahl/grab-netprinter/actions/workflows/build.yml/badge.svg)](https://github.com/CaullenOmdahl/grab-netprinter/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/CaullenOmdahl/grab-netprinter)](https://github.com/CaullenOmdahl/grab-netprinter/releases)
[![license](https://img.shields.io/github/license/CaullenOmdahl/grab-netprinter)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.1%2B%20(API%2027)-3DDC84?logo=android&logoColor=white)](#compatibility)
[![framework](https://img.shields.io/badge/framework-LSPosed-8A2BE2)](#prerequisites)

An LSPosed (Xposed) module that makes a **LAN ESC/POS receipt printer** appear inside a POS/merchant
app as an **already-paired Bluetooth SPP printer**, then forwards the app's ESC/POS bytes to the
printer over TCP.

Useful when a POS app only prints over Bluetooth but you have a networked thermal printer.

## Contents

- [Overview](#overview)
- [Features](#features)
- [How it works](#how-it-works)
- [Compatibility](#compatibility)
- [Installation](#installation)
- [Configuration](#configuration)
- [Security and privacy](#security-and-privacy)
- [Troubleshooting](#troubleshooting)
- [Limitations](#limitations)
- [Building and testing](#building-and-testing)
- [Project layout](#project-layout)
- [Contributing](#contributing)
- [License](#license)
- [Third-party components](#third-party-components)
- [Acknowledgements](#acknowledgements)
- [Disclaimer](#disclaimer)

## Overview

Bluetooth-only printing is a common limitation of restaurant and POS apps. This module fakes the
Bluetooth printer **inside the target app's process** — no real Bluetooth stack, kernel driver or
hardware emulation — and redirects the print stream to your network printer. The target app keeps
using its normal "Bluetooth printer" flow; it simply ends up talking to a TCP socket.

## Features

- Fake bonded Bluetooth printer inside the target app (printer-class device, SPP UUID, bonded state).
- Transparent ESC/POS forwarding to a LAN printer (`host:port`, typically `9100`).
- Optional Bluetooth discovery injection so the printer also appears in the app's scan screen.
- Hooks live at the `android.bluetooth.*` API layer, not inside the target app, so app updates keep
  working and the module is reusable across POS apps.
- In-app settings screen: printer, fake Bluetooth identity, target packages, discovery/debug toggles,
  TCP **Test connection**, **Restart target app** and **Open LSPosed** shortcuts.
- Configuration works without root prompts: the settings screen stores a pending config that the
  optional support module applies at boot; grant root for immediate application.
- Optional KernelSU/Magisk support module for managed/headless deployments.
- Standalone diagnostics APK and offline test suite for the platform-independent code.
- No telemetry, no background services, no bundled third-party binaries.

## How it works

```
 target app (e.g. a Flutter/Kotlin POS app using the DantSu ESC/POS libraries)
   ├─ BluetoothAdapter.getBondedDevices()   ──► fake bonded "LAN Printer"
   ├─ device.createRfcommSocketToServiceRecord(uuid)
   │      └─ BluetoothSocket.connect()      ──► TCP connect to your printer
   └─ socket.getOutputStream().write(bytes) ──► raw ESC/POS over TCP ──► printer
```

The module hooks `android.bluetooth.*` inside the scoped target app only:

- `BluetoothAdapter.isEnabled()/getBondedDevices()/startDiscovery()` report a usable adapter and a
  fabricated printer device (Bluetooth class `0x0680`, Serial Port Profile UUID);
- `BluetoothDevice` getters answer for that fake device (name, address, bond state, UUIDs);
- `BluetoothSocket` methods are intercepted for fake sockets; the output stream is bridged to TCP;
- registered `BroadcastReceiver`s are called directly for discovery (spoofed system broadcasts are
  blocked by Android, so this is deliberately not a broadcast-spoofing design).

See [docs/architecture.md](docs/architecture.md) for details.

## Compatibility

| | |
|---|---|
| Android | 8.1+ (API 27+); tested on Android 14 (x86_64) |
| Root | Magisk, KernelSU (+ ZygiskNext/NeoZygisk) or APatch |
| Hook framework | LSPosed (Zygisk variant); legacy Xposed API |
| Target apps | Classic Bluetooth SPP printing. Primary/tested target: GrabMerchant 4.196.0; other POS apps can be added via the `targets` setting |
| Printers | Raw ESC/POS over TCP (usually port 9100). IPP/LPD-only office printers are not supported |
| Not supported | BLE-GATT printing, native (NDK) print pipelines, iOS |

Pinned Zygisk/LSPosed builds and known-good combinations are documented in
[docs/compat.md](docs/compat.md). Those binaries are not redistributed with this project.

## Installation

### Prerequisites

1. A rooted device with **LSPosed** installed and working (Module repository: LSPosed / LSPosed fork).
2. A network receipt printer that accepts raw ESC/POS on TCP (check with the printer manual or a
   TCP tool; usually port `9100`).
3. The POS app you want to print from.

### 1. Install the module

Download the latest `GrabNetPrinter.apk` from
[Releases](https://github.com/CaullenOmdahl/grab-netprinter/releases) (or build it, see
[Building and testing](#building-and-testing)), then:

```bash
adb install -r GrabNetPrinter.apk
```

### 2. Enable it in LSPosed

In **LSPosed Manager → Modules**: enable **GrabNetPrinter**, then set its **scope** to the POS app
package (default `com.grab.merchant`). An empty scope is the most common cause of "nothing happens".
Force-stop the target app afterwards.

### 3. Configure

Open the **GrabNetPrinter** settings app from the launcher:

- **Printer host / port** — your printer, e.g. `192.168.1.50` / `9100`;
- optionally the fake Bluetooth name/address and target package(s);
- press **Save** (a root prompt applies it immediately; without root, the optional support module
  applies it on the next boot), then **Restart target app**.

All keys and delivery channels: [docs/configuration.md](docs/configuration.md).

### 4. Pair and print

In the POS app open its Bluetooth printer settings. **`LAN Printer`** should be listed as a paired
printer; select it and print a test receipt. The module log shows the TCP connection and byte count.

### Optional KernelSU/Magisk support module

`grab-netprinter-<version>.zip` from the release is an optional Magisk-format support module. It
seeds the config at boot from `/data/adb/modules/grab-netprinter/config.prop` and applies pending
configs saved by the settings screen. Most users do not need it. Deployment details:
[docs/deploy.md](docs/deploy.md).

## Configuration

The settings app is the normal way to configure the module. Configuration is delivered to the
target app through a per-target config file (written by root into the target app's files directory),
with a read-only ContentProvider and the legacy `/data/local/tmp/grab-netprinter.conf` file as
fallbacks. See [docs/configuration.md](docs/configuration.md) for every key, precedence and the
rootless/pending path.

## Security and privacy

- Hooks are installed **only** in the process of packages you configure; the module never hooks the
  system, the Bluetooth service, or unrelated apps.
- All configuration stays on-device. The only network connection the module makes is the TCP
  connection to the printer you configure.
- No telemetry, analytics, accounts, or background services.
- The exported settings provider is read-only and exposes only the keys you saved (LAN printer
  address and a cosmetic Bluetooth identity — no secrets).
- Root is used only for convenience actions: writing the config into the target app's directory and
  restarting the target app. These are optional; reads never need root.
- Keep LSPosed scope minimal (the POS app only) and review the source before use.

## Troubleshooting

| Symptom | Check |
|---|---|
| Printer not listed | LSPosed scope ticked, module enabled, target app force-stopped/restarted; check settings status line and `adb logcat -s LSPosed-Bridge` |
| Connect fails / no output | **Test connection** in settings; printer IP/port; POS network reachability; module log `TCP connected`/`close` lines |
| Garbage/partial receipts | Printer must accept raw ESC/POS on that port; wrong port or IPP-only printer is the usual cause |
| Settings not applied | Press Save, then Restart target app; without root, reboot with the optional support module installed |
| After an app update | Hooks are framework-level, but re-check the target app's printer flow; see troubleshooting doc |

More: [docs/troubleshooting.md](docs/troubleshooting.md). The optional `gnp-diag.apk` from the
release validates device-side assumptions and can test printer reachability
(`--es printer_host <ip> --es printer_port 9100`).

## Limitations

- Only the classic Bluetooth SPP write path is emulated; BLE-GATT printing is not supported.
- Raw TCP ESC/POS is implemented. `lpd`/`ipp` transports are reserved but not implemented; the
  protocol setting currently behaves as `raw`.
- Apps that implement printing in native code (NDK) bypass Java-level hooks.
- The target app must be in LSPosed scope and restarted after configuration changes.
- Faking a Bluetooth device assigns a Bluetooth address/name chosen in settings; this is visible in
  the target app only.

## Building and testing

The build is offline and Gradle-free (aapt2 + javac + d8 + zipalign + apksigner). Requirements: JDK 17,
Android SDK with `platforms/android-34` and `build-tools/35.0.0`.

```bash
tools/build.sh      # dist/GrabNetPrinter.apk, dist/grab-netprinter-<ver>.zip, dist/gnp-diag.apk
tools/verify.sh     # 39 artifact/component/signature checks
tests/jvm/run.sh    # 46 offline config/TCP-bridge tests
```

CI runs the same three commands on every push (see the workflow badge above). The Xposed API 82 jar
is vendored in `app/libs/` (Apache-2.0, see [THIRD_PARTY.md](THIRD_PARTY.md)); Gradle files are for
IDE convenience only.

## Project layout

```
app/       LSPosed module APK: settings UI, hooks, config delivery
module/    optional KernelSU/Magisk support module (config seeding + log)
diag/      standalone device-diagnostics APK
tools/     offline build/verify scripts + diagnostics notes
deploy/    optional device bring-up/verify/scope scripts
docs/      configuration, architecture, deployment, compatibility, troubleshooting, validation
tests/     offline JVM tests for config/TCP bridge
vendor/    fetch script for pinned root prerequisites (binaries not committed)
```

## Contributing

Issues and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md); the short
version: keep hooks defensive, keep them at the framework API level, add docs/tests for new config
keys, and never include decompiled third-party code or personal device data.

## License

[MIT](LICENSE). By contributing you agree to license your changes under the same terms.

## Third-party components

Xposed API 82 is vendored (Apache-2.0). LSPosed, ZygiskNext and alternatives are downloaded and
installed by the user and are **not** redistributed here (ZygiskNext's licence forbids
redistribution). Full details: [THIRD_PARTY.md](THIRD_PARTY.md).

## Acknowledgements

- The Xposed/LSPosed and Zygisk projects for making in-process instrumentation practical.
- DantSu's ESC/POS Android library, whose Bluetooth connection flow this module was designed around.
- Everyone who tests the module on other POS apps and printers and reports results.

## Disclaimer

Not affiliated with, endorsed by, or supported by Grab or any POS vendor. Use only on devices and
accounts you own or administer, and respect the terms of service of the apps you modify. Provided
as-is, without warranty.
