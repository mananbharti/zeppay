import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { decodeImageData } from './qr.js';
import * as Ic from './Icons.jsx';
import { usePersisted } from './store.js';
import {
  parseUpi, validateAmount, formatINR, telHref, CARRIERS, carrierSupported, newId, initials, groupByDay,
  ussdMenuCode, ussdHandoffKind, ussdPayCode, ussdBalanceCode, isIOS, isValidVpa, isValidMobile, normMobile,
  isValidAccount, normAccount, isValidIfsc, normIfsc, USSD_MENU, MAX_AMOUNT,
  ME, equalShares, debts, totalYouOwe, totalOwedToYou, monthly,
} from './lib.js';

/*
 * ZepPay web app for iPhone, "Aurora Signal" identity (same as the Android app).
 * A web page can't read the bank's *99# reply, so every status here comes from the user,
 * and the app says so. The UPI PIN is only ever typed in the phone's dialer.
 */

const label = (p) => p.name || p.vpa || p.mobile || (p.account ? `A/c ${p.account}` : '');
const target = (p) => p.vpa || p.mobile || p.account;
const statusWord = (s) => ({ success: 'Paid', failed: 'Failed', unknown: 'Not confirmed', pending: 'Pending' }[s] || s);
const timeOf = (at) => new Date(at).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit' });
const whenOf = (at) => new Date(at).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
async function copyText(t) { try { await navigator.clipboard.writeText(t); return true; } catch { return false; } }

function useToast() {
  const [t, setT] = useState('');
  const timer = useRef();
  const show = useCallback((m) => { setT(m); clearTimeout(timer.current); timer.current = setTimeout(() => setT(''), 2400); }, []);
  return [t, show];
}
function useTheme() {
  const [theme, setTheme] = usePersisted('theme', 'auto');
  useEffect(() => {
    const mq = matchMedia('(prefers-color-scheme: dark)');
    const apply = () => {
      const dark = theme === 'dark' || (theme === 'auto' && mq.matches);
      document.documentElement.dataset.theme = dark ? 'dark' : 'light';
      document.querySelector('meta[name=theme-color]')?.setAttribute('content', dark ? '#070B14' : '#F5F7FC');
    };
    apply(); mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  }, [theme]);
  return [theme, setTheme];
}
function useOnline() {
  const [on, setOn] = useState(navigator.onLine);
  useEffect(() => {
    const a = () => setOn(true), b = () => setOn(false);
    addEventListener('online', a); addEventListener('offline', b);
    return () => { removeEventListener('online', a); removeEventListener('offline', b); };
  }, []);
  return on;
}

/* ---------- Kit ---------- */
const Btn = ({ kind = 'primary', icon: I, children, className = '', ...p }) => (
  <button className={`pill ${kind} ${className}`} {...p}>{I && <I width={22} height={22} />}<span>{children}</span></button>
);
const Avatar = ({ name, size = 48 }) => <span className="av" style={{ width: size, height: size, fontSize: size * 0.36 }}>{initials(name)}</span>;
const Status = ({ s }) => <span className={'status ' + s}>{statusWord(s)}</span>;
const Done = () => <span className="status success">Done</span>;
function Top({ title, onBack, action }) {
  return (
    <header className="top">
      {onBack && <button className="round" onClick={onBack} aria-label="Back"><Ic.Back /></button>}
      <h1>{title}</h1>{action}
    </header>
  );
}
function Page({ title, onBack, foot, action, children, label: aria }) {
  return (
    <div className="page" role="dialog" aria-label={aria || title}>
      <Top title={title} onBack={onBack} action={action} />
      <div className="body">{children}</div>
      {foot && <div className="foot">{foot}</div>}
    </div>
  );
}
const BankCounter = ({ children }) => <div className="bank"><b className="bank-k">BANK'S COUNTER</b>{children}</div>;
function Dial({ size = 224, working = false, onClick, aria, children }) {
  return (
    <div className={'dial' + (working ? ' working' : '')} style={{ width: size, height: size }}>
      <i /><i /><i />
      {onClick ? <button className="dial-core" onClick={onClick} aria-label={aria}>{children}</button> : <div className="dial-core">{children}</div>}
    </div>
  );
}
function Field({ label: l, error, big, ...p }) {
  return (
    <label className="field"><span className="f-l">{l}</span>
      <input className={(big ? 'big ' : '') + (error ? 'bad' : '')} aria-label={l} autoCapitalize="off" autoCorrect="off" spellCheck="false" {...p} />
      {error && <span className="f-e">{error}</span>}
    </label>
  );
}
function TelLink({ code, before, className, children, ...rest }) {
  // A real <a href="tel:..."> so iOS treats the tap as user-initiated. `before` must be synchronous.
  const href = telHref(code);
  return <a href={href} className={className} onClick={(e) => { try { before && before(); } catch { /* ignore */ } if (typeof window.__zepNavigate === 'function') { e.preventDefault(); window.__zepNavigate(href); } }} {...rest}>{children}</a>;
}

const ANDROID_APP_URL = 'https://github.com/mananbharti/zeppay/releases/latest/download/zeppay.apk';
const isAndroid = () => typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent || '');
function AndroidNotice() {
  return (
    <div className="page intro">
      <div className="hero"><div className="aurora" /><Dial size={200}><Ic.Phone width={48} height={48} /></Dial></div>
      <h1 className="h-hero">Made for iPhone</h1>
      <p className="lead">On Android, ZepPay is a full app: it fills in *99# for you and pays Jio numbers over UPI 123PAY.</p>
      <a className="pill primary" href={ANDROID_APP_URL}><Ic.Update width={22} height={22} /><span>Get the Android app</span></a>
    </div>
  );
}

export default function App() {
  const [onboarded, setOnboarded] = usePersisted('onboarded', false);
  const [theme, setTheme] = useTheme();
  const [carrier, setCarrier] = usePersisted('carrier', 'airtel');
  const [dialer, setDialer] = usePersisted('dialer', 'auto');
  const [history, setHistory] = usePersisted('history', []);
  const [groups, setGroups] = usePersisted('groups', []);
  const [pending, setPending] = usePersisted('pending', null);
  const [tab, setTab] = useState('pay');
  const [stack, setStack] = useState([]);
  const [toast, showToast] = useToast();
  const online = useOnline();
  const [offlineReady, setOfflineReady] = useState(!!window.__zepOfflineReady);
  useEffect(() => { const r = () => setOfflineReady(true); addEventListener('zep-offline-ready', r); return () => removeEventListener('zep-offline-ready', r); }, []);
  const [installEvt, setInstallEvt] = useState(null);
  useEffect(() => { const h = (e) => { e.preventDefault(); setInstallEvt(e); }; addEventListener('beforeinstallprompt', h); return () => removeEventListener('beforeinstallprompt', h); }, []);

  const go = (s) => setStack((st) => [...st, s]);
  const replace = (s) => setStack((st) => [...st.slice(0, -1), s]);
  const back = () => setStack((st) => st.slice(0, -1));
  const home = () => { setStack([]); setTab('pay'); };
  const screen = stack[stack.length - 1];

  // Back from the dialer with a payment open: ask how it went (we can't read it).
  useEffect(() => {
    const onVis = () => { if (document.visibilityState === 'visible' && pending) setStack((st) => (st[st.length - 1]?.name === 'outcome' ? st : [...st, { name: 'outcome', tx: pending }])); };
    document.addEventListener('visibilitychange', onVis);
    return () => document.removeEventListener('visibilitychange', onVis);
  }, [pending]);

  if (isAndroid()) return <AndroidNotice />;
  if (!onboarded) return <Onboarding onDone={() => setOnboarded(true)} />;

  const supported = carrierSupported(carrier);
  const record = (tx, status) => {
    const done = { ...tx, status, at: Date.now() };
    setHistory((h) => [done, ...h.filter((x) => x.id !== tx.id)].slice(0, 300));
    setPending(null);
    if (status === 'success' && tx.splitRef) {
      const [gid, pid] = tx.splitRef.split(':');
      setGroups((gs) => gs.map((g) => (g.id === gid && !(g.settlements || []).some((s) => s.txId === tx.id)
        ? { ...g, settlements: [...(g.settlements || []), { id: newId(), from: ME, to: pid, amount: Number(tx.amount), at: Date.now(), txId: tx.id }] } : g)));
    }
    return done;
  };
  const ctx = { carrier, supported, history, groups, setGroups, showToast, online, go, back, replace, home, dialer };
  const onInstall = async () => { installEvt.prompt(); await installEvt.userChoice; setInstallEvt(null); };

  const s = screen;
  return (
    <div className="app">
      {!s && <>
        <main className="tabpage">
          {tab === 'pay' && <PayTab {...ctx} />}
          {tab === 'split' && <SplitTab {...ctx} />}
          {tab === 'spends' && <SpendsTab {...ctx} />}
          {tab === 'more' && <MoreTab {...ctx} offlineReady={offlineReady} online={online} />}
        </main>
        <nav className="dock" aria-label="Main">
          {[['pay', 'Pay', Ic.Scan], ['split', 'Split', Ic.Contacts], ['spends', 'Spends', Ic.Chart], ['more', 'More', Ic.More]].map(([id, l, I]) => (
            <button key={id} className={tab === id ? 'on' : ''} aria-current={tab === id ? 'page' : undefined} onClick={() => setTab(id)}><I width={24} height={24} /><span>{l}</span></button>
          ))}
        </nav>
      </>}
      {s?.name === 'scan' && <Scanner {...ctx} onResult={(p) => replace({ name: 'pay', payee: p })} />}
      {s?.name === 'payto' && <PayTo kind={s.kind} splitRef={s.splitRef} {...ctx} onNext={(p) => replace({ name: 'pay', payee: p, splitRef: s.splitRef })} />}
      {s?.name === 'pay' && <PayScreen payee={s.payee} splitRef={s.splitRef} {...ctx} onGo={(tx) => go({ name: 'handoff', tx })} />}
      {s?.name === 'handoff' && <Handoff tx={s.tx} {...ctx} onLaunch={() => setPending(s.tx)} onPick={(st) => setStack([{ name: 'receipt', tx: record(s.tx, st) }])} />}
      {s?.name === 'outcome' && <Outcome tx={s.tx} onPick={(st) => { const d = record(s.tx, st); setStack([{ name: 'receipt', tx: d }]); }} onLater={() => { setPending(null); record(s.tx, 'unknown'); home(); }} />}
      {s?.name === 'receipt' && <Receipt tx={history.find((h) => h.id === s.tx.id) || s.tx} {...ctx} onMark={(st) => replace({ name: 'receipt', tx: record(s.tx, st) })} />}
      {s?.name === 'balance' && <Balance {...ctx} />}
      {s?.name === 'bankguide' && <BankGuide {...ctx} />}
      {s?.name === 'activity' && <Activity {...ctx} />}
      {s?.name === 'group' && <GroupView id={s.id} {...ctx} />}
      {s?.name === 'newsplit' && <NewSplit duo={s.duo} {...ctx} />}
      {s?.name === 'addexp' && <AddExpense gid={s.gid} {...ctx} />}
      {s?.name === 'settings' && <Settings {...ctx} theme={theme} setTheme={setTheme} setCarrier={setCarrier} setDialer={setDialer}
        onClear={() => { setHistory([]); showToast('Activity cleared'); }} onReplay={() => { setOnboarded(false); setStack([]); }} />}
      {s?.name === 'help' && <Help {...ctx} />}
      {s?.name === 'install' && <InstallGuide {...ctx} installEvt={installEvt} onInstall={onInstall} />}
      {s?.name === 'licences' && <Licences {...ctx} />}
      <div className={'toast' + (toast ? ' show' : '')} role="status" aria-live="polite">{toast}</div>
    </div>
  );
}

/* ---------- Onboarding: one thing per screen ---------- */
const BANK_STEPS = [
  ['Dial *99#', `Open the Phone app, type ${USSD_MENU} and call. Use the SIM registered with your bank. Jio SIMs don't have *99#.`, true],
  ['Pick your language', 'The bank\'s screen lists languages. Type the number for yours and press Send.'],
  ['Find your bank', 'Type the first 4 letters of your bank\'s IFSC (like SBIN), then pick your bank.'],
  ['Check with your debit card', 'Type the last 6 digits of your debit card and its expiry, on the bank\'s screen.'],
  ['Set your UPI PIN', 'Make a 4 or 6 digit UPI PIN on the bank\'s screen. Never tell anyone. ZepPay never asks for it.'],
];
function Onboarding({ onDone }) {
  const [i, setI] = useState(0);
  const slides = [
    ['Pay without internet', 'ZepPay pays any UPI QR, phone number or bank account through *99# on your SIM. No mobile data, no Wi-Fi.', Ic.Scan],
    ['Your PIN stays with your bank', 'You type your UPI PIN only in the Phone app, on your bank\'s screen. ZepPay never sees or saves it.', Ic.Shield],
    ['You confirm each payment', 'A web app can\'t read your bank\'s reply. After each payment, check your bank SMS and tell ZepPay how it went.', Ic.Check],
    ['Link your bank once', 'Do this once per SIM, on your bank\'s own screen. ZepPay can walk you through it from the Pay screen.', Ic.Bank],
  ];
  const [t, d, I] = slides[i]; const last = i === slides.length - 1;
  return (
    <div className="page intro">
      <button className="skip" onClick={onDone}>Skip</button>
      <div className="hero"><div className="aurora" /><Dial size={190}><I width={52} height={52} /></Dial></div>
      <div className="steps-dots" aria-label={`Step ${i + 1} of ${slides.length}`}>{slides.map((_, k) => <i key={k} className={k === i ? 'on' : k < i ? 'past' : ''} />)}</div>
      <h1 className="h-hero">{t}</h1>
      <p className="lead">{d}</p>
      <div className="grow" />
      <Btn icon={Ic.Arrow} onClick={() => (last ? onDone() : setI(i + 1))}>{last ? 'Start using ZepPay' : 'Next'}</Btn>
      <p className="fine center">Independent app. Your bank completes every payment.</p>
    </div>
  );
}

/* ---------- Pay tab ---------- */
function PayTab({ carrier, supported, history, go }) {
  const c = CARRIERS.find((x) => x.id === carrier);
  const recent = history.slice(0, 3);
  return (
    <>
      <div className="row head">
        <span className="wordmark">zeppay</span>
        <button className="sim" onClick={() => go({ name: 'settings' })} aria-label="SIM settings"><i className={supported ? 'ok' : 'warn'} />{c?.name} · *99#</button>
      </div>
      <div className="hero"><div className="aurora" />
        <Dial onClick={() => go({ name: 'scan' })} aria="Scan QR"><Ic.Scan width={54} height={54} className="dial-ic" /><b>Scan to pay</b></Dial>
      </div>
      <p className="fine center">Any UPI QR. No internet needed.</p>
      {!supported && <div className="card warn" role="alert"><b>Jio has no *99#.</b><p>Payments from this web app need a *99# SIM (Airtel, Vi, BSNL). The Android app pays Jio numbers over UPI 123PAY.</p></div>}
      <h2>Pay someone</h2>
      <div className="tiles">
        <button className="tile" onClick={() => go({ name: 'payto', kind: 'mobile' })}><span className="bub a"><Ic.Phone /></span>Mobile no.</button>
        <button className="tile" onClick={() => go({ name: 'payto', kind: 'upi' })}><span className="bub b"><Ic.At /></span>UPI ID</button>
        <button className="tile" onClick={() => go({ name: 'payto', kind: 'bank' })}><span className="bub c"><Ic.Bank /></span>Bank a/c</button>
        <button className="tile" onClick={() => go({ name: 'balance' })}><span className="bub d"><Ic.Wallet /></span>Balance</button>
      </div>
      {recent.length > 0 && (
        <section className="card">
          <div className="row"><h2 className="grow">Recent</h2><button className="link" onClick={() => go({ name: 'activity' })}>See all</button></div>
          {recent.map((t) => <TxRow key={t.id} t={t} onClick={() => go({ name: 'receipt', tx: t })} />)}
        </section>
      )}
      <button className="trow" onClick={() => go({ name: 'bankguide' })}><span className="bub c sm"><Ic.Shield /></span><span className="grow"><b>New to *99#?</b><small>Link your bank once, step by step</small></span><Ic.Arrow /></button>
      <p className="fine">UPI is a trademark of NPCI. ZepPay is an independent app, not a bank or payment service.</p>
    </>
  );
}
function TxRow({ t, onClick }) {
  return (
    <button className="tx" onClick={onClick} aria-label={`${label(t)}, ${formatINR(t.amount)}`}>
      <Avatar name={label(t)} />
      <span className="tx-m"><b>{t.name || label(t).split('@')[0]}</b><small>{timeOf(t.at)} · *99#</small></span>
      <span className="tx-a"><b>{formatINR(t.amount)}</b><Status s={t.status} /></span>
    </button>
  );
}

/* ---------- Payee ---------- */
function PayTo({ kind: initial, back, onNext }) {
  const [kind, setKind] = useState(initial);
  const [val, setVal] = useState(''); const [val2, setVal2] = useState(''); const [name, setName] = useState('');
  const ok = kind === 'upi' ? isValidVpa(val) : kind === 'mobile' ? isValidMobile(val) : isValidAccount(val) && isValidIfsc(val2);
  const submit = (e) => {
    e.preventDefault(); if (!ok) return;
    onNext(kind === 'upi' ? { vpa: val.trim(), name: name.trim() } : kind === 'mobile' ? { mobile: normMobile(val), name: name.trim() } : { account: normAccount(val), ifsc: normIfsc(val2), name: name.trim() });
  };
  const title = kind === 'upi' ? 'Pay a UPI ID' : kind === 'mobile' ? 'Pay a phone number' : 'Pay a bank account';
  return (
    <Page title={title} onBack={back} label="Pay to">
      <form className="stack" onSubmit={submit}>
        <div className="seg" role="tablist" aria-label="Payee type">
          {[['upi', 'UPI ID'], ['mobile', 'Mobile number'], ['bank', 'Bank a/c']].map(([k, l]) => (
            <button key={k} type="button" role="tab" aria-selected={kind === k} className={kind === k ? 'on' : ''} onClick={() => { setKind(k); setVal(''); setVal2(''); }}>{l}</button>))}
        </div>
        {kind === 'upi' && <Field big label="UPI ID" placeholder="name@bank" inputMode="email" value={val} onChange={(e) => setVal(e.target.value)} autoFocus error={val && !ok ? 'Use the format name@bank.' : null} />}
        {kind === 'mobile' && <Field big label="Mobile number" placeholder="10 digits" inputMode="numeric" value={val} onChange={(e) => setVal(e.target.value)} autoFocus error={val && !ok ? 'Enter a valid 10-digit Indian mobile number.' : null} />}
        {kind === 'bank' && <>
          <Field big label="Account number" placeholder="9 to 18 digits" inputMode="numeric" value={val} onChange={(e) => setVal(e.target.value)} autoFocus error={val && !isValidAccount(val) ? 'Account numbers are 9 to 18 digits.' : null} />
          <Field label="IFSC" placeholder="e.g. HDFC0001234" maxLength={11} autoCapitalize="characters" value={val2} onChange={(e) => setVal2(e.target.value)} error={val2 && !isValidIfsc(val2) ? 'IFSC is 11 characters, like HDFC0001234.' : null} />
        </>}
        <Field label="Payee name (optional)" placeholder="Only for your records" maxLength={40} value={name} onChange={(e) => setName(e.target.value)} />
        {kind !== 'upi' && <p className="fine">The number must be linked to UPI. Over *99#, you paste it at the bank's prompt.</p>}
        <div className="foot-inline"><Btn icon={Ic.Arrow} disabled={!ok}>Next</Btn></div>
      </form>
    </Page>
  );
}

/* ---------- Amount + check ---------- */
function PayScreen({ payee, splitRef, supported, carrier, back, onGo }) {
  const [amount, setAmount] = useState(payee.amount || '');
  const [note, setNote] = useState(payee.note || '');
  const [sure, setSure] = useState(false);
  const err = validateAmount(amount);
  const fixed = !!payee.amount;
  const go = () => onGo({ id: newId(), vpa: payee.vpa, mobile: payee.mobile, account: payee.account, ifsc: payee.ifsc, name: payee.name || '', amount: Number(amount), note, route: 'ussd', splitRef: splitRef || '' });
  return (
    <Page title="Check and pay" onBack={back} label="Pay"
      foot={<>
        <Btn icon={Ic.Signal} disabled={!!err || !sure || !supported} onClick={go}>{`Pay ${!err && amount ? formatINR(amount) : ''} with *99#`}</Btn>
        {!supported && <p className="err center">Jio SIMs can't use *99#.</p>}
      </>}>
      <div className="row"><Avatar name={label(payee)} size={52} /><div className="grow"><b className="pname">{payee.name || 'Paying'}</b><div className="muted" data-testid="payee-vpa">{payee.vpa || payee.mobile || `A/c ${payee.account} · ${payee.ifsc}`}</div></div></div>
      <div className="card amount">
        <label className="amt"><span>₹</span><input inputMode="decimal" aria-label="Amount" placeholder="0" value={amount} readOnly={fixed} autoFocus={!fixed}
          onChange={(e) => { const v = e.target.value.replace(/[^0-9.]/g, ''); if ((v.match(/\./g) || []).length < 2 && v.length <= 8) setAmount(v); }} /></label>
        <p className="muted">*99# allows ₹1 to ₹{MAX_AMOUNT.toLocaleString('en-IN')} per payment</p>
      </div>
      {amount && err && <p className="err">{err}</p>}
      {!fixed && <div className="chips">{[50, 100, 200, 500, 1000].map((v) => <button key={v} className={'chip' + (amount === String(v) ? ' on' : '')} onClick={() => setAmount(String(v))}>₹{v}</button>)}</div>}
      {!payee.mobile && <Field label="Note" placeholder="Optional, e.g. chai" maxLength={50} value={note} onChange={(e) => setNote(e.target.value)} />}
      <label className="check"><input type="checkbox" checked={sure} onChange={(e) => setSure(e.target.checked)} /><span>I've checked the name, number and amount.</span></label>
      <div className="card"><div className="row"><Ic.Shield className="ok-ic" /><b>Your PIN stays with your bank</b></div><p className="muted">You type it in the Phone app, on your bank's screen. ZepPay never sees it. Over {CARRIERS.find((x) => x.id === carrier)?.name} *99#, no data needed.</p></div>
    </Page>
  );
}

/* ---------- Handoff to the dialer ---------- */
function Handoff({ tx, dialer = 'auto', showToast, back, onLaunch, onPick }) {
  const code = ussdPayCode(tx, undefined, dialer);
  const kind = ussdHandoffKind(undefined, dialer);
  const menuCode = ussdMenuCode(undefined, dialer);
  const tgt = target(tx);
  const embeds = kind === 'fast' && !tx.account;
  const before = () => { onLaunch(); copyText(tgt).then((ok) => { if (ok) showToast(tx.vpa ? 'UPI ID copied' : tx.mobile ? 'Number copied' : 'Account number copied'); }); };
  return (
    <Page title="Pay via *99#" onBack={back} label="Complete payment"
      foot={<>
        <TelLink code={code} before={before} className="pill primary" data-testid="open-handoff"><Ic.Call width={22} height={22} /><span>Open the Phone app</span></TelLink>
        <div className="two">
          <button className="pill secondary" data-testid="mark-success" onClick={() => onPick('success')}><span>It worked</span></button>
          <button className="pill secondary" onClick={() => onPick('failed')}><span>It didn't work</span></button>
        </div>
      </>}>
      <div className="hero sm"><div className="aurora" /><Dial size={180}><b className="dial-amt">{formatINR(tx.amount)}</b><small>to {tx.name || tgt}</small></Dial></div>
      <BankCounter>
        <h3>Finish in the Phone app</h3>
        {embeds
          ? <p>ZepPay opens <code>{code}</code> with {tx.vpa ? 'the UPI ID' : 'the number'} in it. Enter <b>{formatINR(tx.amount)}</b>, then your UPI PIN, on your bank's screen.</p>
          : <p>ZepPay copies {tx.vpa ? 'the UPI ID' : tx.mobile ? 'the number' : 'the account number'} and opens <code>{code}</code>. Paste it{tx.ifsc ? ', then the IFSC,' : ''} at the prompt, enter <b>{formatINR(tx.amount)}</b>, then your UPI PIN.</p>}
      </BankCounter>
      <div className="stack-sm">
        {kind !== 'fast' && <TelLink code={menuCode} className="pill secondary" data-testid="open-menu"><Ic.Call width={20} height={20} /><span>Open the {menuCode} menu instead</span></TelLink>}
        <button className="pill secondary" data-testid="copy-target" onClick={async () => showToast((await copyText(tgt)) ? 'Copied' : 'Copy not available')}><Ic.Copy width={20} height={20} /><span>Copy {tgt}</span></button>
        {tx.ifsc && <button className="pill secondary" data-testid="copy-ifsc" onClick={async () => showToast((await copyText(tx.ifsc)) ? 'IFSC copied' : 'Copy not available')}><Ic.Copy width={20} height={20} /><span>Copy IFSC {tx.ifsc}</span></button>}
      </div>
      <p className="fine">When you come back, ZepPay asks how it went. Your bank SMS is where most people check.{isIOS() ? ' Phone app rejects the code? Change "Dialer codes" in Settings.' : ''}</p>
    </Page>
  );
}
function Outcome({ tx, onPick, onLater }) {
  return (
    <Page title="Did it go through?" label="Payment outcome"
      foot={<>
        <Btn kind="secondary" icon={Ic.Check} onClick={() => onPick('success')}>Yes, my bank says paid</Btn>
        <Btn kind="secondary" icon={Ic.Cross} onClick={() => onPick('failed')}>No, it failed</Btn>
        <Btn onClick={onLater}>Not sure yet</Btn>
      </>}>
      <div className="card center"><div className="big-amt">{formatINR(tx.amount)}</div><b>to {tx.name || label(tx)}</b></div>
      <p>ZepPay can't read the Phone app, so it can't tell if this worked. Check the SMS from your bank, then tell ZepPay so your records are right.</p>
      <div className="card warn"><b>Don't pay again yet</b><p>If you're not sure, the money may already have gone. Paying again could take it twice.</p></div>
    </Page>
  );
}

/* ---------- Receipt ---------- */
function Receipt({ tx, back, go, onMark }) {
  const st = tx.status;
  const share = async () => {
    const text = `ZepPay record: ${formatINR(tx.amount)} to ${tx.name || label(tx)} on ${whenOf(tx.at)}. Status: ${statusWord(st)} (as I marked it). My bank SMS is the usual confirmation.`;
    try { if (navigator.share) await navigator.share({ text }); else await navigator.clipboard.writeText(text); } catch { /* cancelled */ }
  };
  return (
    <Page title="Payment" onBack={back} label="Receipt"
      action={<button className="round" onClick={share} aria-label="Share"><Ic.Share /></button>}
      foot={st === 'unknown' || st === 'pending'
        ? <><Btn kind="secondary" icon={Ic.Check} onClick={() => onMark('success')}>My bank SMS says paid</Btn><Btn kind="secondary" icon={Ic.Cross} onClick={() => onMark('failed')}>My bank SMS says failed</Btn></>
        : <Btn icon={Ic.Arrow} onClick={() => go({ name: 'pay', payee: { vpa: tx.vpa, mobile: tx.mobile, account: tx.account, ifsc: tx.ifsc, name: tx.name } })}>Pay again</Btn>}>
      <div className={'badge ' + st}><span>{st === 'success' ? <Ic.Check width={44} height={44} /> : st === 'failed' ? <Ic.Cross width={44} height={44} /> : <Ic.Question width={44} height={44} />}</span><b>{statusWord(st)}</b></div>
      <div className="big-amt center">{formatINR(tx.amount)}</div>
      <p className="center"><b>{st === 'success' ? 'Paid to' : st === 'failed' ? 'Failed to pay' : 'Not confirmed, to'} {tx.name || label(tx)}</b></p>
      <dl className="card kv">
        <dt>{tx.vpa ? 'UPI ID' : tx.mobile ? 'Mobile' : 'Account'}</dt><dd>{tx.vpa || tx.mobile || `${tx.account} (${tx.ifsc})`}</dd>
        <dt>When</dt><dd>{whenOf(tx.at)}</dd><dt>Paid via</dt><dd>*99#</dd><dt>Status from</dt><dd>You</dd>
        {tx.note && <><dt>Note</dt><dd>{tx.note}</dd></>}
      </dl>
      <p className="fine">This is what you told ZepPay. Your bank SMS is the usual confirmation.</p>
    </Page>
  );
}

/* ---------- Balance ---------- */
function Balance({ supported, dialer = 'auto', back }) {
  const code = ussdBalanceCode(undefined, dialer);
  return (
    <Page title="Check balance" onBack={back}
      foot={supported ? <TelLink code={code} className="pill primary" data-testid="balance-dial"><Ic.Call width={22} height={22} /><span>Open the Phone app</span></TelLink>
        : <button className="pill primary" disabled data-testid="balance-dial"><span>Open the Phone app</span></button>}>
      <BankCounter><h3>Your balance, on your bank's screen</h3><p>ZepPay opens <code>{code}</code>. Pick your account and enter your UPI PIN in the Phone app. Your bank shows the balance. ZepPay can't read or save it.</p></BankCounter>
      {!supported && <p className="err">Jio SIMs can't use *99#.</p>}
    </Page>
  );
}

/* ---------- Bank setup: one step per screen ---------- */
function BankGuide({ back }) {
  const [i, setI] = useState(0);
  const [t, d, dial] = BANK_STEPS[i]; const last = i === BANK_STEPS.length - 1;
  return (
    <Page title="Link your bank" onBack={() => (i ? setI(i - 1) : back())} label="Link your bank"
      foot={<>
        {dial && <TelLink code={ussdMenuCode()} className="pill primary"><Ic.Call width={22} height={22} /><span>Dial {ussdMenuCode()} now</span></TelLink>}
        <Btn kind={dial ? 'secondary' : 'primary'} icon={last ? Ic.Check : Ic.Arrow} onClick={() => (last ? back() : setI(i + 1))}>{last ? 'Done' : dial ? 'I\'ve dialled it' : 'Next'}</Btn>
      </>}>
      <div className="steps-dots">{BANK_STEPS.map((_, k) => <i key={k} className={k === i ? 'on' : k < i ? 'past' : ''} />)}</div>
      <p className="kicker">Step {i + 1} of {BANK_STEPS.length}</p>
      <h1 className="h-hero">{t}</h1>
      <p className="lead">{d}</p>
      {i > 0 && <BankCounter><p>You type this on the bank's screen. ZepPay isn't involved in this step.</p></BankCounter>}
    </Page>
  );
}

/* ---------- Activity ---------- */
function Activity({ history, back, go }) {
  const unsure = history.filter((t) => t.status === 'unknown' || t.status === 'pending');
  const groups = useMemo(() => groupByDay(history.filter((t) => !unsure.includes(t))), [history]); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <Page title="Activity" onBack={back}>
      <p className="fine">Payments started in ZepPay on this phone. Statuses are what you marked.</p>
      {history.length === 0 && <div className="empty"><Ic.Clock width={40} height={40} /><p>No payments yet</p></div>}
      {unsure.length > 0 && <section className="card"><h2>Check these</h2>{unsure.map((t) => <TxRow key={t.id} t={t} onClick={() => go({ name: 'receipt', tx: t })} />)}</section>}
      {groups.map((g) => <section key={g.label} className="card"><h2>{g.label}</h2>{g.items.map((t) => <TxRow key={t.id} t={t} onClick={() => go({ name: 'receipt', tx: t })} />)}</section>)}
    </Page>
  );
}

/* ---------- Split ---------- */
function SplitTab({ groups, go }) {
  return (
    <>
      <h1 className="h-tab">Split a bill</h1>
      <div className="card split-sum">
        <div><small>You owe</small><b>{formatINR(totalYouOwe(groups))}</b></div>
        <div><small>Owed to you</small><b className="ok">{formatINR(totalOwedToYou(groups))}</b></div>
      </div>
      <Btn icon={Ic.Contacts} onClick={() => go({ name: 'newsplit', duo: true })}>Split with one person</Btn>
      <Btn kind="secondary" icon={Ic.Plus} onClick={() => go({ name: 'newsplit', duo: false })}>Make a group</Btn>
      {groups.length > 0 && <h2>Your splits</h2>}
      {[...groups].sort((a, b) => b.createdAt - a.createdAt).map((g) => {
        const ds = debts(g); const owe = ds.filter((d) => d.from === ME).reduce((s, d) => s + d.amount, 0); const owed = ds.filter((d) => d.to === ME).reduce((s, d) => s + d.amount, 0);
        return <button key={g.id} className="trow" onClick={() => go({ name: 'group', id: g.id })}><span className={'bub sm ' + (owe ? 'd' : owed ? 'a' : 'c')}>{g.duo ? <Ic.Contacts /> : <Ic.Split />}</span>
          <span className="grow"><b>{g.name}</b><small>{owe ? `You owe ${formatINR(owe)}` : owed ? `You're owed ${formatINR(owed)}` : 'All settled'}</small></span><Ic.Arrow /></button>;
      })}
      <div className="card"><b>Saved on this phone only</b><p className="muted">Splits don't sync to anyone else: that would need internet and an account, which ZepPay doesn't have. Share a reminder from a split instead.</p></div>
    </>
  );
}
const nameOf = (g, id) => (id === ME ? 'You' : (g.people.find((p) => p.id === id) || {}).name || 'Someone');
function GroupView({ id, groups, setGroups, back, go }) {
  const g = groups.find((x) => x.id === id);
  const [del, setDel] = useState(false);
  if (!g) return <Page title="Split" onBack={back}><p>This split was deleted.</p></Page>;
  const ds = debts(g);
  const settle = (from, amount) => setGroups((gs) => gs.map((x) => (x.id === id ? { ...x, settlements: [...(x.settlements || []), { id: newId(), from, to: ME, amount, at: Date.now() }] } : x)));
  const remind = async (d) => { const t = `Hi ${nameOf(g, d.from)}, for "${g.name}" you owe me ${formatINR(d.amount)}. (Sent from ZepPay)`; try { if (navigator.share) await navigator.share({ text: t }); else await navigator.clipboard.writeText(t); } catch { /* cancelled */ } };
  return (
    <Page title={g.name} onBack={back} foot={<Btn icon={Ic.Plus} onClick={() => go({ name: 'addexp', gid: id })}>Add a bill</Btn>}>
      <h2>Who owes what</h2>
      {ds.length === 0 && <p className="muted">Everyone is settled up.</p>}
      {ds.map((d, i) => {
        const p = g.people.find((x) => x.id === d.to);
        return (
          <div key={i} className={'card' + (d.from === ME ? ' owe' : '')}>
            <p><b>{nameOf(g, d.from)} {d.from === ME ? 'owe' : 'owes'} {d.to === ME ? 'you' : nameOf(g, d.to)}</b></p>
            <div className="big-amt sm">{formatINR(d.amount)}</div>
            {d.from === ME && (p && (p.vpa || p.mobile)
              ? <Btn icon={Ic.Arrow} onClick={() => go({ name: 'pay', payee: { vpa: p.vpa, mobile: p.vpa ? '' : p.mobile, name: p.name, amount: String(d.amount) }, splitRef: `${g.id}:${p.id}` })}>Pay {p.name} {formatINR(d.amount)}</Btn>
              : <p className="fine">Add their phone or UPI ID to pay from here.</p>)}
            {d.to === ME && <div className="two"><Btn kind="secondary" onClick={() => settle(d.from, d.amount)}>They paid me</Btn><Btn kind="secondary" icon={Ic.Share} onClick={() => remind(d)}>Remind</Btn></div>}
          </div>
        );
      })}
      {g.expenses.length > 0 && <><h2>Bills</h2>{[...g.expenses].reverse().map((e) => <div key={e.id} className="card row"><span className="grow"><b>{e.title}</b><small className="muted block">{nameOf(g, e.paidBy)} paid · split {Object.keys(e.shares).length} ways</small></span><b>{formatINR(e.amount)}</b></div>)}</>}
      <Btn kind="secondary" icon={Ic.Trash} className="danger" onClick={() => { if (del) { setGroups((gs) => gs.filter((x) => x.id !== id)); back(); } else setDel(true); }}>{del ? 'Tap again to delete this split' : 'Delete split'}</Btn>
    </Page>
  );
}
function NewSplit({ duo, setGroups, replace, back }) {
  const [name, setName] = useState(''); const [people, setPeople] = useState([]);
  const [pn, setPn] = useState(''); const [pc, setPc] = useState('');
  const canAdd = pn.trim() && (!pc.trim() || isValidMobile(pc) || isValidVpa(pc));
  const mk = () => ({ id: newId(), name: pn.trim(), mobile: isValidMobile(pc) ? normMobile(pc) : '', vpa: isValidVpa(pc) ? pc.trim() : '' });
  const add = () => { if (!canAdd) return; setPeople((p) => [...p, mk()]); setPn(''); setPc(''); };
  const ready = duo ? people.length === 1 || canAdd : people.length > 0 && name.trim();
  const save = () => {
    const ps = duo && people.length === 0 ? [mk()] : people;
    const g = { id: newId(), name: duo ? ps[0].name : name.trim(), people: ps, duo, expenses: [], settlements: [], createdAt: Date.now() };
    setGroups((gs) => [...gs, g]); replace({ name: 'addexp', gid: g.id });
  };
  return (
    <Page title={duo ? 'Split with one person' : 'New group'} onBack={back} foot={<Btn icon={Ic.Check} disabled={!ready} onClick={save}>Save</Btn>}>
      {!duo && <Field label="Group name" placeholder="e.g. Goa trip" value={name} onChange={(e) => setName(e.target.value.slice(0, 30))} />}
      {people.map((p) => <div key={p.id} className="card"><b>{p.name}</b><small className="muted block">{p.vpa || p.mobile || 'No number: you can\'t pay them from ZepPay'}</small></div>)}
      {(!duo || people.length === 0) && <>
        <Field label={duo ? 'Their name' : 'Add a person: name'} placeholder="Name" value={pn} onChange={(e) => setPn(e.target.value.slice(0, 30))} />
        <Field label="Their phone or UPI ID (optional)" placeholder="Needed to pay them" value={pc} onChange={(e) => setPc(e.target.value)} error={pc && !canAdd ? 'Enter a 10-digit mobile number or a UPI ID like name@bank.' : null} />
        {!duo && <Btn kind="secondary" icon={Ic.Plus} disabled={!canAdd} onClick={add}>Add person</Btn>}
      </>}
      <p className="fine">You're included automatically.</p>
    </Page>
  );
}
function AddExpense({ gid, groups, setGroups, replace, back }) {
  const g = groups.find((x) => x.id === gid);
  const everyone = g ? [ME, ...g.people.map((p) => p.id)] : [];
  const [title, setTitle] = useState(''); const [amount, setAmount] = useState(''); const [paidBy, setPaidBy] = useState(ME); const [among, setAmong] = useState(everyone);
  if (!g) return null;
  const amt = Number(amount); const ok = amt > 0 && among.length && title.trim();
  const save = () => { setGroups((gs) => gs.map((x) => (x.id === gid ? { ...x, expenses: [...x.expenses, { id: newId(), title: title.trim(), amount: amt, paidBy, shares: equalShares(amt, among), at: Date.now() }] } : x))); replace({ name: 'group', id: gid }); };
  return (
    <Page title="Add a bill" onBack={back} foot={<Btn icon={Ic.Check} disabled={!ok} onClick={save}>{ok ? `Save · ${formatINR(amt / among.length)} each` : 'Save'}</Btn>}>
      <Field label="What was it?" placeholder="e.g. Dinner" value={title} onChange={(e) => setTitle(e.target.value.slice(0, 30))} />
      <Field big label="Total amount (₹)" placeholder="0" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^0-9.]/g, '').slice(0, 9))} />
      <h2>Who paid?</h2>
      <div className="chips">{everyone.map((id) => <button key={id} className={'chip' + (paidBy === id ? ' on' : '')} onClick={() => setPaidBy(id)}>{nameOf(g, id)}</button>)}</div>
      <h2>Split equally between</h2>
      <div className="chips">{everyone.map((id) => <button key={id} className={'chip' + (among.includes(id) ? ' on' : '')} onClick={() => setAmong((a) => (a.includes(id) ? a.filter((x) => x !== id) : [...a, id]))}>{nameOf(g, id)}</button>)}</div>
    </Page>
  );
}

/* ---------- Spends: ZepPay-recorded only ---------- */
function SpendsTab({ history, go }) {
  const months = useMemo(() => monthly(history), [history]);
  const now = months[0]; const bars = [...months].reverse(); const max = Math.max(1, ...bars.map((m) => m.paid));
  return (
    <>
      <h1 className="h-tab">Spends</h1>
      <div className="card"><b>Only what you paid with ZepPay</b><p className="muted">This isn't your bank statement. Payments made in other apps, at the bank or by card aren't here.</p></div>
      <div className="card">
        <small className="muted">Marked paid in {MONTHS[now.month]} with ZepPay</small>
        <div className="big-amt">{formatINR(now.paid)}</div>
        <p className="muted">{now.paidCount} payment{now.paidCount === 1 ? '' : 's'}</p>
        <div className="bars" aria-hidden="true">{bars.map((m, i) => <div key={i}><i className={i === bars.length - 1 ? 'now' : ''} style={{ height: `${Math.max(3, (m.paid / max) * 100)}%` }} /><small>{MONTHS[m.month]}</small></div>)}</div>
      </div>
      {(now.unconfirmed > 0 || now.failed > 0) && <div className="card warn"><b>Not counted above</b>{now.unconfirmed > 0 && <p>{now.unconfirmed} not confirmed yet. Check your bank SMS and mark them in Activity.</p>}{now.failed > 0 && <p>{now.failed} failed.</p>}</div>}
      {now.top.length > 0 && <section className="card"><h2>Most paid this month</h2>{now.top.map(([who, amt]) => <div key={who} className="row line"><Avatar name={who} size={40} /><span className="grow">{who}</span><b>{formatINR(amt)}</b></div>)}</section>}
      <Btn kind="secondary" icon={Ic.List} onClick={() => go({ name: 'activity' })}>All activity</Btn>
    </>
  );
}

/* ---------- More ---------- */
function MoreTab({ go, offlineReady }) {
  const rows = [
    ['settings', 'Settings', 'SIM, look, dialer codes', Ic.Gear, 'a'], ['install', 'Add to Home Screen', 'Open ZepPay like an app, even offline', Ic.Update, 'b'],
    ['bankguide', 'Link your bank', 'First-time *99# setup', Ic.Shield, 'c'], ['activity', 'Activity', 'Every payment you started here', Ic.List, 'd'],
    ['help', 'Help', 'How ZepPay works, limits, privacy', Ic.Help, 'a'], ['licences', 'Licences', null, Ic.List, 'b'],
  ];
  return (
    <>
      <h1 className="h-tab">More</h1>
      {rows.map(([n, t, s, I, b]) => <button key={n} className="trow" onClick={() => go({ name: n })}><span className={'bub sm ' + b}><I /></span><span className="grow"><b>{t}</b>{s && <small>{s}</small>}</span><Ic.Arrow /></button>)}
      <p className="fine" role="status">{offlineReady ? 'Saved for offline use on this device.' : 'Not yet saved for offline use. Keep ZepPay open on internet once.'} v3.0.0</p>
    </>
  );
}
function Settings({ theme, setTheme, carrier, setCarrier, dialer = 'auto', setDialer, onClear, onReplay, history, back }) {
  const [sure, setSure] = useState(false);
  return (
    <Page title="Settings" onBack={back}>
      <h2>Your SIM carrier</h2>
      <div className="chips">{CARRIERS.map((c) => <button key={c.id} className={'chip' + (carrier === c.id ? ' on' : '')} onClick={() => setCarrier(c.id)}>{c.name}</button>)}</div>
      {!carrierSupported(carrier) && <p className="err">Jio doesn't support *99#. Payments are turned off for it.</p>}
      <h2>Look</h2>
      <div className="seg" role="radiogroup" aria-label="Theme">
        {[['auto', 'Auto'], ['light', 'Daylight'], ['dark', 'Night']].map(([k, l]) => <button key={k} role="radio" aria-checked={theme === k} className={theme === k ? 'on' : ''} onClick={() => setTheme(k)}>{l}</button>)}
      </div>
      <h2>Dialer codes (iPhone)</h2>
      <div className="seg" role="radiogroup" aria-label="Dialer codes">
        {[['auto', 'Auto'], ['ios27', 'iOS 27+'], ['ios26', 'iOS 26 or below']].map(([k, l]) => <button key={k} role="radio" aria-checked={dialer === k} className={dialer === k ? 'on' : ''} onClick={() => setDialer(k)}>{l}</button>)}
      </div>
      <p className="fine">This phone gets the <code>{ussdMenuCode(undefined, dialer)}</code> style. If the Phone app rejects a code, try the other option. Not yet checked on a real iPhone.</p>
      <h2>Your data</h2>
      <Btn kind="secondary" icon={Ic.Trash} className="danger" disabled={!history.length} onClick={() => { if (sure) { onClear(); setSure(false); } else setSure(true); }}>{sure ? 'Tap again: delete all activity' : 'Clear payment activity'}</Btn>
      <Btn kind="secondary" onClick={onReplay}>Replay intro</Btn>
      <p className="fine">Everything stays on this device. No account, no analytics, no server.</p>
    </Page>
  );
}
const FAQ = [
  ['Does ZepPay move money itself?', 'No. Your bank does, through *99# on your SIM. ZepPay lines up the payment. It never sees your UPI PIN.'],
  ['Which SIMs work?', 'Airtel, Vi and BSNL support *99#. Jio does not, so this web app can\'t pay on a Jio SIM.'],
  ['Why does it ask if my payment worked?', 'A web app can\'t read your bank\'s reply in the Phone app. You tell ZepPay, and it saves that on this device.'],
  ['What are the limits?', '*99# allows ₹1 to ₹5,000 per payment, plus a daily limit set by your bank.'],
  ['Does it work without internet?', 'Yes, once saved for offline use. Payments use your SIM\'s signal, not data.'],
  ['Where is my data?', 'Only on this device. Clearing Safari\'s website data erases it.'],
];
function Help({ back, go }) {
  const [open, setOpen] = useState(0);
  return (
    <Page title="Help" onBack={back}>
      {FAQ.map(([q, a], i) => (
        <div key={q} className={'card qa' + (open === i ? ' open' : '')}><button aria-expanded={open === i} onClick={() => setOpen(open === i ? -1 : i)}><b>{q}</b><Ic.Arrow /></button>{open === i && <p className="muted">{a}</p>}</div>))}
      <button className="trow" onClick={() => go({ name: 'bankguide' })}><span className="bub sm c"><Ic.Shield /></span><span className="grow"><b>Bank-link walkthrough</b></span><Ic.Arrow /></button>
      <p className="fine">Not for vending machines or shops that need an order ID: *99# can't send one.</p>
    </Page>
  );
}
function InstallGuide({ back, installEvt, onInstall }) {
  return (
    <Page title="Add to Home Screen" onBack={back}>
      {installEvt && <Btn icon={Ic.Update} onClick={onInstall}>Install ZepPay</Btn>}
      <ol className="install">
        <li><b>Open ZepPay in Safari.</b></li>
        <li><b>Tap the Share button</b> (a square with an arrow).</li>
        <li><b>Tap "Add to Home Screen"</b>, then "Add".</li>
        <li><b>Open ZepPay from your Home Screen</b> once while online, until More says "Saved for offline use".</li>
      </ol>
      <div className="card warn"><b>Picture guide coming</b><p>Step-by-step screenshots will be added after they're taken on a real iPhone. The steps above may look slightly different on your iOS version.</p></div>
    </Page>
  );
}
function Licences({ back }) {
  return (
    <Page title="Licences" onBack={back}>
      <p>Bricolage Grotesque and Atkinson Hyperlegible Next: SIL Open Font License 1.1.</p>
      <p>jsQR: Apache 2.0. zxing-wasm: MIT. React: MIT.</p>
      <p>UPI is a trademark of NPCI. ZepPay isn't affiliated with NPCI or any bank.</p>
    </Page>
  );
}

/* ---------- Scanner (camera + picture + paste), decoded on the device ---------- */
function Scanner({ back, onResult, showToast }) {
  const video = useRef(); const canvas = useRef(); const fileRef = useRef(); const streamRef = useRef();
  const [err, setErr] = useState(''); const [torch, setTorch] = useState(false); const [paste, setPaste] = useState(''); const [scanning, setScanning] = useState(false);
  const resultRef = useRef(onResult); resultRef.current = onResult;
  const toastRef = useRef(showToast); toastRef.current = showToast;
  const handleText = useCallback((text) => { const p = parseUpi(text); if (!p) { showToast('Not a UPI QR'); return false; } onResult(p); return true; }, [onResult, showToast]);

  useEffect(() => {
    let timer, alive = true, lastBadAt = 0;
    (async () => {
      if (!navigator.mediaDevices?.getUserMedia) { setErr('Camera not available here. Pick a QR picture or paste a UPI ID below.'); return; }
      try {
        const s = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false });
        if (!alive) { s.getTracks().forEach((t) => t.stop()); return; }
        streamRef.current = s; video.current.srcObject = s; await video.current.play();
        if (!alive) { s.getTracks().forEach((t) => t.stop()); return; }
        setScanning(true);
        let detector = null;
        try { if ('BarcodeDetector' in window && (await BarcodeDetector.getSupportedFormats()).includes('qr_code')) detector = new BarcodeDetector({ formats: ['qr_code'] }); } catch { detector = null; }
        let tickNo = 0;
        const found = (text) => {
          const p = parseUpi(text);
          if (p) { s.getTracks().forEach((t) => t.stop()); try { navigator.vibrate?.(30); } catch { /* no vibration */ } resultRef.current(p); return true; }
          const now = Date.now(); if (now - lastBadAt > 2500) { lastBadAt = now; toastRef.current('That QR is not a UPI QR'); }
          return false;
        };
        const tick = async () => {
          if (!alive) return;
          const v = video.current;
          if (v && v.readyState >= 2 && v.videoWidth && v.videoHeight) {
            try {
              if (detector) { const codes = await detector.detect(v); if (!alive) return; if (codes.length && codes[0].rawValue && found(codes[0].rawValue)) return; }
              const c = canvas.current; const g = c.getContext('2d', { willReadFrequently: true });
              const region = async (sx, sy, sw, sh, cap) => { const k = Math.min(1, cap / Math.max(sw, sh)); c.width = Math.max(1, Math.round(sw * k)); c.height = Math.max(1, Math.round(sh * k)); g.drawImage(v, sx, sy, sw, sh, 0, 0, c.width, c.height); return decodeImageData(g.getImageData(0, 0, c.width, c.height)); };
              const vw = v.videoWidth, vh = v.videoHeight;
              let r = await region(0, 0, vw, vh, 1600);
              if (!r) { const side = Math.floor(Math.min(vw, vh) * 0.7); r = await region((vw - side) / 2, (vh - side) / 2, side, side, 1600); }
              if (!r && tickNo++ % 3 === 2) r = await region(0, 0, vw, vh, 3072);
              if (!alive) return; if (r && found(r)) return;
            } catch { detector = null; }
          }
          timer = setTimeout(tick, 180);
        };
        tick();
      } catch (e) {
        const n = e && e.name;
        if (n === 'NotAllowedError' || n === 'SecurityError') setErr('Camera is blocked for ZepPay. Allow it in Settings > Safari > Camera, then reload. Or pick a QR picture below.');
        else if (n === 'NotFoundError' || n === 'OverconstrainedError') setErr('No camera found. Pick a QR picture or paste a UPI ID below.');
        else if (n === 'NotReadableError') setErr('Another app is using the camera. Close it and try again.');
        else setErr('Camera would not start. Pick a QR picture or paste a UPI ID below.');
      }
    })();
    return () => { alive = false; clearTimeout(timer); streamRef.current?.getTracks().forEach((t) => t.stop()); };
  }, []);

  const fromFile = (f) => {
    if (!f) return;
    const img = new Image(); const url = URL.createObjectURL(f);
    img.onload = () => {
      const tryAt = async (maxSide) => { const k = Math.min(1, maxSide / Math.max(img.width, img.height)); const c = document.createElement('canvas'); c.width = Math.round(img.width * k); c.height = Math.round(img.height * k); const g = c.getContext('2d', { willReadFrequently: true }); g.drawImage(img, 0, 0, c.width, c.height); return decodeImageData(g.getImageData(0, 0, c.width, c.height)); };
      (async () => { const r = (await tryAt(1400)) || (await tryAt(2400)) || (await tryAt(Number.MAX_SAFE_INTEGER)); URL.revokeObjectURL(url); if (!r) showToast('No QR found in that picture'); else handleText(r); })();
    };
    img.onerror = () => { URL.revokeObjectURL(url); showToast('Could not read that picture'); };
    img.src = url;
  };
  const toggleTorch = async () => { const t = streamRef.current?.getVideoTracks()[0]; try { await t.applyConstraints({ advanced: [{ torch: !torch }] }); setTorch(!torch); } catch { showToast('Torch not supported'); } };
  return (
    <div className="page scan" role="dialog" aria-label="Scan QR">
      <video ref={video} playsInline muted className="cam" /><canvas ref={canvas} hidden />
      <header className="top light"><button className="round" onClick={back} aria-label="Close"><Ic.Close /></button><h1>Scan to pay</h1><button className={'round' + (torch ? ' on' : '')} onClick={toggleTorch} aria-label="Torch"><Ic.Torch /></button></header>
      <div className="viewfinder"><i /><i /><i /><i /></div>
      <div className="scan-sheet">
        <p className="center"><b>{err || (scanning ? 'Hold the QR inside the corners.' : 'Starting camera…')}</b></p>
        <input ref={fileRef} type="file" accept="image/*" hidden data-testid="qr-file" onChange={(e) => { fromFile(e.target.files[0]); e.target.value = ''; }} />
        <Btn kind="secondary" icon={Ic.Image} onClick={() => fileRef.current.click()}>Pick a QR picture</Btn>
        <form className="row" onSubmit={(e) => { e.preventDefault(); if (!handleText(paste)) setPaste(''); }}>
          <input className="grow paste" aria-label="Paste UPI ID or link" placeholder="Or paste name@bank / upi://…" value={paste} onChange={(e) => setPaste(e.target.value)} autoCapitalize="off" spellCheck="false" />
          <button className="round go" disabled={!paste.trim()} aria-label="Use"><Ic.Arrow /></button>
        </form>
      </div>
    </div>
  );
}
