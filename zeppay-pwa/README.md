# ZepPay web app

Offline UPI payments over `*99#`, as an installable web app (PWA). It lines up the payment and hands it to your bank: the `*99#` service on your SIM (no internet needed). The payment itself is always completed by your bank.

Live (after merge to `main`): https://mananbharti.github.io/zeppay/ios/
Preview build: https://mananbharti.github.io/zeppay-pwa/

<p align="center">
  <img src="docs/screenshots/02-home.png" width="190" />
  <img src="docs/screenshots/03-pay.png" width="190" />
  <img src="docs/screenshots/08-dialer.png" width="190" />
  <img src="docs/screenshots/06-receipt.png" width="190" />
</p>
<p align="center">
  <img src="docs/screenshots/01b-banklink.png" width="190" />
  <img src="docs/screenshots/11-help.png" width="190" />
  <img src="docs/screenshots/07-history.png" width="190" />
  <img src="docs/screenshots/14-home-dark.png" width="190" />
</p>

## Features

- **Bank-link guide**: the one-time `*99#` setup, in onboarding, Help and Settings
- **Scan QR to pay**: live camera scan, upload a QR image, or paste a UPI ID or `upi://` link
- **Pay to a UPI ID or a mobile number**
- **Pay over `*99#`** (the only payment route): copies the UPI ID or number, then opens your dialer with `*99*1*3#` (UPI ID) or `*99*1*1#` (mobile number). No data needed
- **Check balance**: opens `*99*3#`
- **History**: every payment you start, saved on the device. When you return from the dialer, ZepPay asks if it went through
- **Help and FAQ**, install to home screen, Auto / Light / Dark theme, carrier picker (Jio doesn't support `*99#`, so offline payments are switched off for it)
- Works offline once loaded

## What ZepPay can and can't do

A web page can't move money or read your bank's reply. So ZepPay never claims a payment succeeded: the status in History is what you confirm. Your UPI PIN is entered in the dialer only. ZepPay never sees or stores it. Everything is kept in `localStorage` on your device. No analytics, no accounts, no servers.

## Run it

```bash
npm install
npm run dev
npm run build && npm run preview
```

## Tests

```bash
npm test                        # unit tests: UPI parsing, validation, USSD codes
npm run build && npm run e2e    # 52 browser checks in headless Chrome
```

The browser suite drives the whole app: onboarding, UPI ID and mobile entry, the exact `upi://pay` link and `tel:` codes that get opened, clipboard copy, outcome prompt on return, history, QR decode from an image, help, theme, Jio blocking, layout overflow, service worker and offline load. Set `CHROME=/path/to/chrome` if Chrome isn't at `/usr/bin/google-chrome`, `SHOTS=1` to regenerate screenshots, `TARGET_URL=...` to test a deployed copy.

## Stack

React, Vite, jsQR, hand-written service worker, Inter. No UI kit.

UPI is a trademark of NPCI. Independent project, not a registered payment service.

## Offline installation and scanner verification

The production build generates a versioned service worker manifest covering HTML,
JavaScript (including the QR decoder), CSS, fonts, logo, icons and manifest. Wait
for "Saved for offline use" before disconnecting. Camera decoding, image upload,
payee entry, history and settings are local; no QR service is contacted.

Offline storage is not permanent: browser/OS eviction, clearing site data or a
private browsing session can remove it. Save to Home Screen where available.
Updates and reinstalling need internet. Bank payments still require a supported
SIM and cellular signal; offline does not mean without cellular service.

Dialing uses the menu, not payment shortcuts: iOS 27 uses `*99##`; iOS 26 and
older, Android and unknown versions use `*99#`. This follows the requested
compatibility rule, not a physical-device verification. Browsers that hide the
actual OS version may fall back to one hash. Choose 1 then 3 for UPI ID, 1 then 1
for mobile, or 3 for balance. The bank menu remains authoritative.

Regression commands:

```sh
npm test
npm run build
npm run e2e
OFFLINE_TEST=1 node e2e/camscan.mjs
INVERT_QR=1 OFFLINE_TEST=1 node e2e/camscan.mjs
```

Camera tests use a synthetic video feed with a test UPI payload, with the network
disabled before reloading and opening the scanner. Real iPhone camera focus,
permissions, version reporting and dialer acceptance still need device testing.
