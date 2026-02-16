const API_BASE = '/api';

async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(`${API_BASE}${url}`);
  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

async function postText<T>(url: string, body: string): Promise<T> {
  const response = await fetch(`${API_BASE}${url}`, { method: 'POST', body });
  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

// DNS tools
export async function dnsLookup(type: string, domain: string, server?: string) {
  const qs = server ? `?server=${encodeURIComponent(server)}` : '';
  return fetchJson(`/dns/${type}/${domain}${qs}`);
}

export async function dnssecLookup(type: string, domain: string) {
  return fetchJson(`/dnssec/${type}/${domain}`);
}

export async function dnssecChain(domain: string) {
  return fetchJson(`/dnssec-chain/${domain}`);
}

export async function reverseLookup(ip: string) {
  return fetchJson(`/reverse/${ip}`);
}

// Email auth tools
export async function spfLookup(domain: string) {
  return fetchJson(`/spf/${domain}`);
}

export async function dkimLookup(selector: string, domain: string) {
  return fetchJson(`/dkim/${selector}/${domain}`);
}

export async function dkimDiscover(domain: string) {
  return fetchJson(`/dkim-discover/${domain}`);
}

export async function dmarcLookup(domain: string) {
  return fetchJson(`/dmarc/${domain}`);
}

// Network tools
export async function tcpCheck(host: string, port: number) {
  return fetchJson(`/tcp/${host}/${port}`);
}

export async function httpCheck(url: string) {
  return fetchJson(`/http/${encodeURIComponent(url)}`);
}

export async function httpsCheck(host: string) {
  return fetchJson(`/https/${host}`);
}

export async function pingCheck(host: string, count: number = 4) {
  return fetchJson(`/ping/${host}?count=${count}`);
}

export async function traceroute(host: string, lookupMode: 'geo' | 'asn-country' = 'geo') {
  return fetchJson(`/trace-visual/${host}?lookupMode=${encodeURIComponent(lookupMode)}`);
}

// WHOIS tools
export async function whoisLookup(query: string) {
  return fetchJson(`/whois/${query}`);
}

// Blocklist tools
export async function blacklistCheck(ip: string) {
  return fetchJson(`/blacklist/${ip}`);
}

// Utility tools
export async function whatIsMyIp() {
  return fetchJson(`/whatismyip`);
}

export async function subnetCalc(cidr: string) {
  return fetchJson(`/subnet/${encodeURIComponent(cidr)}`);
}

export async function passwordGen(opts: {
  length?: number; count?: number;
  upper?: boolean; lower?: boolean; digits?: boolean; special?: boolean;
} = {}) {
  const p = new URLSearchParams();
  if (opts.length != null) p.set('length', String(opts.length));
  if (opts.count != null) p.set('count', String(opts.count));
  if (opts.upper != null) p.set('upper', String(opts.upper));
  if (opts.lower != null) p.set('lower', String(opts.lower));
  if (opts.digits != null) p.set('digits', String(opts.digits));
  if (opts.special != null) p.set('special', String(opts.special));
  return fetchJson(`/password?${p}`);
}

export async function emailExtract(text: string) {
  return postText(`/email-extract`, text);
}

// Composite tools
export async function dnsHealthCheck(domain: string) {
  return fetchJson(`/dns-health/${domain}`);
}

export async function domainHealth(domain: string) {
  return fetchJson(`/domain-health/${domain}`);
}

// Generator tools
export async function spfGenerator(opts: {
  includes?: string; ip4?: string; ip6?: string;
  mx?: boolean; a?: boolean; all?: string;
}) {
  const p = new URLSearchParams();
  if (opts.includes) p.set('includes', opts.includes);
  if (opts.ip4) p.set('ip4', opts.ip4);
  if (opts.ip6) p.set('ip6', opts.ip6);
  if (opts.mx != null) p.set('mx', String(opts.mx));
  if (opts.a != null) p.set('a', String(opts.a));
  if (opts.all) p.set('all', opts.all);
  return fetchJson(`/spf-generator?${p}`);
}

export async function dmarcGenerator(opts: {
  policy?: string; sp?: string; pct?: number;
  rua?: string; ruf?: string; adkim?: string; aspf?: string;
}) {
  const p = new URLSearchParams();
  if (opts.policy) p.set('policy', opts.policy);
  if (opts.sp) p.set('sp', opts.sp);
  if (opts.pct != null && opts.pct !== 100) p.set('pct', String(opts.pct));
  if (opts.rua) p.set('rua', opts.rua);
  if (opts.ruf) p.set('ruf', opts.ruf);
  if (opts.adkim) p.set('adkim', opts.adkim);
  if (opts.aspf) p.set('aspf', opts.aspf);
  return fetchJson(`/dmarc-generator?${p}`);
}

// About
export async function aboutInfo() {
  return fetchJson('/about');
}
