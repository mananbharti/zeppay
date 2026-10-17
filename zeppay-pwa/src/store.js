import { useEffect, useState } from 'react';
const P = 'zep.';
export function load(key, fallback) {
  try { const v = localStorage.getItem(P + key); return v == null ? fallback : JSON.parse(v); } catch { return fallback; }
}
export function save(key, val) { try { localStorage.setItem(P + key, JSON.stringify(val)); } catch { /* storage full or blocked */ } }
export function usePersisted(key, fallback) {
  const [v, setV] = useState(() => load(key, fallback));
  useEffect(() => save(key, v), [key, v]);
  return [v, setV];
}
