import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import '@fontsource-variable/inter';
import './styles.css';

createRoot(document.getElementById('root')).render(<App />);
if ('serviceWorker' in navigator && location.protocol !== 'file:' && import.meta.env.PROD) {
  navigator.serviceWorker.register('./sw.js').then(async () => {
    await navigator.serviceWorker.ready;
    const mark = () => {
      if (!navigator.serviceWorker.controller) return;
      window.__zepOfflineReady = true;
      window.dispatchEvent(new Event('zep-offline-ready'));
      navigator.storage?.persist?.().catch(() => {});
    };
    mark();
    navigator.serviceWorker.addEventListener('controllerchange', mark);
  }).catch(() => {});
}
