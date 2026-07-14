---
title: NorTools
tagline: Use the desktop UI for guided workflows, or the executable for fast repeatable diagnostics.

features:
  - title: Command Line
    details: Examples use the released executable form, such as nortools mx example.com.
  - title: Desktop UI
    details: UI docs show where to click, what each field means, and which CLI command matches the workflow.
  - title: Engineer Reference
    details: Protocol notes, JSON output, RFC context, and edge cases are kept close to beginner explanations.
---

## Start With A Task

<div class="tool-grid">
  <a class="tool-card" href="tools/dns/"><strong>Check DNS records</strong><p>A, AAAA, MX, TXT, CNAME, NS, SOA, SRV, reverse DNS, and propagation.</p></a>
  <a class="tool-card" href="tools/email/"><strong>Debug email delivery</strong><p>SPF, DKIM, DMARC, BIMI, SMTP, MTA-STS, TLS reporting, and headers.</p></a>
  <a class="tool-card" href="tools/network/"><strong>Inspect a network endpoint</strong><p>HTTP, HTTPS/TLS, TCP connectivity, ping, traceroute, and throughput diagnostics.</p></a>
  <a class="tool-card" href="tools/zeroconf/"><strong>Discover local devices</strong><p>mDNS, DNS-SD, SSDP, WS-Discovery, LLMNR, NetBIOS, and Samba browsing.</p></a>
  <a class="tool-card" href="tools/utility/"><strong>Use offline utilities</strong><p>Subnet calculator, password generator, email extractor, and public IP detection.</p></a>
  <a class="tool-card" href="tools/composite/"><strong>Run full reports</strong><p>Domain health, DNS health, mail flow, deliverability, compliance, and bulk lookup.</p></a>
</div>

## One Tool, Two Interfaces

Most NorTools features are available in both the desktop/web UI and the executable.

```bash
nortools mx example.com
nortools mx --json example.com
```

The UI version is useful when you want explanations and structured views. The CLI is useful for repeatable diagnostics, scripts, incident notes, and JSON output.
