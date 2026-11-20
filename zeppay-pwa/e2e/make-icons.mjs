// Renders the ZepPay Aurora Signal mark into public/ icons. Run: node e2e/make-icons.mjs (CHROME=path to override).
import puppeteer from 'puppeteer-core';
const svg = (pad) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#19E3C8"/><stop offset="1" stop-color="#6A4BFF"/></linearGradient></defs>
<rect width="108" height="108" fill="#070B14"/><g transform="translate(54 54) scale(${pad}) translate(-54 -54)">
<circle cx="54" cy="54" r="29" fill="none" stroke="url(#g)" stroke-width="2.6"/><circle cx="54" cy="54" r="23" fill="none" stroke="url(#g)" stroke-width="2.2" opacity=".75"/>
<path d="M45,50v-3.5a1.5,1.5 0,0 1,1.5 -1.5H50M58,45h3.5a1.5,1.5 0,0 1,1.5 1.5V50M63,58v3.5a1.5,1.5 0,0 1,-1.5 1.5H58M50,63h-3.5a1.5,1.5 0,0 1,-1.5 -1.5V58M48.5,54h11" fill="none" stroke="#3EF0B1" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"/></g></svg>`;
const b = await puppeteer.launch({ executablePath: process.env.CHROME || '/usr/bin/chromium-browser', args: ['--no-sandbox'], headless: 'new' });
const p = await b.newPage();
for (const [file, size, pad] of [['icon-192.png', 192, 1.45], ['icon-512.png', 512, 1.45], ['icon-180.png', 180, 1.45], ['favicon.png', 64, 1.6], ['icon-maskable-512.png', 512, 1.05]]) {
  await p.setViewport({ width: size, height: size });
  await p.setContent(`<html><body style="margin:0">${svg(pad).replace('<svg ', `<svg width="${size}" height="${size}" `)}</body></html>`);
  await p.screenshot({ path: 'public/' + file, clip: { x: 0, y: 0, width: size, height: size } });
}
await b.close();
