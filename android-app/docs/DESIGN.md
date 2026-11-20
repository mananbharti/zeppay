# ZepPay design contract: "Aurora Signal"

Shared by the Android app and the iPhone web app (`mananbharti/zeppay`, `zeppay-pwa/`).
Target image: [design/aurora-target-home.png](design/aurora-target-home.png) (generated in Figma, approved, then built and compared on device).

**Purpose:** get someone paid fast, offline, with no doubt about what happened. Audience includes street vendors and elderly users, so one clear action per screen, big targets (≥ 58dp), plain words, body text ≥ 16sp, high contrast. Night is the default; Daylight is for bright sun.

**Grounding:** the user's dark-fintech references set the *feel* (premium night base, one glowing hero object, big numerals, pill buttons). Colour, the hero object and type were deliberately made ZepPay's own: no orange/red glow, no glossy square tile, no serif, no coins or offers.

## Colour: one job each

| Token | Night | Daylight | Job |
| --- | --- | --- | --- |
| bg | `#070B14` | `#F5F7FC` | Page |
| card / tile | `#0F1626` | `#FFFFFF` + hairline | Grouped info / shortcuts and inputs |
| primary | mint `#3EF0B1`, ink text | ink `#0A1020`, mint text | **The one next action** |
| secondary | `#18233A` | `#E7ECF6` | Any other choice |
| aurora A / B | teal `#19E3C8` / violet `#6A4BFF` | slightly deeper | Only the signal dial and "working" |
| bubbles | mint, violet, cyan, amber | same | Tile icons, so tiles are told apart without reading |
| paid / failed / unsure | mint / coral / amber | darker shades | Status text and tinted status pills. Always with a word, never colour alone |
| bank | sky `#9CC9FF` dashed | `#1B6FB5` dashed | The bank's counter: where ZepPay steps aside |

## Shape: one job each

- **Pill** = an action (mint = next step, navy = other choice). Nothing else is a solid pill.
- **Small tinted pill without an edge** = a status (Paid, Not confirmed, Done). Never pressable.
- **Card, 24dp** = grouped information. **Tile, 24dp + icon bubble** = a shortcut. **Input, 18dp + hairline.**
- **Signal dial** (disc + 3 gradient rings) = the one hero object: Scan to pay on Home, the payment in progress.
- **Small circle** = a person.
- **Dashed sky frame labelled "BANK'S COUNTER"** = only where the bank or the Phone app owns the screen (PIN, balance, bank-link steps). Never used for ordinary notes.

## Type

- **Bricolage Grotesque** (OFL): headings, amounts, tile labels. Normal width, slightly quirky.
- **Atkinson Hyperlegible Next** (OFL): every sentence. Made for low-vision readers.

## Motion: one job each

- **Press:** scale to 96%. Your tap registered.
- **Forward / back:** the next screen rises in (340ms, emphasized ease); back sinks it away.
- **Rings ripple outward:** only while the carrier is working. Never decoration.
- **Step aside:** the Android overlay shrinks from a banner into the dashed bank's-counter chip at PIN time.
- **Outcome:** Paid pops with one ring; Failed shakes once; Not confirmed stays still (no celebration for uncertainty).
- System "remove animations" / `prefers-reduced-motion` makes every motion instant.

## Required states (all implemented)

Onboarding (incl. upgrade from v1), payee entry with validation, amount (fixed for QR amounts), check-and-pay (helper off, on a call, Jio/UPI-ID block, overlay off), live session (opening, filling in, PIN handoff, bank asks something unknown), outcomes (Paid, Failed, Stopped, Not confirmed with the bank's raw text and "don't pay again yet"), manual route "Did it go through?", receipt, balance/history info, splits (none, owe, owed, settled), spends (ZepPay-recorded only, unconfirmed shown apart), helper setup incl. Android 13+ restricted settings, bank setup one step per screen, carrier-menu check, updates (check, available, downloading, verifying, Android confirmation, permission needed, blocked during payment, failed), settings, help, licences.

## Finish condition

Rendered on an Android 15 emulator (night + daylight) and in Chromium at 390×844 (night + daylight), compared with the target image. Not yet checked: a real phone, small screens under 360dp wide, Android font scale 200%, and a real iPhone.
