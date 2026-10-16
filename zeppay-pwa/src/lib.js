// Pure helpers: UPI parsing, USSD building, validation, formatting.
export const MAX_AMOUNT = 5000;
export const MIN_AMOUNT = 1;
export const USSD_SEND = '*99*1*3#'; // send money to a UPI ID
export const USSD_MOBILE = '*99*1*1#'; // send money to a mobile number
export const USSD_BALANCE = '*99*3#';
export const USSD_MENU = '*99#';

const VPA_RE = /^[a-zA-Z0-9._-]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,63}$/;

export function isValidVpa(v) {
  return typeof v === 'string' && VPA_RE.test(v.trim());
}

// Accepts upi://pay?pa=..&pn=..&am=..&tn=.. (also a bare VPA). Returns null if not UPI.
export function parseUpi(text) {
  if (typeof text !== 'string') return null;
  const s = text.trim();
  if (!s) return null;
  if (isValidVpa(s)) return { vpa: s, name: '', amount: '', note: '' };
  if (!/^upi:\/\//i.test(s)) return null;
  let q;
  try { q = new URL(s.replace(/^upi:\/\//i, 'https://upi.local/')).searchParams; } catch { return null; }
  const pa = (q.get('pa') || '').trim();
  if (!isValidVpa(pa)) return null;
  const am = (q.get('am') || '').trim();
  return {
    vpa: pa,
    name: (q.get('pn') || '').trim().slice(0, 60),
    amount: am && Number(am) > 0 ? String(Number(am)) : '',
    note: (q.get('tn') || '').trim().slice(0, 50),
  };
}

export function validateAmount(raw) {
  const n = Number(raw);
  if (raw === '' || raw == null || !Number.isFinite(n)) return 'Enter an amount';
  if (n < MIN_AMOUNT) return `Minimum is ₹${MIN_AMOUNT}`;
  if (n > MAX_AMOUNT) return `*99# allows up to ₹${MAX_AMOUNT.toLocaleString('en-IN')} per payment`;
  if (Math.round(n * 100) !== n * 100) return 'Use at most 2 decimals';
  return '';
}

export function formatINR(n) {
  const v = Number(n) || 0;
  return '₹' + v.toLocaleString('en-IN', { minimumFractionDigits: Number.isInteger(v) ? 0 : 2, maximumFractionDigits: 2 });
}

export function telHref(code) {
  return 'tel:' + code.replace(/#/g, '%23');
}

export const CARRIERS = [
  { id: 'airtel', name: 'Airtel', ok: true },
  { id: 'vi', name: 'Vi', ok: true },
  { id: 'bsnl', name: 'BSNL', ok: true },
  { id: 'jio', name: 'Jio', ok: false },
  { id: 'other', name: 'Other', ok: true },
];

export function carrierSupported(id) {
  const c = CARRIERS.find((x) => x.id === id);
  return c ? c.ok : true;
}

export function isValidMobile(v) {
  return /^[6-9]\d{9}$/.test(String(v || '').replace(/[\s-]/g, '').replace(/^(\+91|91|0)(?=\d{10}$)/, ''));
}
export function normMobile(v) {
  return String(v || '').replace(/[\s-]/g, '').replace(/^(\+91|91|0)(?=\d{10}$)/, '');
}

export function newId(now = Date.now(), rnd = Math.random) {
  return now.toString(36) + Math.floor(rnd() * 1e6).toString(36);
}

export function upiLink({ vpa, name, amount, note }) {
  const p = new URLSearchParams({ pa: vpa });
  if (name) p.set('pn', name);
  if (amount) p.set('am', String(amount));
  p.set('cu', 'INR');
  if (note) p.set('tn', note);
  return 'upi://pay?' + p.toString();
}

export function initials(name) {
  const parts = (name || '').replace(/@.*/, '').split(/[\s._-]+/).filter(Boolean);
  return ((parts[0]?.[0] || '?') + (parts[1]?.[0] || '')).toUpperCase();
}

export function groupByDay(items, now = Date.now()) {
  const day = (t) => new Date(t).toDateString();
  const today = day(now), yest = day(now - 864e5);
  const out = [];
  for (const it of items) {
    const d = day(it.at);
    const label = d === today ? 'Today' : d === yest ? 'Yesterday' : new Date(it.at).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
    let g = out.find((x) => x.label === label);
    if (!g) out.push((g = { label, items: [] }));
    g.items.push(it);
  }
  return out;
}

// Hands a tel: or upi: link to the OS. Tests can set window.__zepNavigate to capture it.
export function navigate(url) {
  if (typeof window !== 'undefined' && typeof window.__zepNavigate === 'function') window.__zepNavigate(url);
  else window.location.href = url;
}

// iOS blocks tel: links that look like USSD call-forwarding codes (e.g. *99*1*3#). Plain *99# is the safe form there.
export function isIOS() {
  if (typeof window === 'undefined') return false;
  if (typeof window.__zepIOS === 'boolean') return window.__zepIOS;
  const n = window.navigator || {};
  return /iPhone|iPad|iPod/.test(n.userAgent || '') || (n.platform === 'MacIntel' && n.maxTouchPoints > 1);
}
