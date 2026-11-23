# ZepPay

**UPI payments with no mobile data or Wi-Fi.** ZepPay pays any UPI QR, phone number or bank account through `*99#` on your SIM (and the UPI 123PAY call on Jio). Your UPI PIN is typed only on your bank's own screen. No accounts, no analytics.

| | Get it |
| --- | --- |
| **Android** | [Download the APK](https://github.com/mananbharti/zeppay/releases/latest/download/zeppay.apk) (Android 8+). Updates arrive inside the app from 2.0.0 |
| **iPhone** | [mananbharti.github.io/zeppay/ios](https://mananbharti.github.io/zeppay/ios/) → Safari → Share → Add to Home Screen |

> Real-SIM testing of the `*99#` automation, the PIN step and 123PAY is still pending. Your bank SMS is where to confirm a payment.

## What's in this repo

| Folder | What | Docs |
| --- | --- | --- |
| [`android-app/`](android-app) | Native Android app (Kotlin, Jetpack Compose) | [README](android-app/README.md) · [design contract](android-app/docs/DESIGN.md) |
| [`zeppay-pwa/`](zeppay-pwa) | iPhone web app (React, Vite, offline PWA) | [README](zeppay-pwa/README.md) |
| [`gh-pages-root/`](gh-pages-root) | Static extras published with the website (Zep Card profile page) | |
| [`backend/`](backend), [`supabase/`](supabase), `render.yaml` | Earlier OTP/Supabase backend, kept for reference. The current apps don't use it | |

The iPhone web app uses the "Blue Current" design (the Android app here is still on "Aurora Signal"). Both share one rule set: never capture the UPI PIN; show *Paid* only when the bank's reply (Android) or the user (iPhone) confirms it; never auto-retry an uncertain payment.

The earlier Flutter app was removed in favour of these two. It's in git history, and its old releases stay downloadable.

## Build and test

```bash
cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug
cd zeppay-pwa && npm ci && npm test && npm run build && npm run e2e
```

## Releases

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)):

- **Every push and PR:** Android unit tests and a debug build; web app tests and build.
- **Push to `main`:** deploys the web app to GitHub Pages (at `/zeppay/` and `/zeppay/ios/`).
- **Tag `vX.Y.Z`** (must equal `versionName` in `android-app/app/build.gradle.kts`): builds the APK signed with the release key from Actions secrets, then publishes `zeppay.apk`, `zeppay-vX.Y.Z.apk` and `update.json` (checksum, version, notes from `android-app/release-notes.json`) as the latest release. The app's updater reads that `update.json`.

To release: bump `versionCode` and `versionName`, update `release-notes.json`, merge to `main`, then push the tag.

**Signing:** the release key is never in this repo. CI uses the secrets `ZEPPAY_KEYSTORE_B64`, `ZEPPAY_STORE_PASSWORD`, `ZEPPAY_KEY_ALIAS` and `ZEPPAY_KEY_PASSWORD`. Every release must be signed with the same key, or it won't install over the existing app. Keep an offline backup of the keystore.

---

<sub>UPI and *99# are NPCI's. ZepPay is an independent project, not a bank or payment service.</sub>
