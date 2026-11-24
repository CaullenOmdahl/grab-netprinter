# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Documentation
- Rewrote the README to an open-source project standard (badges, TOC, compatibility, installation,
  security/privacy, troubleshooting, project layout) and added SECURITY.md, CODE_OF_CONDUCT.md,
  CHANGELOG.md, issue templates, a pull-request template and an .editorconfig.
- Defined the supported compatibility envelope (issue #1): README now separates the declared
  minimum (Android 8.1+) from what has been tested, marks other combinations as untested, lists the
  out-of-scope pipelines, links the compatibility matrix and validation procedure, and adds a
  "Requirements at a glance" section stating that a rooted device with Zygisk + LSPosed is mandatory.
- Scoped the "app updates keep working" claim to the Java classic-SPP (RFCOMM) flow and added an
  "After the target app updates" troubleshooting section.
- Removed remaining placeholder defaults from the optional deploy scripts.

## [1.1.1] - 2026-11-13

### Added
- Rootless configuration path: the settings app records a pending configuration and the optional
  KernelSU/Magisk support module applies it to the target app(s) at boot.

### Changed
- Support module seeds the per-target config file and imports pending configurations.
- Documentation: KernelSU 0.9.x staged-module-update quirk and recovery notes.

## [1.1.0] - 2026-11-13

### Added
- In-app settings screen (printer host/port, fake Bluetooth identity, targets, discovery/debug,
  test-connection, restart-target, open-LSPosed).
- Configuration delivery through a per-target config file, with a read-only ContentProvider and the
  legacy `/data/local/tmp` file as fallbacks.
- Public project files: CI workflow, artifact verifier, offline tests, diagnostics APK.

### Changed
- Hook installation is deferred until the target app has a `Context` so configuration can be read.
- Module APK now has a launcher activity and a configuration provider.

## [1.0.0]

### Added
- Initial LSPosed module: fake bonded Bluetooth SPP printer, TCP ESC/POS bridge, discovery
  injection, optional support module and diagnostics.
- Validated end-to-end on an Android-x86 POS with a network ESC/POS printer.

[Unreleased]: https://github.com/CaullenOmdahl/grab-netprinter/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/CaullenOmdahl/grab-netprinter/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/CaullenOmdahl/grab-netprinter/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/CaullenOmdahl/grab-netprinter/releases/tag/v1.0.0
