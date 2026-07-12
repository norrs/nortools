# NorTools Todo Plans

Audit date: 2026-07-12

This directory tracks implementation-ready plans for non-portscanner features that fit NorTools. Each plan records what already exists, where the feature should live, and the concrete implementation steps.

## Guardrails

- Keep every feature target-driven: one domain, URL, host, file, or CIDR supplied by the user.
- Do not add general port scanning, unauthenticated network-wide service probing, or background probing without explicit user action.
- Prefer shared library code for parsing and diagnostics, then thin CLI and web wrappers.
- Keep CLI JSON output and web API models aligned.
- Reuse existing routes, pages, and composite checks when the feature is an enhancement rather than a new tool.

## Audit Summary

| # | Feature | Existing coverage found | Planned home | Plan |
|---|---|---|---|---|
| 1 | iperf3 throughput testing | Partially implemented in `web/IperfHandlers.kt`, `iperf-page.vue`, `tools/network/iperf`, and `docs/iperf3-integration-design.md`; missing shared `lib/iperf`, CLI wrapper, stronger tests, packaging checks | Existing `tools/network/iperf`, new `lib/iperf`, existing `/iperf` page | [01-iperf3-hardening-and-cli.md](01-iperf3-hardening-and-cli.md) |
| 2 | DANE / TLSA diagnostics | SMTP DANE exists inside web domain health for `_25._tcp.<mx>`; no standalone DANE/TLSA CLI/API, no general HTTPS/TLSA checker | New `tools/dnssec/tlsa`, new `lib/tlsa`, reuse in domain health | [02-dane-tlsa-diagnostics.md](02-dane-tlsa-diagnostics.md) |
| 3 | CAA analyzer | CAA is checked in CLI `dns-health` and `domain-health`, and deeper web checks exist via `CaaRecords.kt`; no dedicated detailed CLI/API analyzer | Existing DNS health/domain health plus shared CAA analyzer library; optional `tools/dns/caa` CLI | [03-caa-analyzer.md](03-caa-analyzer.md) |
| 4 | SVCB / HTTPS DNS records | Generic DNS lookup can query custom record types; help mentions SVCB/HTTPS; no parser, CLI target, or UX for SvcParams | New `tools/dns/svcb`, existing DNS lookup page, shared parser in `lib/dns` | [04-svcb-https-records.md](04-svcb-https-records.md) |
| 5 | Certificate Transparency / OCSP | HTTPS tool has TLS, SNI, ALPN, and certificate chain details; no OCSP, SCT, or CT log lookup | Existing `tools/network/https`, `NetworkHandlers.kt`, `https-page.vue`; optional `lib/tls` | [05-cert-transparency-ocsp.md](05-cert-transparency-ocsp.md) |
| 6 | DNS zone snapshot and diff | `dns-propagation` compares live resolver answers; `dns-health` compares some NS consistency; no persisted snapshot or diff | New utility `tools/util/dns-snapshot`, web page `/dns-snapshot`, shared `lib/dns-snapshot` | [06-dns-zone-snapshot-diff.md](06-dns-zone-snapshot-diff.md) |
| 7 | HTTP security header checker | HTTP/HTTPS tools expose headers; no HSTS/CSP/cookie/CORS/security-header analysis | Existing `tools/network/http` and `http-page.vue`; shared `lib/httpsec` | [07-http-security-headers.md](07-http-security-headers.md) |
| 8 | NTP / clock drift diagnostic | No substantive NTP/SNTP implementation found | New `tools/network/ntp`, web page `/ntp`, shared `lib/ntp` | [08-ntp-clock-drift.md](08-ntp-clock-drift.md) |
| 9 | Subnet planner | `subnet-calc` and `/subnet` calculate one CIDR; no VLSM, split, summarize, or overlap planning | Existing `tools/util/subnet-calc`, `UtilityHandlers.kt`, `subnet-page.vue`; shared `lib/subnet` | [09-subnet-planner.md](09-subnet-planner.md) |
| 10 | Mail diagnostics dashboard | Separate tools exist: `dmarc-report`, `deliverability`, `compliance`, `header-analyzer`; no combined dashboard or reusable report model | Existing composite/email tools plus new shared `lib/maildiag` and web page `/mail-diagnostics` | [10-mail-diagnostics-dashboard.md](10-mail-diagnostics-dashboard.md) |
| 11 | GitHub Pages documentation site | README, RFC reference, screenshots, CLI smoke specs, and Vue pages exist; no publishable end-user docs site or Pages workflow | New `docs-site` source, generated command reference, GitHub Pages workflow | [11-gh-pages-documentation-site.md](11-gh-pages-documentation-site.md) |

## Suggested Order

1. Finish iperf3 hardening, because it is already partially implemented and user-visible.
2. Build the documentation site foundation early so every later feature lands with user-facing docs.
3. Extract shared analyzer libraries for CAA, TLSA, subnet, HTTP security, and mail diagnostics before expanding web pages.
4. Add new bounded tools in this order: SVCB/HTTPS records, NTP, DNS snapshot/diff.
5. Update `README.md`, `RFC.md`, screenshots, and route tests as each feature lands.
