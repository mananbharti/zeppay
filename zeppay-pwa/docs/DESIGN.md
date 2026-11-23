# ZepPay design contract: "Blue Current"

iPhone web app (`zeppay-pwa/`), ported from the Android app's design contract. Replaces "Aurora Signal" (3.0.x).

**Purpose:** get someone paid fast, offline, with no doubt about what happened. Audience includes street vendors and elderly users, so: one clear action per screen, big targets (≥ 58dp), plain words, body text ≥ 16sp, high contrast. Night is the default; Daylight is for bright sun; Auto follows the phone.

**Grounding:** layout ideas (a hero band with one scan card, a row of round quick actions, list rows with a chevron, a floating tab pill with a separate scan button, an ID-style profile card) were studied from a layout reference. No art, icon, font, colour, text or file from it is used. Every icon is drawn in code (`ui/theme/Icons.kt`), the hero is drawn in code (`HeroBand`, `ScanCard` in `ui/kit/Kit.kt`), and the palette is ZepPay's website palette.

## Colour: one job each

All tokens live in `ui/theme/Zep.kt` (`ZepColors`, `Dark`, `Light`). Screens never use a hex value. Views drawn outside Compose (the overlay card) read the same night tokens through `ZepArgb`.

Website palette: `#080808` black, `#3898EC` blue, `#2476CC` deep blue, `#A5D4FF` light blue, `#4CD5FF` cyan.

| Token | Night | Daylight | Job |
| --- | --- | --- | --- |
| bg | `#080808` | `#F5F8FC` | Page |
| card / tile | `#121418` + hairline | `#FFFFFF` + hairline | Grouped info / list rows and inputs |
| raised | `#1A2230` | `#E3EEFA` | Avatars, surfaces on a card |
| line | `#242B36` | `#D5E1EE` | Hairlines, dividers |
| text / muted | `#F2F6FB` / `#9AA7B8` | `#080808` / `#4A5566` | Words |
| link | `#A5D4FF` | `#1B5FA8` | Text actions ("See all", "Copy UPI ID") |
| primary / onPrimary | `#3898EC` / `#080808` | `#2476CC` / `#FFFFFF` | **The one next action** |
| secondary / onSecondary | `#1A2230` / `#F2F6FB` | `#E3EEFA` / `#0B2A4A` | Any other choice |
| glowA / glowB | `#3898EC` / `#4CD5FF` | same | Only the hero band and the live-payment rings |
| iconBg / iconFg | `#16263A` / `#A5D4FF` | `#D9ECFF` / `#1B5FA8` | Round icon wells; the selected tab |
| paid / failed / unsure | `#5EE6A0` / `#FF7A86` / `#FFC44D` | `#0B7A4B` / `#C62F43` / `#8F5F00` | Status text and tinted pills. Always with a word, never colour alone |
| bank | `#4CD5FF` dashed | `#2476CC` dashed | The bank's counter: where ZepPay steps aside |
| camera / onCamera | `#080808` / `#F2F6FB` | same | The scanner (always dark) |
| qrPaper / qrInk | `#FFFFFF` / `#080808` | same | Behind and in a QR (scanners need dark on light) |

Contrast (WCAG): text 18.4:1 night / 18.8:1 day; muted 8.2 / 7.1; button labels 6.6 (black on `#3898EC`) / 4.6 (white on `#2476CC`); link 12.8 / 6.1; status colours ≥ 5.4 on their backgrounds. White on `#3898EC` would be only 3.1:1, which is why the night action pill uses black ink.

## Shape: one job each

- **Pill** = an action (blue = next step, quiet = other choice). Nothing else is a solid pill.
- **Small tinted pill without an edge** = a status (Paid, Not confirmed, Done). Never pressable.
- **Card, 24dp + hairline** = grouped information. **Row, 20dp + icon well + chevron** = a shortcut. **Input, 18dp + hairline.**
- **Round icon well (64dp) with its label under it** = a Home quick action.
- **Scan card** (32dp, cyan-to-blue gradient edge, big scan corners) = the one hero object on Home, on the hero band (blue wash + dot grid + cyan glow).
- **Rings** = the live payment.
- **Small circle** = a person (photo, initials, or a person icon before a name is set).
- **Dashed cyan frame labelled "BANK'S COUNTER"** = only where the bank or the Phone app owns the screen (PIN, balance, bank-link steps).
- **Dock** = a floating pill with four labelled tabs (current one in a light-blue well) plus a round blue Scan button: scanning is one tap from every tab.

## Type

- **Bricolage Grotesque** (OFL): headings, amounts, labels.
- **Atkinson Hyperlegible Next** (OFL): every sentence. Made for low-vision readers.

## Motion: one job each

- **Press:** scale to 96%.
- **Forward / back:** the next screen rises in (340ms, emphasized ease); back sinks it away.
- **Rings ripple outward:** only while the carrier is working.
- **Step aside:** the overlay shrinks into the dashed bank's-counter chip at PIN time.
- **Outcome:** Paid pops with one ring; Failed shakes once; Not confirmed stays still.
- System "remove animations" makes every motion instant.

## Receive and profile (2.2)

- **Profile:** name (required, ≤ 40), photo (system photo picker, copied into app-private storage, ≤ 512px), own UPI ID (optional, must look like `name@bank`). Store keys `profileName`, `profileVpa`, `profilePhoto` (new; no existing key renamed).
- **Receive:** a real QR of `upi://pay?pa=<UPI ID>&pn=<name>[&am=<amount>][&tn=<note>]`, made with ZXing, dark on white in both themes. Share (picture through the share sheet) and Save (system "save as", no storage permission). No UPI ID yet: Receive sends you to Profile first.
- **Honesty:** Receive never says money arrived. It says plainly that a QR confirms nothing and that only the bank (SMS, bank app, statement) confirms money received.

## Required states (all implemented)

Onboarding, payee entry with validation, amount (fixed for QR amounts), check-and-pay, live session (opening, filling in, PIN handoff, bank asks something unknown), outcomes (Paid, Failed, Stopped, Not confirmed with the bank's text and "don't pay again yet"), manual route "Did it go through?", receipt, balance/history info, splits, spends, helper setup incl. restricted settings, bank setup, carrier-menu check, updates, settings, help, licences, profile (empty, invalid, saved, with photo), receive (no UPI ID, any amount, fixed amount, invalid amount).

## Finish condition

Rendered on an Android 15 emulator in Night and Daylight (every screen), Receive QR decoded from a screenshot with ZXing and scanned back with ZepPay's own scanner, profile and photo kept across rotation and restart, the pay flow run end to end against the debug simulated carrier. Not yet checked: a real phone, screens under 360dp wide, font scale 200%, TalkBack.
