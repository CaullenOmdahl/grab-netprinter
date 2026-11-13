# Third-party components

## Bundled in this repository

| Component | Version | Licence | Where |
|---|---|---|---|
| Xposed API (`de.robv.android.xposed:api`) | 82 | Apache License 2.0 | `app/libs/xposed-api-82.jar` (compile-only; not packaged into the APK). Re-fetch with `tools/fetch-api.sh`. |

## Downloaded separately, not redistributed

| Component | Notes |
|---|---|
| **LSPosed** (JingMatrix fork) | GPL-3.0. Installed by the user; see `docs/compat.md` for pinned versions/checksums. |
| **ZygiskNext** | Its README reserves all rights and forbids redistribution. Never committed to this repo; fetched from upstream releases by the user (`vendor/fetch.sh`). |
| **Safetynet/Vector/NeoZygisk alternatives** | Same approach: fetched, not bundled. GPL-3.0 where applicable. |

## Referenced, not bundled

- DantSu ESC/POS Thermal Printer Android library (target apps bundle their own copy).
- The Xposed framework API documentation and LSPosed module development docs.
