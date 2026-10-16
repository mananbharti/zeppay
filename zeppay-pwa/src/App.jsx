import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import jsQR from 'jsqr';
import * as Ic from './Icons.jsx';
import logo from './assets/logo.png';
import { usePersisted } from './store.js';
import {
  parseUpi, validateAmount, formatINR, telHref, CARRIERS, carrierSupported, newId, initials,
  groupByDay, navigate, isIOS, isValidVpa, isValidMobile, normMobile, USSD_SEND, USSD_MOBILE, USSD_BALANCE, USSD_MENU, MAX_AMOUNT,
} from './lib.js';

const HUES = [252, 12, 164, 36, 200, 320, 90, 280];
const hue = (s) => HUES[[...s].reduce((a, c) => a + c.charCodeAt(0), 0) % HUES.length];
function Avatar({ name, size = 48 }) {
  const h = hue(name || '?');
  return <span className="av" style={{ width: size, height: size, fontSize: size * 0.36, background: `hsl(${h} 70% 92%)`, color: `hsl(${h} 55% 32%)` }}>{initials(name)}</span>;
}
const label = (p) => p.name || (p.vpa ? p.vpa : p.mobile);

function useToast() {
  const [t, setT] = useState('');
  const timer = useRef();
  const show = useCallback((m) => { setT(m); clearTimeout(timer.current); timer.current = setTimeout(() => setT(''), 2200); }, []);
  return [t, show];
}
function useTheme() {
  const [theme, setTheme] = usePersisted('theme', 'auto');
  useEffect(() => {
    const mq = matchMedia('(prefers-color-scheme: dark)');
    const apply = () => {
      const dark = theme === 'dark' || (theme === 'auto' && mq.matches);
      document.documentElement.dataset.theme = dark ? 'dark' : 'light';
      document.querySelector('meta[name=theme-color]')?.setAttribute('content', dark ? '#07104f' : '#0a1ba8');
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
async function copyText(t) { try { await navigator.clipboard.writeText(t); return true; } catch { return false; } }

export default function App() {
  const [onboarded, setOnboarded] = usePersisted('onboarded', false);
  const [theme, setTheme] = useTheme();
  const [carrier, setCarrier] = usePersisted('carrier', 'airtel');
  const [history, setHistory] = usePersisted('history', []);
  const [pending, setPending] = usePersisted('pending', null); // a handoff awaiting the user's outcome
  const [tab, setTab] = useState('home');
  const [screen, setScreen] = useState(null);
  const [toast, showToast] = useToast();
  const online = useOnline();
  const [installEvt, setInstallEvt] = useState(null);

  useEffect(() => {
    const h = (e) => { e.preventDefault(); setInstallEvt(e); };
    addEventListener('beforeinstallprompt', h);
    return () => removeEventListener('beforeinstallprompt', h);
  }, []);

  // When the user comes back from the dialer, ask how it went.
  useEffect(() => {
    const onVis = () => { if (document.visibilityState === 'visible' && pending) setScreen((s) => (s?.name === 'outcome' ? s : { name: 'outcome', tx: pending })); };
    document.addEventListener('visibilitychange', onVis);
    return () => document.removeEventListener('visibilitychange', onVis);
  }, [pending]);

  const supported = carrierSupported(carrier);
  const go = (s) => setScreen(s);
  const close = () => setScreen(null);
  const ctx = { carrier, supported, history, showToast, online, go };

  const record = (tx, status) => {
    setHistory((h) => [{ ...tx, status, at: Date.now() }, ...h.filter((x) => x.id !== tx.id)].slice(0, 200));
    setPending(null);
  };

  if (!onboarded) return <Onboarding onDone={() => setOnboarded(true)} />;

  return (
    <div className="app">
      {tab === 'home' && <HomeTab {...ctx} installEvt={installEvt} onInstall={async () => { installEvt.prompt(); await installEvt.userChoice; setInstallEvt(null); }} goTab={setTab} />}
      {tab === 'history' && <HistoryTab {...ctx} />}
      {tab === 'help' && <HelpTab {...ctx} />}
      {tab === 'settings' && <SettingsTab {...ctx} theme={theme} setTheme={setTheme} setCarrier={setCarrier} installEvt={installEvt} onInstall={async () => { installEvt.prompt(); await installEvt.userChoice; setInstallEvt(null); }} onClear={() => { setHistory([]); showToast('History cleared'); }} onReplay={() => setOnboarded(false)} />}

      <nav className="tabbar" aria-label="Main">
        <button className={tab === 'home' ? 'on' : ''} onClick={() => setTab('home')} aria-label="Home"><Ic.Home /><span>Home</span></button>
        <button className={tab === 'history' ? 'on' : ''} onClick={() => setTab('history')} aria-label="History"><Ic.Clock /><span>History</span></button>
        <button className="fab" onClick={() => go({ name: 'scan' })} aria-label="Scan QR"><Ic.Scan width={28} height={28} /></button>
        <button className={tab === 'help' ? 'on' : ''} onClick={() => setTab('help')} aria-label="Help"><Ic.Info /><span>Help</span></button>
        <button className={tab === 'settings' ? 'on' : ''} onClick={() => setTab('settings')} aria-label="Settings"><Ic.Gear /><span>Settings</span></button>
      </nav>

      {screen?.name === 'scan' && <Scanner onClose={close} onResult={(p) => go({ name: 'pay', payee: p })} showToast={showToast} />}
      {screen?.name === 'payto' && <PayTo kind={screen.kind} onClose={close} onNext={(p) => go({ name: 'pay', payee: p })} />}
      {screen?.name === 'pay' && <PayScreen payee={screen.payee} supported={supported} carrier={carrier} onClose={close} onGo={(tx, route) => go({ name: 'handoff', tx, route })} showToast={showToast} />}
      {screen?.name === 'handoff' && <Handoff tx={screen.tx} route={screen.route} showToast={showToast} onClose={close} onLaunch={() => setPending(screen.tx)} onPick={(st) => { record(screen.tx, st); go({ name: 'receipt', tx: { ...screen.tx, status: st, at: Date.now() } }); }} onBack={() => go({ name: 'pay', payee: screen.tx })} />}
      {screen?.name === 'balance' && <BalanceScreen supported={supported} onClose={close} showToast={showToast} />}
      {screen?.name === 'outcome' && <Outcome tx={screen.tx} onPick={(st) => { record(screen.tx, st); go({ name: 'receipt', tx: { ...screen.tx, status: st, at: Date.now() } }); }} onLater={close} />}
      {screen?.name === 'receipt' && <Receipt tx={screen.tx} onClose={close} onAgain={() => go({ name: 'pay', payee: screen.tx })} />}
      {screen?.name === 'bankguide' && <BankGuide onClose={close} />}

      <div className={'toast' + (toast ? ' show' : '')} role="status" aria-live="polite">{toast}</div>
    </div>
  );
}

/* ---------- Bank-link steps (shared by onboarding, help) ---------- */
const BANK_STEPS = [
  ['Dial *99#', `Open your phone dialer, type ${USSD_MENU} and call. Works on Airtel, Vi and BSNL. Jio is not supported.`],
  ['Pick your bank', 'Reply with your bank name or the first 4 letters of its IFSC code, then choose it from the list.'],
  ['Link your account', 'Follow the prompts: enter the last 6 digits of your debit card and its expiry date to verify.'],
  ['Set a UPI PIN', 'Create a 4 or 6 digit UPI PIN if you do not have one. This is the PIN you enter for every *99# payment.'],
];

function Onboarding({ onDone }) {
  const [i, setI] = useState(0);
  const slides = [
    { icon: <Ic.WifiOff width={44} height={44} />, t: 'Pay with no internet', d: 'ZepPay lines up UPI payments over *99#, the service built into your SIM. No Wi-Fi, no mobile data.' },
    { icon: <Ic.Scan width={44} height={44} />, t: 'Scan, check, pay', d: 'Scan any UPI QR or enter a UPI ID or mobile number. ZepPay lines up the payment and your bank completes it over *99#.' },
    { icon: <Ic.Shield width={44} height={44} />, t: 'Your PIN stays with your bank', d: 'ZepPay never sees or stores your UPI PIN. You enter it in the dialer. History stays on this device.' },
    { icon: <Ic.Wallet width={44} height={44} />, t: 'One-time bank link', d: 'To pay over *99#, your SIM must be linked to your bank once.', steps: true },
  ];
  const s = slides[i], last = i === slides.length - 1;
  return (
    <div className="onb">
      <button className="skip" onClick={onDone}>Skip</button>
      <div className="onb-hero"><div className="logo-big"><img src={logo} alt="ZepPay logo" /></div><h1 className="brand">ZepPay</h1><p className="tag">Offline UPI payments</p></div>
      <div className="onb-card" key={i}>
        <div className="onb-ic">{s.icon}</div><h2>{s.t}</h2><p>{s.d}</p>
        {s.steps && <ol className="mini">{BANK_STEPS.map(([t]) => <li key={t}>{t}</li>)}</ol>}
      </div>
      <div className="dots">{slides.map((_, k) => <i key={k} className={k === i ? 'on' : ''} />)}</div>
      <button className="btn primary" onClick={() => (last ? onDone() : setI(i + 1))}>{last ? 'Get started' : 'Next'} <Ic.Arrow width={20} height={20} /></button>
      <p className="fine">Independent project. Your bank completes every payment.</p>
    </div>
  );
}

/* ---------- Home ---------- */
function HomeTab({ supported, carrier, history, go, online, installEvt, onInstall, goTab }) {
  const recents = useMemo(() => {
    const seen = new Map();
    for (const h of history) { const k = h.vpa || h.mobile; if (h.status === 'success' && !seen.has(k)) seen.set(k, h); }
    return [...seen.values()].slice(0, 8);
  }, [history]);
  const recent = history.slice(0, 3);
  return (
    <main className="page home">
      <section className="hdr">
        <div className="hdr-row">
          <div className="brandrow"><span className="logo-sm"><img src={logo} alt="" /></span><b>ZepPay</b></div>
          <span className={'status ' + (online ? '' : 'off')}><i />{online ? 'Online' : 'Offline mode'}</span>
        </div>
        <p className="greet">Pay anyone, even offline</p>
        <button className="searchbar" onClick={() => go({ name: 'payto', kind: 'upi' })} aria-label="Pay to UPI ID"><Ic.User width={20} height={20} /><span>Enter a UPI ID or mobile number</span></button>
      </section>

      <section className="acts">
        <button onClick={() => go({ name: 'scan' })}><span><Ic.Scan /></span>Scan QR</button>
        <button onClick={() => go({ name: 'payto', kind: 'upi' })}><span><Ic.User /></span>UPI ID</button>
        <button onClick={() => go({ name: 'payto', kind: 'mobile' })}><span><Ic.Phone /></span>Mobile no.</button>
        <button onClick={() => go({ name: 'balance' })}><span><Ic.Wallet /></span>Balance</button>
      </section>

      <section className="netcard">
        <span className="netic"><Ic.WifiOff width={22} height={22} /></span>
        <div><b>Works with no data</b><small>Offline payments use *99# on your {CARRIERS.find((c) => c.id === carrier)?.name} SIM. Your bank completes them.</small></div>
      </section>
      {!supported && <div className="warn" role="alert"><Ic.Info width={20} height={20} /><div><b>Jio doesn't support *99#.</b> Payments in ZepPay need a *99# supported SIM.</div></div>}

      {recents.length > 0 && <>
        <h3 className="sec">Pay again</h3>
        <div className="people">{recents.map((r) => (
          <button key={r.vpa || r.mobile} className="person" aria-label={`Pay ${label(r)}`} onClick={() => go({ name: 'pay', payee: { vpa: r.vpa, mobile: r.mobile, name: r.name } })}>
            <Avatar name={label(r)} size={56} /><span>{(r.name || r.vpa || r.mobile).split(/[\s@]/)[0]}</span></button>))}</div>
      </>}

      <div className="sechead"><h3 className="sec">Recent</h3>{history.length > 0 && <button onClick={() => goTab('history')}>See all</button>}</div>
      {recent.length === 0 ? <div className="empty small">No payments yet. Scan a QR or enter a UPI ID to start.</div> : (
        <ul className="list">{recent.map((t) => (
          <li key={t.id}><button onClick={() => go({ name: 'receipt', tx: t })} className="tx">
            <Avatar name={label(t)} />
            <span className="tx-m"><b>{t.name || label(t).split('@')[0]}</b><small>{statusText(t.status)} · {new Date(t.at).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit' })}</small></span>
            <span className={'tx-a ' + t.status}>{formatINR(t.amount)}</span>
          </button></li>))}</ul>
      )}

      <button className="rowbtn guide" onClick={() => go({ name: 'bankguide' })}><Ic.Shield width={20} height={20} /> First time? Link your bank for *99#</button>
      {installEvt && <button className="install" onClick={onInstall}><Ic.Download /> Add ZepPay to home screen</button>}
      <p className="fine">UPI is a trademark of NPCI. ZepPay is an independent project, not a payment service.</p>
    </main>
  );
}
const statusText = (s) => ({ success: 'Paid', failed: 'Failed', unknown: 'Not confirmed' }[s] || s);

/* ---------- Enter UPI ID / mobile ---------- */
function PayTo({ kind: initial, onClose, onNext }) {
  const [kind, setKind] = useState(initial);
  const [val, setVal] = useState('');
  const [name, setName] = useState('');
  const ok = kind === 'upi' ? isValidVpa(val) : isValidMobile(val);
  const submit = (e) => { e.preventDefault(); if (!ok) return; onNext(kind === 'upi' ? { vpa: val.trim(), name: name.trim() } : { mobile: normMobile(val), name: name.trim() }); };
  return (
    <div className="sheet" role="dialog" aria-label="Pay to">
      <header className="sheet-top"><button className="icon-btn" onClick={onClose} aria-label="Back"><Ic.Back /></button><b>Pay to</b><span style={{ width: 40 }} /></header>
      <form className="sheet-body" onSubmit={submit}>
        <div className="seg" role="tablist" aria-label="Payee type">
          <button type="button" role="tab" aria-selected={kind === 'upi'} className={kind === 'upi' ? 'on' : ''} onClick={() => { setKind('upi'); setVal(''); }}>UPI ID</button>
          <button type="button" role="tab" aria-selected={kind === 'mobile'} className={kind === 'mobile' ? 'on' : ''} onClick={() => { setKind('mobile'); setVal(''); }}>Mobile number</button>
        </div>
        <input className="field" autoFocus aria-label={kind === 'upi' ? 'UPI ID' : 'Mobile number'} placeholder={kind === 'upi' ? 'name@bank' : '10-digit mobile number'} inputMode={kind === 'upi' ? 'email' : 'numeric'} value={val} onChange={(e) => setVal(e.target.value)} autoCapitalize="off" autoCorrect="off" spellCheck="false" />
        {val && !ok && <p className="err">{kind === 'upi' ? 'Use the format name@bank.' : 'Enter a valid 10-digit Indian mobile number.'}</p>}
        <input className="field" aria-label="Payee name (optional)" placeholder="Name (optional, for your history)" maxLength={40} value={name} onChange={(e) => setName(e.target.value)} />
        {kind === 'mobile' && <p className="fine left">Mobile payments go through *99# only. The number must be linked to a UPI account.</p>}
        <button className="btn primary wide" disabled={!ok} style={{ marginTop: 12 }}>Continue <Ic.Arrow width={20} height={20} /></button>
      </form>
    </div>
  );
}

/* ---------- Scanner ---------- */
function Scanner({ onClose, onResult, showToast }) {
  const video = useRef(); const canvas = useRef(); const fileRef = useRef();
  const [err, setErr] = useState(''); const [torch, setTorch] = useState(false); const [paste, setPaste] = useState('');
  const streamRef = useRef();
  const handleText = useCallback((text) => {
    const p = parseUpi(text);
    if (!p) { showToast('Not a UPI QR'); return false; }
    onResult(p); return true;
  }, [onResult, showToast]);

  useEffect(() => {
    let raf, alive = true;
    (async () => {
      if (!navigator.mediaDevices?.getUserMedia) { setErr('Camera not available here. Upload a QR image or paste a UPI ID below.'); return; }
      try {
        const s = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false });
        if (!alive) { s.getTracks().forEach((t) => t.stop()); return; }
        streamRef.current = s; video.current.srcObject = s; await video.current.play();
        const tick = () => {
          const v = video.current;
          if (v && v.readyState === 4 && v.videoWidth) {
            const c = canvas.current; c.width = v.videoWidth; c.height = v.videoHeight;
            const g = c.getContext('2d', { willReadFrequently: true }); g.drawImage(v, 0, 0);
            const d = g.getImageData(0, 0, c.width, c.height);
            const r = jsQR(d.data, d.width, d.height, { inversionAttempts: 'dontInvert' });
            if (r?.data) { const p = parseUpi(r.data); if (p) { navigator.vibrate?.(30); onResult(p); return; } }
          }
          raf = requestAnimationFrame(tick);
        };
        tick();
      } catch { setErr('Camera permission needed. You can upload a QR image or paste a UPI ID instead.'); }
    })();
    return () => { alive = false; cancelAnimationFrame(raf); streamRef.current?.getTracks().forEach((t) => t.stop()); };
  }, [onResult]);

  const fromFile = (f) => {
    if (!f) return;
    const img = new Image();
    img.onload = () => {
      const c = document.createElement('canvas'); const k = Math.min(1, 1400 / Math.max(img.width, img.height));
      c.width = img.width * k; c.height = img.height * k;
      const g = c.getContext('2d'); g.drawImage(img, 0, 0, c.width, c.height);
      const d = g.getImageData(0, 0, c.width, c.height);
      const r = jsQR(d.data, d.width, d.height, { inversionAttempts: 'attemptBoth' });
      if (!r) showToast('No QR found in that image'); else handleText(r.data);
    };
    img.onerror = () => showToast('Could not read that image');
    img.src = URL.createObjectURL(f);
  };
  const toggleTorch = async () => {
    const t = streamRef.current?.getVideoTracks()[0];
    try { await t.applyConstraints({ advanced: [{ torch: !torch }] }); setTorch(!torch); } catch { showToast('Torch not supported'); }
  };
  return (
    <div className="sheet scan" role="dialog" aria-label="Scan QR">
      <video ref={video} playsInline muted className="cam" />
      <canvas ref={canvas} hidden />
      <div className="scrim" />
      <header className="sheet-top light"><button className="icon-btn" onClick={onClose} aria-label="Close"><Ic.Close /></button><b>Scan UPI QR</b><button className="icon-btn" onClick={toggleTorch} aria-label="Torch"><Ic.Flash style={{ opacity: torch ? 1 : 0.6 }} /></button></header>
      <div className="frame"><i /><i /><i /><i /><span className="laser" /></div>
      {err && <p className="cam-err">{err}</p>}
      <div className="scan-bottom">
        <input ref={fileRef} type="file" accept="image/*" hidden data-testid="qr-file" onChange={(e) => { fromFile(e.target.files[0]); e.target.value = ''; }} />
        <button className="btn glass" onClick={() => fileRef.current.click()}><Ic.Image width={20} height={20} /> Upload QR image</button>
        <form className="row-input dark" onSubmit={(e) => { e.preventDefault(); if (!handleText(paste)) setPaste(''); }}>
          <input aria-label="Paste UPI ID or link" placeholder="Paste UPI ID or upi:// link" value={paste} onChange={(e) => setPaste(e.target.value)} autoCapitalize="off" spellCheck="false" />
          <button className="btn primary sm" disabled={!paste.trim()} aria-label="Use"><Ic.Arrow width={20} height={20} /></button>
        </form>
      </div>
    </div>
  );
}

/* ---------- Pay form ---------- */
function PayScreen({ payee, supported, onClose, onGo }) {
  const isMobile = !payee.vpa;
  const [amount, setAmount] = useState(payee.amount || '');
  const [note, setNote] = useState(payee.note || '');
  const [confirmed, setConfirmed] = useState(false);
  const err = validateAmount(amount);
  const base = { id: '', vpa: payee.vpa, mobile: payee.mobile, name: payee.name || '', amount: Number(amount), note };
  const start = (route) => onGo({ ...base, id: newId(), route }, route);
  const dis = !!err || !confirmed;
  return (
    <div className="sheet" role="dialog" aria-label="Pay">
      <header className="sheet-top"><button className="icon-btn" onClick={onClose} aria-label="Back"><Ic.Back /></button><b>Pay</b><span style={{ width: 40 }} /></header>
      <div className="sheet-body">
        <div className="payee"><Avatar name={label(payee)} size={56} />
          <div><div className="pname">{payee.name || (isMobile ? 'Mobile number' : 'Unnamed payee')}</div><div className="pvpa" data-testid="payee-vpa">{payee.vpa || payee.mobile}</div></div></div>
        <label className="amt">
          <span className="rs">₹</span>
          <input autoFocus inputMode="decimal" aria-label="Amount" placeholder="0" value={amount}
            onChange={(e) => { const v = e.target.value.replace(/[^0-9.]/g, ''); if ((v.match(/\./g) || []).length < 2) setAmount(v); }} />
        </label>
        {amount && err && <p className="err center">{err}</p>}
        <div className="limit">*99# allows ₹1 to ₹{MAX_AMOUNT.toLocaleString('en-IN')} per payment</div>
        <div className="quick">{[100, 500, 1000, 2000].map((v) => <button key={v} onClick={() => setAmount(String(v))}>₹{v.toLocaleString('en-IN')}</button>)}</div>
        {!isMobile && <input className="field" aria-label="Note" placeholder="Add a note (optional)" maxLength={50} value={note} onChange={(e) => setNote(e.target.value)} />}
        <label className="check"><input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} /><span>I've checked this is the right person to pay.</span></label>
        <p className="fine left">Payments run only over *99#. Your bank asks for your UPI PIN in the dialer. ZepPay never sees it.</p>
      </div>
      <div className="sheet-foot col">
        <button className="btn primary wide" disabled={dis || !supported} onClick={() => start('ussd')}><Ic.WifiOff width={20} height={20} /> Pay {!err && amount ? formatINR(amount) : ''} with *99#</button>
        {!supported && <p className="err center">Jio SIMs can't use *99#.</p>}
      </div>
    </div>
  );
}


// A real <a href="tel:..."> so iOS treats the tap as a user-initiated navigation. `before` must be synchronous.
function TelLink({ code, before, className, children, ...rest }) {
  const href = telHref(code);
  return <a href={href} className={className} onClick={(e) => { try { before && before(); } catch { /* ignore */ } if (typeof window.__zepNavigate === 'function') { e.preventDefault(); window.__zepNavigate(href); } }} {...rest}>{children}</a>;
}

/* ---------- Handoff to dialer ---------- */
function Handoff({ tx, route, showToast, onClose, onBack, onLaunch, onPick }) {
  const isApp = false;
  const ios = isIOS();
  const full = tx.vpa ? USSD_SEND : USSD_MOBILE;
  const code = ios ? full + '#' : full;
  const menuKey = tx.vpa ? '3' : '1';
  const target = tx.vpa || tx.mobile;
  const before = () => { onLaunch(); copyText(target).then((okc) => { if (okc) showToast(tx.vpa ? 'UPI ID copied' : 'Number copied'); }); };
  return (
    <div className="sheet session" role="dialog" aria-label="Complete payment">
      <header className="sheet-top"><button className="icon-btn" onClick={onBack} aria-label="Back"><Ic.Back /></button><b>Pay via *99#</b><span style={{ width: 40 }} /></header>
      <div className="sheet-body center">
        <div className="amt-show"><small>to {tx.name || target}</small><div>{formatINR(tx.amount)}</div></div>
        <div className="livebox"><Ic.Phone width={34} height={34} />
            <h2>Finish in your dialer</h2>
            {ios
              ? <p>ZepPay copies {tx.vpa ? 'the UPI ID' : 'the number'} and opens <code>{code}</code> (iPhone needs the extra #). Paste it, enter <b>{formatINR(tx.amount)}</b>, then your UPI PIN. If iOS blocks it, use the menu button below and reply <b>1</b>, then <b>{menuKey}</b>.</p>
              : <p>ZepPay copies {tx.vpa ? 'the UPI ID' : 'the number'} and opens <code>{code}</code>. In the bank prompts, paste it, enter <b>{formatINR(tx.amount)}</b>, then your UPI PIN. No internet needed.</p>}
        </div>
        <TelLink code={code} before={before} className="btn primary wide" data-testid="open-handoff"><Ic.Phone width={20} height={20} /> Open dialer</TelLink>
        {ios && <TelLink code={USSD_MENU} before={before} className="btn ghost wide" data-testid="open-menu"><Ic.Phone width={20} height={20} /> Open {USSD_MENU} menu instead</TelLink>}
        {ios && <button className="btn ghost wide" data-testid="copy-code" onClick={async () => showToast((await copyText(full)) ? 'Code copied' : 'Copy not available')}><Ic.Copy width={20} height={20} /> Copy {full} to paste in the dialer</button>}
        {<button className="btn ghost wide" onClick={async () => showToast((await copyText(target)) ? 'Copied' : 'Copy not available')}><Ic.Copy width={20} height={20} /> Copy {target}</button>}
        <p className="fine">When you come back, ZepPay asks if it worked so your history is accurate. Your bank sends the real confirmation.</p>
      </div>
      <div className="sheet-foot col"><button className="btn ghost wide" data-testid="mark-success" onClick={() => onPick('success')}>It worked</button>
        <button className="btn ghost wide" onClick={() => onPick('failed')}>It didn't work</button>
        <button className="btn text wide" onClick={onClose}>Cancel</button></div>
    </div>
  );
}
function Outcome({ tx, onPick, onLater }) {
  return (
    <div className="sheet" role="dialog" aria-label="Payment outcome">
      <header className="sheet-top"><span style={{ width: 40 }} /><b>Did it go through?</b><button className="icon-btn" onClick={onLater} aria-label="Later"><Ic.Close /></button></header>
      <div className="sheet-body center">
        <div className="amt-show"><small>{formatINR(tx.amount)} to</small><div style={{ fontSize: 26 }}>{tx.name || tx.vpa || tx.mobile}</div></div>
        <p className="fine">ZepPay can't read your bank's reply. Check your bank SMS, then tell us so your history is right.</p>
      </div>
      <div className="sheet-foot col">
        <button className="btn primary wide" onClick={() => onPick('success')}>Yes, payment went through</button>
        <button className="btn ghost wide" onClick={() => onPick('failed')}>No, it failed</button>
        <button className="btn text wide" onClick={() => onPick('unknown')}>Not sure yet</button>
      </div>
    </div>
  );
}

/* ---------- Balance ---------- */
function BalanceScreen({ supported, onClose, showToast }) {
  return (
    <div className="sheet" role="dialog" aria-label="Check balance">
      <header className="sheet-top"><button className="icon-btn" onClick={onClose} aria-label="Close"><Ic.Close /></button><b>Check balance</b><span style={{ width: 40 }} /></header>
      <div className="sheet-body center">
        <div className="livebox"><Ic.Wallet width={34} height={34} /><h2>Balance over *99#</h2>
          <p>ZepPay opens <code>{isIOS() ? USSD_BALANCE + '#' : USSD_BALANCE}</code> in your dialer.{isIOS() ? ' iPhone needs the extra #.' : ''} Pick your bank account and enter your UPI PIN. Your bank shows the balance. ZepPay can't read or store it.</p></div>
        {supported
          ? <TelLink code={isIOS() ? USSD_BALANCE + '#' : USSD_BALANCE} className="btn primary wide" data-testid="balance-dial"><Ic.Phone width={20} height={20} /> Open dialer</TelLink>
          : <button className="btn primary wide" disabled data-testid="balance-dial"><Ic.Phone width={20} height={20} /> Open dialer</button>}
        {isIOS() && supported && <TelLink code={USSD_MENU} className="btn ghost wide"><Ic.Phone width={20} height={20} /> Open {USSD_MENU} menu instead</TelLink>}
        {!supported && <p className="err center">Jio SIMs can't use *99#.</p>}
        <p className="fine">Not linked yet? Do the one-time bank link first (Home, then "First time?").</p>
      </div>
    </div>
  );
}

/* ---------- Receipt ---------- */
function Receipt({ tx, onClose, onAgain }) {
  const ok = tx.status === 'success';
  const when = new Date(tx.at).toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });
  const share = async () => {
    const text = `ZepPay: ${formatINR(tx.amount)} to ${tx.name || tx.vpa || tx.mobile} on ${when} (${statusText(tx.status)}, as confirmed by me)`;
    try { if (navigator.share) await navigator.share({ text }); else await navigator.clipboard.writeText(text); } catch { /* cancelled */ }
  };
  return (
    <div className="sheet" role="dialog" aria-label="Receipt">
      <header className="sheet-top"><button className="icon-btn" onClick={onClose} aria-label="Close"><Ic.Close /></button><b>Payment</b><button className="icon-btn" onClick={share} aria-label="Share"><Ic.Share /></button></header>
      <div className="sheet-body center">
        <div className={'outcome ' + (ok ? 'ok' : tx.status === 'failed' ? 'bad' : 'idle')}><div className="burst sm">{ok ? <Ic.Check width={34} height={34} strokeWidth={3} /> : tx.status === 'failed' ? <Ic.Close width={34} height={34} strokeWidth={3} /> : <Ic.Clock width={34} height={34} />}</div>
          <div className="bal-big">{formatINR(tx.amount)}</div><p>{ok ? 'Paid to' : tx.status === 'failed' ? 'Failed to pay' : 'Not confirmed, to'} <b>{tx.name || tx.vpa || tx.mobile}</b></p></div>
        <dl className="kv">
          <dt>{tx.vpa ? 'UPI ID' : 'Mobile'}</dt><dd>{tx.vpa || tx.mobile}</dd><dt>Date</dt><dd>{when}</dd>
          <dt>Paid via</dt><dd>*99#</dd>
          {tx.note && <><dt>Note</dt><dd>{tx.note}</dd></>}
        </dl>
        <p className="fine">Status is what you told ZepPay. Your bank SMS is the official record.</p>
      </div>
      <div className="sheet-foot"><button className="btn primary wide" onClick={onAgain}><Ic.Retry width={20} height={20} /> Pay again</button></div>
    </div>
  );
}

/* ---------- History ---------- */
function HistoryTab({ history, go }) {
  const groups = useMemo(() => groupByDay(history), [history]);
  return (
    <main className="page">
      <header className="top"><h1 className="title">History</h1></header>
      {history.length === 0 ? <div className="empty"><Ic.Clock width={40} height={40} /><p>No payments yet</p><small>Your last 200 payments are kept on this device only.</small></div> :
        groups.map((g) => (
          <section key={g.label}><h3 className="sec">{g.label}</h3>
            <ul className="list">{g.items.map((t) => (
              <li key={t.id}><button onClick={() => go({ name: 'receipt', tx: t })} className="tx">
                <Avatar name={label(t)} />
                <span className="tx-m"><b>{t.name || label(t).split('@')[0]}</b><small>{new Date(t.at).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit' })} · *99#</small></span>
                <span className={'tx-a ' + t.status}>{formatINR(t.amount)}<small>{statusText(t.status)}</small></span>
              </button></li>))}</ul></section>))}
    </main>
  );
}

/* ---------- Bank guide + Help ---------- */
function BankGuide({ onClose }) {
  return (
    <div className="sheet" role="dialog" aria-label="Link your bank">
      <header className="sheet-top"><button className="icon-btn" onClick={onClose} aria-label="Close"><Ic.Close /></button><b>Link your bank</b><span style={{ width: 40 }} /></header>
      <div className="sheet-body">
        <p className="fine left" style={{ marginTop: 0 }}>Do this once per SIM. After that ZepPay handles the rest.</p>
        <ol className="steps">{BANK_STEPS.map(([t, d]) => <li key={t}><span><b>{t}.</b> {d}</span></li>)}</ol>
        <TelLink code={USSD_MENU} className="btn primary wide" style={{ marginTop: 16 }}><Ic.Phone width={20} height={20} /> Dial {USSD_MENU} now</TelLink>
      </div>
    </div>
  );
}
const FAQ = [
  ['Does ZepPay move money itself?', 'No. ZepPay lines up the payment. Your bank completes it, through *99# on your SIM. ZepPay never sees your UPI PIN.'],
  ['Which SIMs work?', 'Airtel, Vi and BSNL support *99#. Jio does not, so ZepPay cannot pay on a Jio SIM.'],
  ['What are the limits?', '*99# allows ₹1 to ₹5,000 per payment, and a daily cap set by your bank. '],
  ['Why does it ask if my payment worked?', 'A web app cannot read the bank reply from the dialer. You tell ZepPay, and it saves that to your history on this device.'],
  ['Can I pay a mobile number?', 'Yes, it uses the *99# "send to mobile number" option. The number must have a UPI account.'],
  ['Does it work without internet?', 'Yes. After the first load the app opens offline. Offline payments run through your SIM and need voice signal, not data.'],
  ['Where is my data?', 'Only on this device. No accounts, no analytics, no servers. Clearing site data erases your history.'],
];
function HelpTab({ go }) {
  const [open, setOpen] = useState(0);
  return (
    <main className="page">
      <header className="top"><h1 className="title">Help</h1></header>
      <h3 className="sec">How to pay</h3>
      <ol className="steps">
        <li><span><b>Link your bank once.</b> Dial <code>{USSD_MENU}</code> and follow the prompts.</span></li>
        <li><span><b>Scan a UPI QR</b> or enter a UPI ID or mobile number.</span></li>
        <li><span><b>Check the payee and amount.</b> ZepPay opens <code>*99#</code> in your dialer.</span></li>
        <li><span><b>Enter your UPI PIN</b> in the bank prompt. Come back and confirm.</span></li>
      </ol>
      <button className="rowbtn guide" onClick={() => go({ name: 'bankguide' })}><Ic.Shield width={20} height={20} /> Bank-link walkthrough</button>
      <h3 className="sec">FAQ</h3>
      <div className="faq">{FAQ.map(([q, a], i) => (
        <div key={q} className={'qa' + (open === i ? ' open' : '')}><button aria-expanded={open === i} onClick={() => setOpen(open === i ? -1 : i)}>{q}<Ic.Arrow width={18} height={18} /></button>{open === i && <p>{a}</p>}</div>))}</div>
      <p className="fine">Not for vending machines or merchants that need an order ID. *99# can't send one.</p>
    </main>
  );
}

/* ---------- Settings ---------- */
function SettingsTab({ theme, setTheme, carrier, setCarrier, onClear, onReplay, history, installEvt, onInstall, go }) {
  return (
    <main className="page">
      <header className="top"><h1 className="title">Settings</h1></header>
      <h3 className="sec">Appearance</h3>
      <div className="seg" role="radiogroup" aria-label="Theme">
        {[['auto', 'Auto', <Ic.Auto width={18} height={18} key="a" />], ['light', 'Light', <Ic.Sun width={18} height={18} key="l" />], ['dark', 'Dark', <Ic.Moon width={18} height={18} key="d" />]].map(([k, l, ic]) => (
          <button key={k} role="radio" aria-checked={theme === k} className={theme === k ? 'on' : ''} onClick={() => setTheme(k)}>{ic}{l}</button>))}
      </div>
      <h3 className="sec">Your SIM carrier</h3>
      <div className="chips">{CARRIERS.map((c) => <button key={c.id} className={'chipbtn' + (carrier === c.id ? ' on' : '')} onClick={() => setCarrier(c.id)}>{c.name}</button>)}</div>
      {!carrierSupported(carrier) && <p className="err">Jio doesn't support *99#. Offline payments are turned off.</p>}
      <h3 className="sec">App</h3>
      <div className="card-list">
        {installEvt ? <button className="rowbtn" onClick={onInstall}><Ic.Download width={18} height={18} /> Install ZepPay</button> : <p className="fine left" style={{ margin: 0 }}>To install: use your browser menu, "Add to Home Screen".</p>}
        <button className="rowbtn" onClick={() => go({ name: 'bankguide' })}>Bank-link walkthrough</button>
        <button className="rowbtn" onClick={onReplay}>Replay intro</button>
        <button className="rowbtn danger" onClick={onClear} disabled={!history.length}><Ic.Trash width={18} height={18} /> Clear payment history</button>
      </div>
      <p className="fine left">ZepPay stores history on this device only. No analytics, no accounts, no servers. Independent project, not a registered payment service. UPI is a trademark of NPCI.</p>
      <p className="fine left">v2.0.0</p>
    </main>
  );
}
