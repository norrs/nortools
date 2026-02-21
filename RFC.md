# nortools — RFC & Standards Reference

This document lists all tools in the nortools suite grouped by category, along with the RFCs and standards each tool implements or relies upon.

---

## DNS Lookup Tools

| Tool | Description | Relevant RFCs / Standards |
|------|-------------|--------------------------|
| **a** | A record lookup (IPv4) | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Domain Names (Implementation & Specification) |
| **aaaa** | AAAA record lookup (IPv6) | [RFC 3596](https://datatracker.ietf.org/doc/html/rfc3596) — DNS Extensions to Support IPv6 |
| **cname** | CNAME record lookup | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Domain Names |
| **mx** | MX record lookup | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Domain Names, [RFC 7505](https://datatracker.ietf.org/doc/html/rfc7505) — Null MX |
| **ns** | NS record lookup | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Domain Names |
| **ptr** | PTR record lookup (reverse DNS) | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Domain Names |
| **soa** | SOA record lookup | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Domain Names |
| **srv** | SRV record lookup | [RFC 2782](https://datatracker.ietf.org/doc/html/rfc2782) — DNS SRV Resource Records |
| **txt** | TXT record lookup | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Domain Names |

---

## DNSSEC Tools

| Tool | Description | Relevant RFCs / Standards |
|------|-------------|--------------------------|
| **dnskey** | DNSKEY record lookup | [RFC 4034](https://datatracker.ietf.org/doc/html/rfc4034) — Resource Records for DNSSEC |
| **ds** | DS record lookup | [RFC 4034](https://datatracker.ietf.org/doc/html/rfc4034) — Resource Records for DNSSEC |
| **rrsig** | RRSIG record lookup | [RFC 4034](https://datatracker.ietf.org/doc/html/rfc4034) — Resource Records for DNSSEC |
| **nsec** | NSEC record lookup | [RFC 4034](https://datatracker.ietf.org/doc/html/rfc4034) — Resource Records for DNSSEC |
| **nsec3param** | NSEC3PARAM record lookup | [RFC 5155](https://datatracker.ietf.org/doc/html/rfc5155) — NSEC3 Hashed Authenticated Denial of Existence |
| **dnssec-chain** *(web)* | DNSSEC authentication chain visualization | [RFC 4033](https://datatracker.ietf.org/doc/html/rfc4033) — DNS Security Introduction, [RFC 4034](https://datatracker.ietf.org/doc/html/rfc4034) — Resource Records for DNSSEC, [RFC 4035](https://datatracker.ietf.org/doc/html/rfc4035) — Protocol Modifications for DNSSEC, [RFC 5155](https://datatracker.ietf.org/doc/html/rfc5155) — NSEC3, [RFC 6781](https://datatracker.ietf.org/doc/html/rfc6781) — DNSSEC Operational Practices |

All DNSSEC tools share the core DNSSEC RFCs:
- [RFC 4033](https://datatracker.ietf.org/doc/html/rfc4033) — DNS Security Introduction and Requirements
- [RFC 4034](https://datatracker.ietf.org/doc/html/rfc4034) — Resource Records for the DNS Security Extensions
- [RFC 4035](https://datatracker.ietf.org/doc/html/rfc4035) — Protocol Modifications for the DNS Security Extensions

---

## Email Authentication Tools

| Tool | Description | Relevant RFCs / Standards |
|------|-------------|--------------------------|
| **spf** | SPF record lookup & validation | [RFC 7208](https://datatracker.ietf.org/doc/html/rfc7208) — Sender Policy Framework (SPF) |
| **dkim** | DKIM record lookup | [RFC 6376](https://datatracker.ietf.org/doc/html/rfc6376) — DomainKeys Identified Mail (DKIM) Signatures |
| **dmarc** | DMARC record lookup | [RFC 7489](https://datatracker.ietf.org/doc/html/rfc7489) — Domain-based Message Authentication, Reporting, and Conformance (DMARC) |
| **bimi** | BIMI record lookup | [BIMI Group Specification](https://bimigroup.org/implementation-guide/) (draft-brand-indicators-for-message-identification) |
| **spf-generator** | SPF record generator | [RFC 7208](https://datatracker.ietf.org/doc/html/rfc7208) — Sender Policy Framework (SPF) |
| **dmarc-generator** | DMARC record generator | [RFC 7489](https://datatracker.ietf.org/doc/html/rfc7489) — DMARC |

---

## Email Infrastructure Tools

| Tool | Description | Relevant RFCs / Standards |
|------|-------------|--------------------------|
| **smtp** | SMTP server test & STARTTLS check | [RFC 5321](https://datatracker.ietf.org/doc/html/rfc5321) — Simple Mail Transfer Protocol, [RFC 3207](https://datatracker.ietf.org/doc/html/rfc3207) — SMTP STARTTLS Extension, [RFC 8314](https://datatracker.ietf.org/doc/html/rfc8314) — Cleartext Considered Obsolete (Implicit TLS) |
| **mta-sts** | MTA-STS record & policy lookup | [RFC 8461](https://datatracker.ietf.org/doc/html/rfc8461) — SMTP MTA Strict Transport Security (MTA-STS) |
| **tlsrpt** | SMTP TLS Reporting record lookup | [RFC 8460](https://datatracker.ietf.org/doc/html/rfc8460) — SMTP TLS Reporting |
| **header-analyzer** | Email header parser & analysis | [RFC 5322](https://datatracker.ietf.org/doc/html/rfc5322) — Internet Message Format, [RFC 2822](https://datatracker.ietf.org/doc/html/rfc2822) — Internet Message Format (obsoleted by 5322) |

---

## Network Tools

| Tool | Description | Relevant RFCs / Standards |
|------|-------------|--------------------------|
| **tcp** | TCP port connectivity check | [RFC 793](https://datatracker.ietf.org/doc/html/rfc793) — Transmission Control Protocol |
| **http** | HTTP check | [RFC 9110](https://datatracker.ietf.org/doc/html/rfc9110) — HTTP Semantics, [RFC 9112](https://datatracker.ietf.org/doc/html/rfc9112) — HTTP/1.1 |
| **https** | HTTPS/TLS/SSL check | [RFC 8446](https://datatracker.ietf.org/doc/html/rfc8446) — TLS 1.3, [RFC 5280](https://datatracker.ietf.org/doc/html/rfc5280) — X.509 PKI Certificates, [RFC 6125](https://datatracker.ietf.org/doc/html/rfc6125) — Representation and Verification of Application-Layer Identities in TLS |
| **ping** | ICMP ping check | [RFC 792](https://datatracker.ietf.org/doc/html/rfc792) — Internet Control Message Protocol (ICMP) |
| **trace** | Traceroute with ASN/geo visualization | [RFC 792](https://datatracker.ietf.org/doc/html/rfc792) — ICMP (TTL exceeded), [RFC 1393](https://datatracker.ietf.org/doc/html/rfc1393) — Traceroute Using an IP Option |

---

## WHOIS & Registration Tools

| Tool | Description | Relevant RFCs / Standards |
|------|-------------|--------------------------|
| **whois** | WHOIS domain lookup | [RFC 3912](https://datatracker.ietf.org/doc/html/rfc3912) — WHOIS Protocol Specification |
| **arin** | ARIN IP/network lookup via RDAP | [RFC 7480](https://datatracker.ietf.org/doc/html/rfc7480) — HTTP Usage in RDAP, [RFC 7481](https://datatracker.ietf.org/doc/html/rfc7481) — Security Services for RDAP, [RFC 7482](https://datatracker.ietf.org/doc/html/rfc7482) — RDAP Query Format, [RFC 7483](https://datatracker.ietf.org/doc/html/rfc7483) — JSON Responses for RDAP, [RFC 7484](https://datatracker.ietf.org/doc/html/rfc7484) — RDAP Bootstrap Service |
| **asn** | ASN lookup (Team Cymru DNS + RDAP + optional Routinator RPKI validation) | [RFC 6483](https://datatracker.ietf.org/doc/html/rfc6483) — Validation of Route Origination Using RPKI, [RFC 7480–7484](https://datatracker.ietf.org/doc/html/rfc7480) — RDAP |

---

## Blocklist & Security Tools

| Tool | Description | Relevant RFCs / Standards |
|------|-------------|--------------------------|
| **blacklist** | IP-based DNSBL check | [RFC 5782](https://datatracker.ietf.org/doc/html/rfc5782) — DNS Blacklists and Whitelists |
| **blocklist** | Domain/URI-based blocklist check | [RFC 5782](https://datatracker.ietf.org/doc/html/rfc5782) — DNS Blacklists and Whitelists |
| **cert** | DNS CERT record lookup | [RFC 4398](https://datatracker.ietf.org/doc/html/rfc4398) — Storing Certificates in the DNS |
| **loc** | DNS LOC record lookup | [RFC 1876](https://datatracker.ietf.org/doc/html/rfc1876) — A Means for Expressing Location Information in the DNS |
| **ipseckey** | DNS IPSECKEY record lookup | [RFC 4025](https://datatracker.ietf.org/doc/html/rfc4025) — A Method for Storing IPsec Keying Material in DNS |

---

## Utility Tools

| Tool | Description | Relevant RFCs / Standards |
|------|-------------|--------------------------|
| **whatismyip** | Public IP detection (HTTP + DNS) | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Domain Names (DNS-based detection via resolver queries) |
| **subnet-calc** | Subnet/CIDR calculator | [RFC 4632](https://datatracker.ietf.org/doc/html/rfc4632) — Classless Inter-domain Routing (CIDR) |
| **password-gen** | Secure password generator | — *(no specific RFC; uses `java.security.SecureRandom`)* |
| **email-extract** | Email address extractor | [RFC 5322](https://datatracker.ietf.org/doc/html/rfc5322) — Internet Message Format (addr-spec syntax) |
| **dns-propagation** | DNS propagation checker | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Domain Names |
| **dns-health** | Comprehensive DNS health check | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Domain Names, [RFC 6891](https://datatracker.ietf.org/doc/html/rfc6891) — EDNS(0), [RFC 4033–4035](https://datatracker.ietf.org/doc/html/rfc4033) — DNSSEC |

---

## Composite & Report Tools

| Tool | Description | Relevant RFCs / Standards |
|------|-------------|--------------------------|
| **domain-health** | Full domain health report | Combines: [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) (DNS), [RFC 4033–4035](https://datatracker.ietf.org/doc/html/rfc4033) (DNSSEC), [RFC 7208](https://datatracker.ietf.org/doc/html/rfc7208) (SPF), [RFC 6376](https://datatracker.ietf.org/doc/html/rfc6376) (DKIM), [RFC 7489](https://datatracker.ietf.org/doc/html/rfc7489) (DMARC), [RFC 8461](https://datatracker.ietf.org/doc/html/rfc8461) (MTA-STS), [RFC 8460](https://datatracker.ietf.org/doc/html/rfc8460) (TLSRPT) |
| **deliverability** | Email deliverability check | Combines: [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) (MX/PTR), [RFC 7208](https://datatracker.ietf.org/doc/html/rfc7208) (SPF), [RFC 6376](https://datatracker.ietf.org/doc/html/rfc6376) (DKIM), [RFC 7489](https://datatracker.ietf.org/doc/html/rfc7489) (DMARC), [RFC 8461](https://datatracker.ietf.org/doc/html/rfc8461) (MTA-STS), [RFC 8460](https://datatracker.ietf.org/doc/html/rfc8460) (TLSRPT), [RFC 5782](https://datatracker.ietf.org/doc/html/rfc5782) (DNSBL) |
| **compliance** | Email compliance check | [RFC 8058](https://datatracker.ietf.org/doc/html/rfc8058) — One-Click Unsubscribe, [RFC 7208](https://datatracker.ietf.org/doc/html/rfc7208) (SPF), [RFC 6376](https://datatracker.ietf.org/doc/html/rfc6376) (DKIM), [RFC 7489](https://datatracker.ietf.org/doc/html/rfc7489) (DMARC), [RFC 8461](https://datatracker.ietf.org/doc/html/rfc8461) (MTA-STS), [RFC 8460](https://datatracker.ietf.org/doc/html/rfc8460) (TLSRPT), Google/Yahoo 2024 Bulk Sender Requirements |
| **dmarc-report** | DMARC aggregate report parser | [RFC 7489 §7.2](https://datatracker.ietf.org/doc/html/rfc7489#section-7.2) — DMARC Aggregate Reports |
| **mailflow** | End-to-end mail flow diagnostic | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) (MX), [RFC 5321](https://datatracker.ietf.org/doc/html/rfc5321) (SMTP), [RFC 3207](https://datatracker.ietf.org/doc/html/rfc3207) (STARTTLS), [RFC 8446](https://datatracker.ietf.org/doc/html/rfc8446) (TLS 1.3) |
| **bulk** | Bulk DNS lookups from file | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Domain Names |

---

## Web Portal — DNS Health Check (Zonemaster-style)

The web portal's DNS Health Check performs ~49–54 checks across 9 categories. Key RFCs used:

| Category | Relevant RFCs / Standards |
|----------|--------------------------|
| **Delegation** | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — NS consistency, glue records |
| **Nameserver** | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — NS reachability, authoritative answers |
| **DNSSEC** | [RFC 4033](https://datatracker.ietf.org/doc/html/rfc4033), [RFC 4034](https://datatracker.ietf.org/doc/html/rfc4034), [RFC 4035](https://datatracker.ietf.org/doc/html/rfc4035) — DNSSEC validation, [RFC 5155](https://datatracker.ietf.org/doc/html/rfc5155) — NSEC3, [RFC 6781](https://datatracker.ietf.org/doc/html/rfc6781) — Operational Practices |
| **Connectivity** | [RFC 793](https://datatracker.ietf.org/doc/html/rfc793) — TCP, [RFC 768](https://datatracker.ietf.org/doc/html/rfc768) — UDP |
| **Consistency** | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — SOA serial consistency across nameservers |
| **Zone** | [RFC 7208](https://datatracker.ietf.org/doc/html/rfc7208) (SPF), [RFC 7489](https://datatracker.ietf.org/doc/html/rfc7489) (DMARC), BIMI draft |
| **Address** | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) (A), [RFC 3596](https://datatracker.ietf.org/doc/html/rfc3596) (AAAA), PTR matching |
| **Syntax** | [RFC 1035](https://datatracker.ietf.org/doc/html/rfc1035) — Label length, hostname syntax |
| **EDNS** | [RFC 6891](https://datatracker.ietf.org/doc/html/rfc6891) — Extension Mechanisms for DNS (EDNS0) |

---

## RFC Quick Reference

| RFC | Title | Used By |
|-----|-------|---------|
| RFC 768 | UDP | DNS Health (connectivity) |
| RFC 792 | ICMP | ping, trace |
| RFC 793 | TCP | tcp, DNS Health (connectivity) |
| RFC 1035 | Domain Names — Implementation & Specification | All DNS tools, dns-health, dns-propagation, bulk, whatismyip |
| RFC 1393 | Traceroute Using an IP Option | trace |
| RFC 1876 | Location Information in DNS | loc |
| RFC 2782 | DNS SRV Records | srv |
| RFC 2822 | Internet Message Format (obsoleted by 5322) | header-analyzer |
| RFC 3207 | SMTP STARTTLS Extension | smtp, mailflow |
| RFC 3596 | DNS Extensions for IPv6 | aaaa, DNS Health |
| RFC 3912 | WHOIS Protocol | whois |
| RFC 4025 | IPsec Keying Material in DNS | ipseckey |
| RFC 4033 | DNS Security Introduction | All DNSSEC tools, dns-health, dnssec-chain |
| RFC 4034 | Resource Records for DNSSEC | dnskey, ds, rrsig, nsec, dnssec-chain |
| RFC 4035 | Protocol Modifications for DNSSEC | All DNSSEC tools, dnssec-chain |
| RFC 4398 | Storing Certificates in DNS | cert |
| RFC 4632 | CIDR | subnet-calc |
| RFC 5155 | NSEC3 Hashed Authenticated Denial | nsec3param, dnssec-chain, dns-health |
| RFC 5280 | X.509 PKI Certificates | https |
| RFC 5321 | Simple Mail Transfer Protocol | smtp, mailflow |
| RFC 5322 | Internet Message Format | header-analyzer, email-extract |
| RFC 5782 | DNS Blacklists and Whitelists | blacklist, blocklist, deliverability |
| RFC 6125 | TLS Identity Verification | https |
| RFC 6376 | DKIM Signatures | dkim, deliverability, compliance, domain-health |
| RFC 6483 | RPKI Route Origination Validation | asn |
| RFC 6781 | DNSSEC Operational Practices | dnssec-chain, dns-health |
| RFC 6891 | EDNS(0) | dns-health |
| RFC 7208 | Sender Policy Framework (SPF) | spf, spf-generator, deliverability, compliance, domain-health |
| RFC 7480–7484 | RDAP (Registration Data Access Protocol) | arin, asn |
| RFC 7489 | DMARC | dmarc, dmarc-generator, dmarc-report, deliverability, compliance, domain-health |
| RFC 7505 | Null MX | mx |
| RFC 8058 | One-Click Unsubscribe | compliance |
| RFC 8314 | Cleartext Considered Obsolete (Implicit TLS) | smtp |
| RFC 8446 | TLS 1.3 | https, mailflow |
| RFC 8460 | SMTP TLS Reporting | tlsrpt, deliverability, compliance, domain-health |
| RFC 8461 | MTA Strict Transport Security | mta-sts, deliverability, compliance, domain-health |
| RFC 9110 | HTTP Semantics | http |
| RFC 9112 | HTTP/1.1 | http |
| BIMI Spec | Brand Indicators for Message Identification | bimi, compliance |
