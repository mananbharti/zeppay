const CACHE = 'zeppay-offline-6fe955f79515c890';
const PRECACHE = ["./","./assets/index-Cjot5Tfh.js","./assets/index-USAICCRl.css","./assets/inter-cyrillic-ext-wght-normal-BOeWTOD4.woff2","./assets/inter-cyrillic-wght-normal-DqGufNeO.woff2","./assets/inter-greek-ext-wght-normal-DlzME5K_.woff2","./assets/inter-greek-wght-normal-CkhJZR-_.woff2","./assets/inter-latin-ext-wght-normal-DO1Apj_S.woff2","./assets/inter-latin-wght-normal-Dx4kXJAl.woff2","./assets/inter-vietnamese-wght-normal-CBcvBZtf.woff2","./assets/logo-BIhlGAvZ.png","./assets/reader-CiZmKcSG.js","./assets/zxing_reader-_HcWiliU.wasm","./favicon.png","./icon-180.png","./icon-192.png","./icon-512.png","./icon-maskable-512.png","./index.html","./manifest.webmanifest"];
self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(PRECACHE)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => (k.startsWith('zeppay-offline-') || k === 'zeppay-v2') && k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET' || new URL(request.url).origin !== location.origin) return;
  event.respondWith(caches.open(CACHE).then(async (cache) => {
    const cached = await cache.match(request);
    if (cached) return cached;
    try { return await fetch(request); }
    catch (error) { if (request.mode === 'navigate') return await cache.match('./'); throw error; }
  }));
});
