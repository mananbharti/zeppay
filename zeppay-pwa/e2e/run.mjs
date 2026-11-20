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
const back = async (p) => { await clickText(p, /^Back$/); await wait(250); };
const tab = async (p, name) => { await clickText(p, new RegExp('^' + name + '$'), '.dock button'); await wait(250); };
const payBtn = (p) => p.$$eval('.foot .pill', (bs) => bs.filter((x) => /Pay/.test(x.textContent)).map((x) => x.disabled));

console.log('Onboarding');
let p = await fresh();
ok(await has(p, /Pay without internet/), 'first slide');
await shot(p, '01-welcome');
for (let i = 0; i < 3; i++) { await clickText(p, /^Next/); await wait(150); }
ok(await has(p, /Link your bank once/), 'bank-link slide');
await clickText(p, /Start using ZepPay/); await wait(300);
ok(await has(p, /Scan to pay/) && await has(p, /Pay someone/), 'lands on home');
await p.reload(); await wait(300);
ok(await has(p, /Pay someone/), 'onboarding not repeated');
await shot(p, '02-home');
ok(await noOverflow(p), 'home has no horizontal overflow');
ok(!(await has(p, /demo/i)), 'no demo wording on home');

console.log('Pay a UPI ID over *99#');
await clickText(p, /^UPI ID$/); await wait(200);
await p.type('input[aria-label="UPI ID"]', 'bad'); ok(await has(p, /name@bank/), 'invalid UPI ID hint');
ok(await p.$eval('form .pill.primary', (e) => e.disabled), 'next disabled while invalid');
await retype(p, 'input[aria-label="UPI ID"]', 'Test.User@okaxis');
await p.type('input[aria-label^="Payee name"]', 'Test User');
await p.keyboard.press('Enter'); await wait(300);
ok(await p.$eval('[data-testid=payee-vpa]', (e) => e.textContent) === 'Test.User@okaxis', 'pay screen shows UPI ID');
await p.type('input[aria-label=Amount]', '5001'); ok(await has(p, /up to ₹5,000/), 'over-limit error');
await retype(p, 'input[aria-label=Amount]', '250'); await p.type('input[aria-label=Note]', 'lunch');
ok((await payBtn(p)).every((d) => d), 'pay disabled until payee confirmed');
await p.click('.check input'); await shot(p, '03-pay');
ok((await payBtn(p)).length === 1 && !(await payBtn(p))[0], 'one pay button, enabled after confirming');
ok(await noOverflow(p), 'pay screen fits');
ok(!(await has(p, /UPI app/)), 'only *99#, no UPI-app route');
await clickText(p, /with \*99#/); await wait(300);
ok(await has(p, /Finish in the Phone app/) && await has(p, /BANK'S COUNTER/), 'handoff shows the bank\'s counter');
await shot(p, '04-handoff');
await p.click('[data-testid=open-handoff]'); await wait(200);
let n = await nav(p);
ok(n.length === 1 && n[0] === 'tel:*99*1*3*Test.User@okaxis%23', 'opens the full *99# command with the UPI ID');
ok(!n.some((u) => u.startsWith('upi:')), 'never opens a upi:// link');
await p.evaluate(() => document.dispatchEvent(new Event('visibilitychange'))); await wait(300);
ok(await has(p, /Did it go through/) && await has(p, /Don't pay again yet/), 'asks for outcome on return, warns against paying twice');
await shot(p, '05-outcome');
await clickText(p, /Yes, my bank says paid/); await wait(300);
ok(await has(p, /Paid to/) && await has(p, /₹250/) && await has(p, /Status from/), 'receipt: paid, amount, says the status came from you');
await shot(p, '06-receipt');
await back(p);
ok(await has(p, /Recent/) && await has(p, /Test User/), 'home shows the payment in Recent');
await clickText(p, /^See all$/); await wait(300);
ok(await has(p, /Test User/) && await has(p, /Today/) && await has(p, /Paid/), 'activity entry saved');
await shot(p, '07-activity');
await p.reload(); await wait(300);
ok(await has(p, /Test User/), 'activity persists after reload');

console.log('Pay again from the receipt; record a failure');
await clickText(p, /Test User/); await wait(300);
await clickText(p, /^Pay again$/); await wait(300);
await retype(p, 'input[aria-label=Amount]', '75'); await p.click('.check input');
await clickText(p, /with \*99#/); await wait(300);
await p.click('[data-testid=open-handoff]'); await wait(300);
ok((await nav(p)).at(-1) === 'tel:*99*1*3*Test.User@okaxis%23', 'pay again opens the same command');
ok((await p.evaluate(() => window.__copied)).includes('Test.User@okaxis'), 'UPI ID copied to clipboard');
await clickText(p, /It didn't work/); await wait(300);
ok(await has(p, /Failed to pay/), 'failure recorded');
await back(p);

console.log('Pay a mobile number');
await clickText(p, /^Mobile no\.$/); await wait(300);
await p.type('input[aria-label="Mobile number"]', '12345'); ok(await has(p, /valid 10-digit/), 'invalid mobile hint');
await retype(p, 'input[aria-label="Mobile number"]', '+91 98765 43210'); await p.keyboard.press('Enter'); await wait(300);
ok(await p.$eval('[data-testid=payee-vpa]', (e) => e.textContent) === '9876543210', 'mobile normalised');
await p.type('input[aria-label=Amount]', '100'); await p.click('.check input');
await clickText(p, /with \*99#/); await wait(300);
await p.click('[data-testid=open-handoff]'); await wait(200);
ok((await nav(p)).at(-1) === 'tel:*99*1*1*9876543210%23', 'opens the full *99# command with the number');
await clickText(p, /^It worked$/); await wait(300);
ok(await has(p, /Paid to/), 'mobile payment recorded');
await back(p);

console.log('Pay a bank account (*99*1*5#)');
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
ok((await p.evaluate(() => window.__copied)).includes('50100078635437'), 'account number copied');
await p.click('[data-testid=copy-ifsc]'); await wait(100);
ok((await p.evaluate(() => window.__copied)).includes('HDFC0000798'), 'IFSC copied');
await clickText(p, /^It worked$/); await wait(300);
ok(await has(p, /Paid to/), 'bank payment recorded');
await back(p);

console.log('Balance');
await clickText(p, /^Balance$/); await wait(300);
ok(await has(p, /BANK'S COUNTER/), 'balance is on the bank\'s screen');
await p.click('[data-testid=balance-dial]'); await wait(200);
ok((await nav(p)).at(-1) === 'tel:*99*3%23', 'opens the balance command *99*3#');
await shot(p, '09-balance');
await back(p);

console.log('Scan via QR image');
await QRCode.toFile('/tmp/zep-qr.png', 'upi://pay?pa=chaiwala@paytm&pn=Sharma%20Tea%20Stall&am=35&tn=chai', { width: 500, margin: 2 });
await clickText(p, /^Scan QR$/); await wait(600);
await shot(p, '10-scan');
await (await p.$('[data-testid=qr-file]')).uploadFile('/tmp/zep-qr.png'); await wait(900);
ok(await p.$eval('[data-testid=payee-vpa]', (e) => e.textContent) === 'chaiwala@paytm', 'QR decoded');
ok(await has(p, /Sharma Tea Stall/) && await p.$eval('input[aria-label=Amount]', (e) => e.value) === '35', 'name + amount from QR');
await back(p);
await clickText(p, /^Scan QR$/); await wait(300);
await p.type('input[aria-label="Paste UPI ID or link"]', 'https://evil.example'); await p.keyboard.press('Enter'); await wait(300);
ok(await has(p, /Not a UPI QR/), 'non-UPI text rejected');
await clickText(p, /^Close$/); await wait(250);

console.log('Split a bill');
await tab(p, 'Split');
ok(await has(p, /Saved on this phone only/), 'split says it is local-only');
await clickText(p, /Split with one person/); await wait(250);
await p.type('input[aria-label="Their name"]', 'Ravi');
await p.type('input[aria-label^="Their phone"]', '9876543210');
await clickText(p, /^Save$/, '.foot .pill'); await wait(300);
await p.type('input[aria-label="What was it?"]', 'Dinner');
await p.type('input[aria-label^="Total amount"]', '300');
await clickText(p, /^Save/, '.foot .pill'); await wait(300);
ok(await has(p, /Ravi owes you/) && await has(p, /₹150/), 'shows who owes what');
await shot(p, '08-split');
await clickText(p, /They paid me/); await wait(250);
ok(await has(p, /settled up/), 'settling clears the debt');
await back(p);

console.log('Spends, help, bank guide, settings');
await tab(p, 'Spends');
ok(await has(p, /Only what you paid with ZepPay/) && await has(p, /isn't your bank statement/), 'spends labelled ZepPay-recorded only');
ok(await noOverflow(p), 'spends fits');
await tab(p, 'More');
await clickText(p, /^Help/); await wait(300);
ok(await has(p, /Does ZepPay move money itself/), 'FAQ shown');
await clickText(p, /Which SIMs work/); ok(await has(p, /Jio does not/), 'FAQ expands');
await shot(p, '11-help');
ok(await noOverflow(p), 'help fits');
await clickText(p, /Bank-link walkthrough/); await wait(300);
ok(await has(p, /Dial \*99#/) && await has(p, /Step 1 of 5/), 'bank walkthrough: one step per screen');
await shot(p, '12-bankguide');
await clickText(p, /Dial \*99# now/, 'a'); ok((await nav(p)).at(-1) === 'tel:*99%23', 'dials *99#');
await back(p); await back(p);
await clickText(p, /^Settings/); await wait(300); await shot(p, '13-settings');
ok(await noOverflow(p), 'settings fits');
await clickText(p, /^Night$/); ok(await p.evaluate(() => document.documentElement.dataset.theme) === 'dark', 'night theme');
await clickText(p, /^Daylight$/); ok(await p.evaluate(() => document.documentElement.dataset.theme) === 'light', 'daylight theme');
await clickText(p, /^Jio$/); await wait(200);
ok(await has(p, /Payments are turned off/), 'Jio warning');
await back(p); await tab(p, 'Pay');
ok(await has(p, /Jio has no \*99#/), 'Jio banner on home');
await clickText(p, /^UPI ID$/); await p.type('input[aria-label="UPI ID"]', 'a.b@ybl'); await p.keyboard.press('Enter'); await wait(300);
await p.type('input[aria-label=Amount]', '10'); await p.click('.check input');
ok((await payBtn(p)).length === 1 && (await payBtn(p))[0] === true, 'Jio: *99# payment blocked');
await back(p);
await tab(p, 'More'); await clickText(p, /^Settings/); await wait(250); await clickText(p, /^Airtel$/);
await clickText(p, /Clear payment activity/); await clickText(p, /Tap again/); await back(p);
await clickText(p, /^Activity/); await wait(200);
ok(await has(p, /No payments yet/), 'activity cleared');
await p.close();

console.log('iPhone dialer routing (auto = iOS 27 style)');
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
ok(await has(p, /Made for iPhone/), 'Android UA gets the native-app notice');
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
await back(p); await back(p);
await tab(p, 'More'); await clickText(p, /^Settings/); await wait(250);
ok(await has(p, /Dialer codes/), 'settings shows the dialer codes control');
await clickText(p, /^iOS 27\+$/); await wait(250);
ok(await p.evaluate(() => JSON.parse(localStorage.getItem('zep.dialer'))) === 'ios27', 'dialer setting persists');
await back(p); await tab(p, 'Pay');
await clickText(p, /^Balance$/); await wait(200);
await p.click('[data-testid=balance-dial]'); await wait(100);
ok((await nav(p)).at(-1) === 'tel:*99*3%23%23', 'ios27 override: balance opens tel:*99*3%23%23');
await p.close();

console.log('Night / auto');
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
ok(await has(p, /Pay without internet|Pay someone/), 'loads offline');
console.log('Styled FamPay-style QR, offline (zxing wasm from cache)');
const fams = [['e2e/fixtures/fam-style.png', 'demo1234@fam'], ...(process.env.FAM_REAL ? [[process.env.FAM_REAL, '9615182000@fam']] : [])];
for (let i = 0; i < 3; i++) { await clickText(p, /^Next/); await wait(100); }
await clickText(p, /Start using ZepPay/); await wait(300);
for (const [f, vpa] of fams) {
  await clickText(p, /^Scan QR$/); await wait(500);
  await (await p.$('[data-testid=qr-file]')).uploadFile(f); await wait(2500);
  ok(await p.$eval('[data-testid=payee-vpa]', (e) => e.textContent).catch(() => '') === vpa, 'decoded ' + f + ' offline');
  await back(p); await wait(300);
}
await p.close();

await b.close(); srv.kill();
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
