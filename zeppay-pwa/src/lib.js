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

export function isValidAccount(v) {
  return /^\d{9,18}$/.test(String(v || '').replace(/[\s-]/g, ''));
}
export function normAccount(v) {
  return String(v || '').replace(/[\s-]/g, '');
}
export function isValidIfsc(v) {
  return /^[A-Za-z]{4}0[A-Za-z0-9]{6}$/.test(String(v || '').trim());
}
export function normIfsc(v) {
  return String(v || '').trim().toUpperCase();
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

// Platform dialer rule (user-requested):
// - iOS 27: double-hash codes work in tel: links there, so dial the direct sub-codes (*99*1*3## style).
// - iOS 26 and below: iOS blocks longer USSD-style tel: links, so only the bare menu (*99#) is safe.
// - Android and everything else: dial the full fast-path command with the payee embedded (*99*1*3*<vpa>#).
const defaultUA = () => (typeof navigator === 'undefined' ? '' : navigator.userAgent);
function uaPlatform(ua) {
  const ios = /iPhone|iPad|iPod/.test(ua);
  const m = ua.match(/(?:CPU (?:iPhone )?OS|iPhone OS) (\d+)[_\.]/);
  return { ios, major: m ? Number(m[1]) : null };
}

// Bare menu code for the given mode.
export function ussdMenuCode(ua = defaultUA(), mode = 'auto') {
  return ussdHandoffKind(ua, mode) === 'sub' ? '*99##' : '*99#';
}

// 'fast': payee embedded in the command (Android/others). 'sub': full-length double-hash code, payee pasted (iOS 27). 'menu': full-length single-hash code, payee pasted (iOS 26 and below).
// mode: 'auto' | 'ios27' | 'ios26' (user setting; iPhone UA version reporting is unreliable).
export function ussdHandoffKind(ua = defaultUA(), mode = 'auto') {
  const { ios } = uaPlatform(ua);
  if (!ios) return 'fast';
  if (mode === 'ios27') return 'sub';
  if (mode === 'ios26') return 'menu';
  // Auto: Safari often freezes the reported OS version, so the UA cannot be trusted on
  // iPhone. Default to the iOS 27 double-hash form; the user can pin "iOS 26 or below"
  // in Settings when the dialer rejects it.
  return 'sub';
}

// Payment dial code for a payee ({ vpa } or { mobile }).
export function ussdPayCode(payee, ua = defaultUA(), mode = 'auto') {
  const kind = ussdHandoffKind(ua, mode);
  // Bank account + IFSC is a two-field payee, so it is never embedded: dial the
  // account-transfer sub-code and paste the account number, then the IFSC.
  if (payee.account) return kind === 'sub' ? '*99*1*5##' : '*99*1*5#';
  if (kind === 'fast') {
    if (payee.vpa) return `*99*1*3*${payee.vpa}#`;
    if (payee.mobile) return `*99*1*1*${payee.mobile}#`;
    return ussdMenuCode(ua, mode);
  }
  // iOS rejects codes with the payee embedded, so iPhone dials the full-length
  // sub-code and the payee gets pasted at the prompt: double hash on iOS 27,
  // single hash on iOS 26 and below.
  if (kind === 'sub') return payee.vpa ? '*99*1*3##' : '*99*1*1##';
  if (payee.vpa) return '*99*1*3#';
  if (payee.mobile) return '*99*1*1#';
  return ussdMenuCode(ua, mode);
}

// Balance dial code.
export function ussdBalanceCode(ua = defaultUA(), mode = 'auto') {
  return ussdHandoffKind(ua, mode) === 'sub' ? '*99*3##' : '*99*3#';
}

/* ---------- Splits (on this device only; no sync) ---------- */
export const ME = 'me';
const r2 = (v) => Math.round(v * 100) / 100;

// Equal split; the last share absorbs the paisa remainder so shares sum exactly.
export function equalShares(amount, ids) {
  if (!ids.length) return {};
  const base = Math.floor((amount * 100) / ids.length) / 100;
  const out = {};
  ids.forEach((id, i) => { out[id] = i === ids.length - 1 ? r2(amount - base * (ids.length - 1)) : base; });
  return out;
}

// Pairwise netted debts [{from, to, amount}]. A settlement from A to B cancels A's debt to B.
export function debts(group) {
  const owe = new Map();
  const add = (from, to, v) => { if (from !== to && v) owe.set(from + '>' + to, (owe.get(from + '>' + to) || 0) + v); };
  for (const e of group.expenses || []) for (const [who, share] of Object.entries(e.shares)) add(who, e.paidBy, share);
  for (const s of group.settlements || []) add(s.to, s.from, s.amount);
  const ids = [...new Set([...(group.people || []).map((p) => p.id), ME])];
  const out = [];
  for (let i = 0; i < ids.length; i++) for (let j = i + 1; j < ids.length; j++) {
    const a = ids[i], b = ids[j];
    const net = r2((owe.get(a + '>' + b) || 0) - (owe.get(b + '>' + a) || 0));
    if (Math.abs(net) >= 0.01) out.push(net > 0 ? { from: a, to: b, amount: net } : { from: b, to: a, amount: -net });
  }
  return out;
}

export const totalYouOwe = (gs) => r2(gs.reduce((s, g) => s + debts(g).filter((d) => d.from === ME).reduce((x, d) => x + d.amount, 0), 0));
export const totalOwedToYou = (gs) => r2(gs.reduce((s, g) => s + debts(g).filter((d) => d.to === ME).reduce((x, d) => x + d.amount, 0), 0));

/* ---------- Spends: ZepPay-recorded payments only, never a bank statement ---------- */
export function monthly(history, months = 6, now = Date.now()) {
  const out = [];
  const d = new Date(now);
  for (let k = 0; k < months; k++) {
    const y = d.getFullYear(), m = d.getMonth();
    const inMonth = history.filter((t) => { const x = new Date(t.at); return x.getFullYear() === y && x.getMonth() === m; });
    const paid = inMonth.filter((t) => t.status === 'success');
    const byWho = {};
    for (const t of paid) { const k2 = t.name || t.vpa || t.mobile || t.account; byWho[k2] = r2((byWho[k2] || 0) + Number(t.amount)); }
    out.push({
      year: y, month: m, paid: r2(paid.reduce((s, t) => s + Number(t.amount), 0)), paidCount: paid.length,
      unconfirmed: inMonth.filter((t) => t.status === 'unknown' || t.status === 'pending').length,
      failed: inMonth.filter((t) => t.status === 'failed').length,
      top: Object.entries(byWho).sort((a, b) => b[1] - a[1]).slice(0, 3),
    });
    d.setDate(1); d.setMonth(d.getMonth() - 1);
  }
  return out;
}
