# Contributing

Thanks for helping. This is a small, focused LSPosed module; keep changes narrow and testable.

## Build and test (no Android Studio required)

```bash
tools/build.sh        # offline: aapt2 + javac + d8 + zipalign + apksigner
tools/verify.sh       # 39 artifact/component checks
tests/jvm/run.sh      # 46 offline parser/bridge tests
```

Requirements: JDK 17, Android SDK with `platforms/android-34` and `build-tools/35.0.0`.

## Ground rules

- The module must **never** crash or destabilise the hooked app: every hook/callback is guarded and
  configuration errors degrade to "no hooks".
- Keep the hooks at the `android.bluetooth.*` API level rather than hooking target-app internals;
  this is what keeps the module usable across app updates.
- Do not add network services, telemetry or secrets. Configuration data is LAN printer address and a
  cosmetic Bluetooth identity only.
- Do not include decompiled or proprietary code from third-party apps in issues, PRs or commits.
- New configuration keys need: a default in `Config`, a settings field in `SettingsStore`/the UI,
  documentation in `docs/configuration.md`, and (where platform-independent) a JVM test.

## Test on a device

`docs/validation.md` lists the on-device checks (there is also a standalone diagnostic APK,
`dist/gnp-diag.apk`). Please include the relevant `logcat -s GrabNetPrinter`/`GNP-DIAG` output in PRs
that change hook behaviour. Never publish personal device identifiers or customer data.

## Licence

By contributing you agree your changes are licensed under the MIT licence (see `LICENSE`).
