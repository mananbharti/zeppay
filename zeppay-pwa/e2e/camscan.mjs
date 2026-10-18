// Camera scan using a synthetic video feed, including readyState=HAVE_CURRENT_DATA.
// This is a browser regression test, not a real-iPhone hardware test.
import puppeteer from 'puppeteer-core';
import QRCode from 'qrcode';
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const PORT = 4185, URL = `http://localhost:${PORT}/`;
const srv = spawn(process.execPath, ['node_modules/vite/bin/vite.js', 'preview', '--port', String(PORT), '--strictPort', '--outDir', process.env.PREVIEW_DIR || 'dist'], { stdio: 'ignore' });
await new Promise((r) => setTimeout(r, 2500));
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; console.log('  ok  ' + m); } else { fail++; console.log('  FAIL ' + m); } };

// Synthetic UPI payload, never submitted as a payment.
const PAYLOAD = process.env.QR_PAYLOAD || 'upi://pay?pa=scan-test@examplebank&pn=Scanner%20Test&am=35';
const W = 1280, H = 720, QR = 360;
const qr = QRCode.create(PAYLOAD, { errorCorrectionLevel: 'M' });
const n = qr.modules.size;
const y = Buffer.alloc(W * H, 120);           // grey-ish background
const u = Buffer.alloc((W / 2) * (H / 2), 128), vbuf = Buffer.alloc((W / 2) * (H / 2), 128);
const ox = ((W - QR) / 2) | 0, oy = ((H - QR) / 2) | 0;
const cell = QR / n;
// Four-module quiet zone, matching QR generation requirements.
const quiet = Math.ceil(4 * cell);
for (let yy = oy - quiet; yy < oy + QR + quiet; yy++)
  for (let xx = ox - quiet; xx < ox + QR + quiet; xx++) y[yy * W + xx] = process.env.INVERT_QR ? 16 : 235;
for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) {
  const dark = !!qr.modules.get(r, c) !== !!process.env.INVERT_QR;
  const x0 = ox + Math.floor(c * cell), x1 = ox + Math.floor((c + 1) * cell);
  const y0 = oy + Math.floor(r * cell), y1 = oy + Math.floor((r + 1) * cell);
  for (let yy = y0; yy < y1; yy++) for (let xx = x0; xx < x1; xx++) {
    const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
    const rounded = process.env.STYLED_QR && Math.hypot((xx - cx) / (cell / 2), (yy - cy) / (cell / 2)) > 1.05;
    y[yy * W + xx] = dark && !rounded ? (process.env.STYLED_QR ? 110 : 16) : 235;
  }
}
const parts = [Buffer.from(`YUV4MPEG2 W${W} H${H} F30:1 Ip A1:1 C420jpeg\n`)];
for (let i = 0; i < 60; i++) parts.push(Buffer.from('FRAME\n'), y, u, vbuf);
writeFileSync('/tmp/zep-qr.y4m', Buffer.concat(parts));

async function run(name, currentFrameOnly) {
  const b = await puppeteer.launch({
    executablePath: process.env.CHROME || '/usr/bin/google-chrome',
    args: ['--no-sandbox', '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--use-file-for-fake-video-capture=/tmp/zep-qr.y4m'],
    headless: 'new',
  });
  const p = await b.newPage();
  await p.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  await p.evaluateOnNewDocument((currentFrameOnly) => {
    Object.defineProperty(window, 'BarcodeDetector', { value: undefined, configurable: true, writable: true });
    if (currentFrameOnly) Object.defineProperty(HTMLMediaElement.prototype, 'readyState', { get() { return this.videoWidth ? 2 : 0; }, configurable: true });
  }, currentFrameOnly);
  p.on('pageerror', (e) => { fail++; console.log('  FAIL pageerror ' + e.message); });
  await p.goto(URL); await wait(400);
  await p.evaluate(() => localStorage.setItem('zep.onboarded', 'true')); await p.reload(); await wait(400);
  await p.waitForFunction(() => window.__zepOfflineReady === true);
  if (process.env.OFFLINE_TEST) {
    const cacheProof = await p.evaluate(async () => {
      const keys = await caches.keys(); const c = await caches.open(keys.find(k => k.startsWith('zeppay-offline-')));
      const urls = (await c.keys()).map(r => r.url);
      return { bundle: urls.some(u => /\.js$/.test(u)), css: urls.some(u => /\.css$/.test(u)), fonts: urls.some(u => /woff2$/.test(u)) };
    });
    ok(cacheProof.bundle && cacheProof.css && cacheProof.fonts, name + ': bundles, styles and fonts precached');
    await p.setOfflineMode(true);
    await p.reload({ waitUntil: 'load' });
    await p.waitForSelector('button[aria-label="Scan QR"]');
    ok(await p.evaluate(() => !navigator.onLine), name + ': reloaded with network disabled');
    ok(await p.evaluate(() => /Pay anyone, even offline/.test(document.body.innerText)), name + ': app and local onboarding state survive offline reload');
  }
  await p.evaluate(() => { [...document.querySelectorAll('button')].find((e) => /Scan QR/i.test(e.textContent)).click(); });
  let sawHint = false, sawPayee = false;
  for (let i = 0; i < 30 && !(sawHint || sawPayee); i++) {
    await wait(100);
    [sawHint, sawPayee] = await p.evaluate(() => [/Point at any UPI QR/.test(document.body.innerText), !!document.querySelector('[data-testid=payee-vpa]')]);
  }
  ok(sawHint || sawPayee, name + ': scan sheet live (hint seen: ' + sawHint + ', decoded fast: ' + sawPayee + ')');
  const t0 = Date.now();
  let vpa = '', label = '';
  while (Date.now() - t0 < 12000) {
    vpa = await p.evaluate(() => document.querySelector('[data-testid=payee-vpa]')?.textContent || '');
    if (vpa) { label = await p.evaluate(() => document.querySelector('.pname')?.textContent || ''); break; }
    await wait(300);
  }
  ok(vpa === (new globalThis.URL(PAYLOAD)).searchParams.get('pa'), name + ': camera QR decoded to VPA (got "' + vpa + '")');
  ok(label === 'Scanner Test', name + ': payee name decoded (got "' + label + '")');
  const amt = await p.evaluate(() => document.querySelector('input[aria-label=Amount]')?.value || '');
  ok(amt === '35', name + ': amount decoded (got "' + amt + '")');
  if (process.env.CAMERA_SHOT && vpa) { await wait(600); await p.screenshot({ path: process.env.CAMERA_SHOT }); }
  await b.close();
}

await run('jsqr-normal', false);
await run('jsqr-current-frame-only', true);
srv.kill();
console.log(fail ? `\n${fail} FAILED, ${pass} passed` : `\nall ${pass} camera-scan checks passed`);
process.exit(fail ? 1 : 0);
