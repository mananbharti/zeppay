import { describe, it, expect } from 'vitest';
import { parseUpi, isValidVpa, validateAmount, formatINR, telHref, carrierSupported, isValidMobile, normMobile, newId, USSD_MOBILE, upiLink, groupByDay, initials, USSD_SEND } from './lib.js';

describe('vpa', () => {
  it('accepts valid', () => { expect(isValidVpa('ravi.k@okaxis')).toBe(true); expect(isValidVpa('9876543210@ybl')).toBe(true); });
  it('rejects invalid', () => { for (const v of ['', 'abc', 'a@b', '@ybl', 'x y@ybl', null]) expect(isValidVpa(v)).toBe(false); });
});
describe('parseUpi', () => {
  it('parses full link', () => {
    expect(parseUpi('upi://pay?pa=shop@okicici&pn=Chai%20Point&am=120.50&tn=tea&cu=INR')).toEqual({ vpa: 'shop@okicici', name: 'Chai Point', amount: '120.5', note: 'tea' });
  });
  it('parses bare vpa', () => { expect(parseUpi(' me@ybl ').vpa).toBe('me@ybl'); });
  it('rejects junk and bad pa', () => {
    expect(parseUpi('https://example.com')).toBeNull();
    expect(parseUpi('upi://pay?pn=x')).toBeNull();
    expect(parseUpi('upi://pay?pa=bad')).toBeNull();
    expect(parseUpi('')).toBeNull();
    expect(parseUpi(undefined)).toBeNull();
  });
  it('drops zero/negative amount', () => { expect(parseUpi('upi://pay?pa=a1@ybl&am=0').amount).toBe(''); });
});
describe('amount', () => {
  it('bounds', () => {
    expect(validateAmount('')).toMatch(/Enter/);
    expect(validateAmount('0')).toMatch(/Minimum/);
    expect(validateAmount('5001')).toMatch(/5,000/);
    expect(validateAmount('10.999')).toMatch(/decimals/);
    expect(validateAmount('1')).toBe('');
    expect(validateAmount('5000')).toBe('');
    expect(validateAmount('99.5')).toBe('');
  });
});
describe('misc', () => {
  it('formatINR', () => { expect(formatINR(1234567)).toBe('₹12,34,567'); expect(formatINR(10.5)).toBe('₹10.50'); });
  it('telHref encodes #', () => { expect(telHref(USSD_SEND)).toBe('tel:*99*1*3%23'); });
  it('carrier', () => { expect(carrierSupported('jio')).toBe(false); expect(carrierSupported('airtel')).toBe(true); });
  it('ids differ', () => { expect(newId(1, () => 0.1)).not.toBe(newId(1, () => 0.2)); });
  it('mobile', () => { expect(isValidMobile('98765 43210')).toBe(true); expect(isValidMobile('+919876543210')).toBe(true); expect(isValidMobile('12345')).toBe(false); expect(isValidMobile('5876543210')).toBe(false); expect(normMobile('+91 98765-43210')).toBe('9876543210'); expect(USSD_MOBILE).toBe('*99*1*1#'); });
  it('upiLink roundtrip', () => { const o = { vpa: 'a1@ybl', name: 'A B', amount: '10', note: 'hi' }; expect(parseUpi(upiLink(o))).toEqual(o); });
  it('initials', () => { expect(initials('Ravi Kumar')).toBe('RK'); expect(initials('')).toBe('?'); });
  it('groupByDay', () => {
    const now = Date.now();
    const g = groupByDay([{ at: now }, { at: now - 1000 }, { at: now - 864e5 }], now);
    expect(g.map((x) => x.label)).toEqual(['Today', 'Yesterday']); expect(g[0].items.length).toBe(2);
  });
});
