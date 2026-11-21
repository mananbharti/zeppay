const CACHE = 'zeppay-offline-3475229c31a4d26b';
const PRECACHE = ["./","./assets/atkinson-_PkjPGrw.ttf","./assets/bricolage-IeJf__1j.ttf","./assets/index-3Qd4UTr4.css","./assets/index-Qv3_31Ti.js","./assets/reader-CiZmKcSG.js","./assets/zxing_reader-_HcWiliU.wasm","./favicon.png","./icon-180.png","./icon-192.png","./icon-512.png","./icon-maskable-512.png","./index.html","./manifest.webmanifest"];
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
