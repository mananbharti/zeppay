import puppeteer from 'puppeteer-core';
const url = process.argv[2] || 'http://localhost:4173/';
const b = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'], headless: 'new' });
const p = await b.newPage(); await p.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
await p.goto(url); await new Promise(r=>setTimeout(r,600));
await p.screenshot({ path: '/tmp/s1.png' });
for (let i=0;i<3;i++){ await p.evaluate(()=>[...document.querySelectorAll('button')].find(b=>/Next|Get started/.test(b.textContent))?.click()); await new Promise(r=>setTimeout(r,500)); if(i==0) await p.screenshot({ path: '/tmp/s2.png' }); }
await p.screenshot({ path: '/tmp/s3.png', fullPage:false });
await b.close();
