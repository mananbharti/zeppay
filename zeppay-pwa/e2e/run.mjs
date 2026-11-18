import puppeteer from 'puppeteer-core';
import QRCode from 'qrcode';
import { spawn } from 'node:child_process';

const PORT = 4179, URL = process.env.TARGET_URL || `http://localhost:${PORT}/`;
const srv = process.env.TARGET_URL ? { kill() {} } : spawn(process.execPath, ['node_modules/vite/bin/vite.js', 'preview', '--port', String(PORT), '--strictPort'], { stdio: 'ignore' });
await new Promise((r) => setTimeout(r, 2500));
const SHOTS = process.env.SHOTS === '1';
const b = await puppeteer.launch({ executablePath: process.env.CHROME || '/usr/bin/google-chrome', args: ['--no-sandbox', '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'], headless: 'new' });
let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; console.log('  ok  ' + m); } else { fail++; console.log('  FAIL ' + m); } };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function fresh(opts = {}) {
  const ctx = await b.createBrowserContext();
  await ctx.overridePermissions(new globalThis.URL(URL).origin, ['clipboard-read', 'clipboard-write']);
  const p = await ctx.newPage();
  await p.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  if (opts.dark) await p.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
  await p.evaluateOnNewDocument(() => { window.__nav = []; window.__copied = []; try { Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (t) => { window.__copied.push(t); } }, configurable: true }); } catch (e) {} window.__zepNavigate = (u) => window.__nav.push(u); });
  p.on('pageerror', (e) => { fail++; console.log('  FAIL pageerror ' + e.message); });
  await p.goto(URL); await wait(300);
  return p;
}
const clickText = (p, re, sel = 'button') => p.evaluate((src, sel) => { const r = new RegExp(src, 'i'); const el = [...document.querySelectorAll(sel)].find((e) => r.test(e.textContent) || r.test(e.getAttribute('aria-label') || '')); if (el) el.click(); return !!el; }, re.source, sel);
const has = (p, re) => p.evaluate((src) => new RegExp(src, 'i').test(document.body.innerText), re.source);
const shot = async (p, name) => { if (SHOTS) await wait(450); if (SHOTS) await p.screenshot({ path: `docs/screenshots/${name}.png` }); };
const retype = async (p, sel, text) => { await p.focus(sel); await p.$eval(sel, (e) => e.select()); await p.keyboard.press('Backspace'); await p.type(sel, text); };
const noOverflow = (p) => p.evaluate(() => document.documentElement.scrollWidth <= innerWidth && innerWidth === 390);
const nav = (p) => p.evaluate(() => window.__nav);
const skip = async (p) => { await clickText(p, /^Skip$/); await wait(300); };

console.log('Onboarding');
let p = await fresh();
ok(await has(p, /Pay with no internet/), 'first slide');
await shot(p, '01-welcome');
for (let i = 0; i < 3; i++) { await clickText(p, /^Next/); await wait(150); }
ok(await has(p, /One-time bank link/) && await has(p, /Dial \*99#/), 'bank-link slide lists steps');
await shot(p, '01b-banklink');
await clickText(p, /Get started/); await wait(300);
ok(await has(p, /Pay anyone, even offline/), 'lands on home');
await p.reload(); await wait(300);
ok(await has(p, /Pay anyone, even offline/), 'onboarding not repeated');
await shot(p, '02-home');
ok(await noOverflow(p), 'home has no horizontal overflow');
ok(!(await has(p, /demo/i)), 'no demo wording on home');

console.log('Pay to UPI ID, via UPI app');
await clickText(p, /^UPI ID$/); await wait(200);
await p.type('input[aria-label="UPI ID"]', 'bad'); ok(await has(p, /name@bank/), 'invalid UPI ID hint');
ok(await p.$eval('form .btn.primary', (e) => e.disabled), 'continue disabled while invalid');
await retype(p, 'input[aria-label="UPI ID"]', 'Test.User@okaxis');
await p.type('input[aria-label^="Payee name"]', 'Test User');
await p.keyboard.press('Enter'); await wait(300);
ok(await p.$eval('[data-testid=payee-vpa]', (e) => e.textContent) === 'Test.User@okaxis', 'pay form shows UPI ID');
await p.type('input[aria-label=Amount]', '5001'); ok(await has(p, /up to ₹5,000/), 'over-limit error');
await retype(p, 'input[aria-label=Amount]', '250'); await p.type('input[aria-label=Note]', 'lunch');
ok(await p.$$eval('.sheet-foot .btn', (bs) => bs.every((x) => x.disabled)), 'both pay buttons disabled until payee confirmed');
await p.click('.check input'); await shot(p, '03-pay');
ok(await noOverflow(p), 'pay form fits the screen');
ok(!(await has(p, /UPI app/)), 'pay form offers only *99#');
await clickText(p, /with \*99#/); await wait(300);
ok(await has(p, /Finish in your dialer/) && await has(p, /\*99#/), 'handoff shows *99#');
await shot(p, '04-handoff');
await p.click('[data-testid=open-handoff]'); await wait(200);
let n = await nav(p);
ok(n.length === 1 && n[0] === 'tel:*99*1*3*Test.User@okaxis%23', 'opens the full *99# command with the UPI ID');
ok(!n.some((u) => u.startsWith('upi:')), 'never opens a upi:// link');
await p.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
await wait(300);
ok(await has(p, /Did it go through/), 'asks for outcome on return');
await shot(p, '05-outcome');
await clickText(p, /Yes, payment went through/); await wait(300);
ok(await has(p, /Paid to/) && await has(p, /₹250/) && await has(p, /\*99#/), 'receipt shows paid, amount, *99#');
await shot(p, '06-receipt');
await clickText(p, /^Close$/); await clickText(p, /^History$/); await wait(300);
ok(await has(p, /Test User/) && await has(p, /Today/) && await has(p, /Paid/), 'history entry saved');
await shot(p, '07-history');
await p.reload(); await wait(300); await clickText(p, /^History$/); await wait(200);
ok(await has(p, /Test User/), 'history persists after reload');
await clickText(p, /^Home$/); await wait(200);
ok(await has(p, /Pay again/), 'pay again shortcut appears');

console.log('Pay offline via *99# (UPI ID)');
await clickText(p, /Pay Test/); await wait(300);
await retype(p, 'input[aria-label=Amount]', '75'); await p.click('.check input');
await clickText(p, /with \*99#/); await wait(300);
ok(await has(p, /Finish in your dialer/) && await has(p, /\*99#/), 'dialer handoff shows *99#');
await shot(p, '08-dialer');
await p.click('[data-testid=open-handoff]'); await wait(300);
n = await nav(p);
ok(n.at(-1) === 'tel:*99*1*3*Test.User@okaxis%23', 'opens the full *99# command with the UPI ID');
ok((await p.evaluate(() => window.__copied)).includes('Test.User@okaxis'), 'UPI ID copied to clipboard');
await clickText(p, /It didn't work/); await wait(300);
ok(await has(p, /Failed to pay/), 'failure recorded');
await clickText(p, /^Close$/);

console.log('Pay to mobile number (*99# only)');
await clickText(p, /^Mobile no\.$/); await wait(300);
await p.type('input[aria-label="Mobile number"]', '12345'); ok(await has(p, /valid 10-digit/), 'invalid mobile hint');
await retype(p, 'input[aria-label="Mobile number"]', '+91 98765 43210'); await p.keyboard.press('Enter'); await wait(300);
ok(await p.$eval('[data-testid=payee-vpa]', (e) => e.textContent) === '9876543210', 'mobile normalised');
ok(!(await has(p, /UPI app/)), 'no UPI-app wording');
await p.type('input[aria-label=Amount]', '100'); await p.click('.check input');
await clickText(p, /with \*99#/); await wait(300);
await p.click('[data-testid=open-handoff]'); await wait(200);
ok((await nav(p)).at(-1) === 'tel:*99*1*1*9876543210%23', 'opens the full *99# command with the number');
await clickText(p, /^It worked$/); await wait(300);
ok(await has(p, /Paid to/), 'mobile payment recorded');
await clickText(p, /^Close$/);

console.log('Pay to bank account (*99*1*5#)');
await clickText(p, /^UPI ID$/); await wait(200);
await clickText(p, /^Bank a\/c$/); await wait(200);
await p.type('input[aria-label="Account number"]', '50100078635437');
await p.type('input[aria-label="IFSC"]', 'hdfc0000798');
await p.keyboard.press('Enter'); await wait(300);
ok(await p.$eval('[data-testid=payee-vpa]', (e) => e.textContent.includes('50100078635437') && e.textContent.includes('HDFC0000798')), 'bank payee shown with account + IFSC');
await p.type('input[aria-label=Amount]', '100'); await p.click('.check input');
await clickText(p, /with \*99#/); await wait(200);
await p.click('[data-testid=open-handoff]'); await wait(200);
ok((await nav(p)).at(-1) === 'tel:*99*1*5%23', 'opens the account-transfer code *99*1*5#');
ok(!(await nav(p)).at(-1).includes('50100078635437'), 'account number never embedded in the code');
ok((await p.evaluate(() => window.__copied)).includes('50100078635437'), 'account number copied to clipboard');
await p.click('[data-testid=copy-ifsc]'); await wait(100);
ok((await p.evaluate(() => window.__copied)).includes('HDFC0000798'), 'IFSC copied to clipboard');
await clickText(p, /^It worked$/); await wait(300);
ok(await has(p, /Paid to/), 'bank payment recorded');
await clickText(p, /^Close$/);

console.log('Balance');
await clickText(p, /^Balance$/); await wait(300);
await p.click('[data-testid=balance-dial]'); await wait(200);
ok((await nav(p)).at(-1) === 'tel:*99*3%23', 'opens the balance command *99*3#');
await shot(p, '09-balance');
await clickText(p, /^Close$/);

console.log('Scan via QR image');
await QRCode.toFile('/tmp/zep-qr.png', 'upi://pay?pa=chaiwala@paytm&pn=Sharma%20Tea%20Stall&am=35&tn=chai', { width: 500, margin: 2 });
await clickText(p, /^Scan QR$/); await wait(600);
await shot(p, '10-scan');
await (await p.$('[data-testid=qr-file]')).uploadFile('/tmp/zep-qr.png'); await wait(900);
ok(await p.$eval('[data-testid=payee-vpa]', (e) => e.textContent) === 'chaiwala@paytm', 'QR decoded');
ok(await has(p, /Sharma Tea Stall/) && await p.$eval('input[aria-label=Amount]', (e) => e.value) === '35', 'name + amount from QR');
await clickText(p, /^Back$/);
await clickText(p, /^Scan QR$/); await wait(300);
await p.type('input[aria-label="Paste UPI ID or link"]', 'https://evil.example'); await p.keyboard.press('Enter'); await wait(300);
ok(await has(p, /Not a UPI QR/), 'non-UPI text rejected');
await clickText(p, /^Close$/);

console.log('Help, bank guide, settings');
await clickText(p, /^Help$/); await wait(300);
ok(await has(p, /Does ZepPay move money itself/), 'FAQ shown');
await clickText(p, /Which SIMs work/); ok(await has(p, /Jio does not/), 'FAQ expands');
await shot(p, '11-help');
ok(await noOverflow(p), 'help has no horizontal overflow');
await clickText(p, /Bank-link walkthrough/); await wait(300);
ok(await has(p, /Set a UPI PIN/), 'bank walkthrough opens');
await shot(p, '12-bankguide');
await clickText(p, /Dial \*99# now/, 'a'); ok((await nav(p)).at(-1) === 'tel:*99%23', 'dials *99#');
await clickText(p, /^Close$/);
await clickText(p, /^Settings$/); await wait(300); await shot(p, '13-settings');
ok(await noOverflow(p), 'settings has no horizontal overflow');
await clickText(p, /^Dark$/); ok(await p.evaluate(() => document.documentElement.dataset.theme) === 'dark', 'dark theme');
await clickText(p, /^Light$/); ok(await p.evaluate(() => document.documentElement.dataset.theme) === 'light', 'light theme');
await clickText(p, /^Jio$/); await wait(200);
ok(await has(p, /Offline payments are turned off/), 'Jio warning');
await clickText(p, /^Home$/); await wait(200);
ok(await has(p, /Jio doesn't support/), 'Jio banner on home');
await clickText(p, /^UPI ID$/); await p.type('input[aria-label="UPI ID"]', 'a.b@ybl'); await p.keyboard.press('Enter'); await wait(300);
await p.type('input[aria-label=Amount]', '10'); await p.click('.check input');
ok(await p.$$eval('.sheet-foot .btn', (bs) => bs.length === 1 && bs[0].disabled === true), 'Jio: *99# payment blocked');
await clickText(p, /^Back$/); await clickText(p, /^Back$/);
await clickText(p, /^Settings$/); await clickText(p, /^Airtel$/);
await clickText(p, /Clear payment history/); await clickText(p, /^History$/); await wait(200);
ok(await has(p, /No payments yet/), 'history cleared');
await p.close();

console.log('iPhone (auto = iOS 27 style) and Android dialer routing');
for (const [ua, payExpected, balExpected, stepRe] of [
  ['Mozilla/5.0 (iPhone; CPU iPhone OS 27_0 like Mac OS X)', 'tel:*99*1*3%23%23', 'tel:*99*3%23%23', /Paste it/],
  ['Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X)', 'tel:*99*1*3%23%23', 'tel:*99*3%23%23', /Paste it/],
  ['Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X)', 'tel:*99*1*3%23%23', 'tel:*99*3%23%23', /Paste it/],
]) {
  p = await fresh(); await p.setUserAgent(ua); await p.reload(); await wait(250);
  await skip(p);
  await clickText(p, /^UPI ID$/); await wait(200);
  await p.type('input[aria-label="UPI ID"]', 'Test.User@okaxis');
  await p.keyboard.press('Enter'); await wait(200);
  await p.type('input[aria-label=Amount]', '250'); await p.click('.check input');
  await clickText(p, /with \*99#/); await wait(200);
  ok(await has(p, stepRe) && await has(p, /UPI ID/), ua + ': handoff steps match the platform');
  await p.click('[data-testid=open-handoff]'); await wait(100);
  ok((await nav(p)).at(-1) === payExpected, ua + ': payment opens ' + payExpected);
  ok((await p.evaluate(() => window.__copied)).includes('Test.User@okaxis'), ua + ': UPI ID copied');
  await p.close();
  p = await fresh(); await p.setUserAgent(ua); await p.reload(); await wait(250); await skip(p);
  await clickText(p, /^Balance$/); await wait(200);
  await p.click('[data-testid=balance-dial]'); await wait(100);
  ok((await nav(p)).at(-1) === balExpected, ua + ': balance opens ' + balExpected);
  await p.close();
}

console.log('Android web notice (web app is iOS-only)');
p = await fresh();
await p.setUserAgent('Mozilla/5.0 (Linux; Android 16)');
await p.reload(); await wait(250);
ok(await has(p, /The ZepPay web app works on iPhone/), 'Android UA gets the native-app notice');
ok(await p.evaluate(() => [...document.querySelectorAll('a')].some((x) => /Get the Android app/.test(x.textContent))), 'notice CTA present');
const apkHref = await p.evaluate(() => { const a = [...document.querySelectorAll('a')].find((x) => /Get the Android app/.test(x.textContent)); return a ? a.href : ''; });
ok(/zeppay-release\/releases\/latest\/download\/zeppay\.apk/.test(apkHref), 'notice links the latest Android APK');
ok(!(await clickText(p, /^UPI ID$/)), 'Android notice replaces the web pay flow');
await p.close();

console.log('Dialer code setting (iPhone)');
p = await fresh();
await p.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X)');
await p.evaluateOnNewDocument(() => { try { localStorage.setItem('zep.dialer', '"ios26"'); } catch (e) {} });
await p.reload(); await wait(250); await skip(p);
await clickText(p, /^UPI ID$/); await wait(200);
await p.type('input[aria-label="UPI ID"]', 'Test.User@okaxis');
await p.keyboard.press('Enter'); await wait(200);
await p.type('input[aria-label=Amount]', '250'); await p.click('.check input');
await clickText(p, /with \*99#/); await wait(200);
ok(await has(p, /Paste it/), 'ios26 override: paste-at-prompt steps shown');
await p.click('[data-testid=open-handoff]'); await wait(100);
ok((await nav(p)).at(-1) === 'tel:*99*1*3%23', 'ios26 override: payment opens tel:*99*1*3%23');
ok((await p.$('[data-testid=open-menu]')) !== null, 'ios26 override: menu-fallback button offered');
await p.click('[data-testid=open-menu]'); await wait(100);
ok((await nav(p)).at(-1) === 'tel:*99%23', 'ios26 override: menu fallback dials tel:*99%23');
await clickText(p, /^Cancel$/); await wait(250);
await clickText(p, /^Settings$/); await wait(250);
ok(await has(p, /Dialer codes/), 'settings shows the dialer codes control');
await clickText(p, /^iOS 27\+$/); await wait(250);
ok(await p.evaluate(() => JSON.parse(localStorage.getItem('zep.dialer'))) === 'ios27', 'dialer setting persists');
await clickText(p, /^Home$/); await wait(200);
await clickText(p, /^Balance$/); await wait(200);
await p.click('[data-testid=balance-dial]'); await wait(100);
ok((await nav(p)).at(-1) === 'tel:*99*3%23%23', 'ios27 override: balance opens tel:*99*3%23%23');
await p.close();

console.log('Dark / auto');
p = await fresh({ dark: true });
ok(await p.evaluate(() => document.documentElement.dataset.theme) === 'dark', 'auto follows system dark');
await skip(p); await shot(p, '14-home-dark'); await p.close();

console.log('PWA');
const man = await (await fetch(URL + 'manifest.webmanifest')).json();
ok(man.display === 'standalone' && man.icons.length >= 3, 'manifest valid');
for (const i of man.icons) ok((await fetch(URL + i.src)).ok, 'icon ' + i.src);
p = await fresh(); await wait(1500);
ok(await p.evaluate(async () => !!(await navigator.serviceWorker.getRegistration())), 'service worker registered');
await p.setOfflineMode(true); await p.reload(); await wait(700);
ok(await has(p, /Pay with no internet|Pay anyone/), 'loads offline');
console.log('Styled FamPay-style QR, offline (zxing wasm from cache)');
const fams = [['e2e/fixtures/fam-style.png', 'demo1234@fam'], ...(process.env.FAM_REAL ? [[process.env.FAM_REAL, '9615182000@fam']] : [])];
for (let i = 0; i < 3; i++) { await clickText(p, /^Next/); await wait(100); }
await clickText(p, /Get started/); await wait(300);
for (const [f, vpa] of fams) {
  await clickText(p, /^Scan QR$/); await wait(500);
  await (await p.$('[data-testid=qr-file]')).uploadFile(f); await wait(2500);
  ok(await p.$eval('[data-testid=payee-vpa]', (e) => e.textContent).catch(() => '') === vpa, 'decoded ' + f + ' offline');
  await clickText(p, /^Back$/); await wait(300);
}
await p.close();

await b.close(); srv.kill();
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
