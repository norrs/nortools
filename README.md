My network tools
=======

A comprehensive suite of network and DNS tools inspired by a lot of tools, built in Kotlin with Bazel.

Quick developer setup: see [developer-setup.md](developer-setup.md).


How to test the desktop app
Dev mode: bazelisk run //desktop:desktop -- --dev
Production mode: bazelisk run //desktop:desktop

## Desktop Auto-Update Release Flow

Desktop release tags (`v*`) now drive updater manifests automatically:

1. (Recommended) Add release notes file `release-notes/<tag>.md` (example: `release-notes/v0.2.0.md`) in the tag commit.
2. Push a tag (example: `v0.2.0`).
3. `release-desktop-all.yml` sets the GitHub release body from `release-notes/<tag>.md`.
   - If the file is missing, it falls back to a generated commit list since the previous tag.
4. The workflow builds and uploads platform artifacts.
5. The same workflow then generates and uploads:
   - `nortools-update-linux-x86_64.json`
   - `nortools-update-darwin-x86_64.json`
   - `nortools-update-darwin-aarch64.json`
   - `nortools-update-windows-x86_64.json`
6. Krema updater endpoint is:
   - `https://github.com/norrs/nortools/releases/latest/download/nortools-update-{{target}}.json`

Release notes folder:

- See `release-notes/README.md` for expected naming and content.

Desktop screenshot refresh after release:

- `release-desktop-all.yml` now runs screenshot refresh after the Linux native release step.
  - It downloads `nortools-linux-amd64-<tag>.tar.gz`, runs Bazel screenshot automation, and opens a PR with updated `docs/screenshots/*`.
- You can still run `.github/workflows/release-desktop-screenshots.yml` manually with a release tag (example: `v0.2.0`).
- Local Bazel entrypoint with preflight host dependency checks:
  - `mise x -- bazelisk run //scripts/release:capture_desktop_screenshots -- --check-only`
  - `mise x -- bazelisk build //desktop:native-linux-x64`
  - `mise x -- bazelisk run //scripts/release:capture_desktop_screenshots -- --tarball bazel-bin/desktop/nortools-linux-x64.tar.gz --output-dir docs/screenshots --display :99`
- PR comment trigger (owner only):
  - On a PR, comment: `/capture-desktop-screenshots`
  - Workflow `.github/workflows/pr-comment-desktop-screenshots.yml` builds Linux native from the PR head commit, captures screenshots, uploads artifacts, and comments the result on the PR.

Optional signing secrets (recommended):

- `KREMA_UPDATER_PRIVATE_KEY_B64`: base64 PKCS#8 Ed25519 private key used to sign artifacts.
- `KREMA_UPDATER_PUBLIC_KEY_B64`: matching base64 public key (uploaded as `nortools-updater-public-key.txt`).

Security model:

- Runtime override for updater public key is intentionally disabled.
- Pin the updater public key directly in `desktop/src/main/kotlin/no/norrs/nortools/desktop/KremaApp.kt`
  (`PINNED_UPDATER_PUBLIC_KEY_B64`).
- If that constant is blank, updater is disabled (fail-closed).
- Endpoint override via `NORTOOLS_UPDATER_ENDPOINT` is still allowed.

Desktop app version source:

- `KremaApp` reads stamped build metadata (`git-build-info.properties` / `build-data.properties`).
- It uses `build.version` / `git.describe`, strips leading `v`, and uses the stable core version (`0.0.YYMMDDNNN`) for Krema updater comparisons.

Generated `krema.toml` build artifact:

- Bazel target `//:krema_toml_generated` emits `bazel-bin/krema.generated.toml`.
- This artifact keeps your checked-in template but stamps `package.version` from build metadata with the same Krema-safe normalization.


## Prerequisites

- [Bazelisk](https://github.com/bazelbuild/bazelisk) (or Bazel 8.5.1+)
- Java 17+

## Build

```bash
bazelisk build //...
```

## Common Flags

All tools support these flags:

| Flag | Description |
|---|---|
| `--json` | Output results in JSON format |
| `--server`, `-s` | Custom DNS server to query |
| `--timeout`, `-t` | Query timeout in seconds (default: 10) |
| `--help` | Show help for the tool |

## Tools

### DNS Lookup Tools

```bash
# MX Lookup — query mail exchange records
bazelisk run //tools/dns/mx -- example.com

# A Record Lookup — query IPv4 address records
bazelisk run //tools/dns/a -- example.com

# AAAA Record Lookup — query IPv6 address records
bazelisk run //tools/dns/aaaa -- example.com

# CNAME Record Lookup — query canonical name records
bazelisk run //tools/dns/cname -- www.example.com

# TXT Record Lookup — query text records
bazelisk run //tools/dns/txt -- example.com

# SOA Record Lookup — query start of authority records
bazelisk run //tools/dns/soa -- example.com

# PTR Record Lookup — reverse DNS lookup
bazelisk run //tools/dns/ptr -- 8.8.8.8

# NS Record Lookup — query name server records
bazelisk run //tools/dns/ns -- example.com

# SRV Record Lookup — query service records
bazelisk run //tools/dns/srv -- _sip._tcp.example.com
```

### DNSSEC Tools

```bash
# DNSKEY Lookup — query DNSSEC public keys
bazelisk run //tools/dnssec/dnskey -- cloudflare.com

# DS Record Lookup — query delegation signer records
bazelisk run //tools/dnssec/ds -- cloudflare.com

# RRSIG Lookup — query DNSSEC signatures
bazelisk run //tools/dnssec/rrsig -- cloudflare.com

# NSEC Lookup — query NSEC records (authenticated denial of existence)
bazelisk run //tools/dnssec/nsec -- cloudflare.com

# NSEC3PARAM Lookup — query NSEC3 parameters
bazelisk run //tools/dnssec/nsec3param -- cloudflare.com
```

### Email Authentication Tools

```bash
# SPF Lookup — query and analyze SPF records
bazelisk run //tools/email/spf -- example.com

# DKIM Lookup — query DKIM records (requires selector)
bazelisk run //tools/email/dkim -- google example.com

# DMARC Lookup — query and analyze DMARC policy
bazelisk run //tools/email/dmarc -- example.com

# BIMI Lookup — query Brand Indicators for Message Identification
bazelisk run //tools/email/bimi -- example.com
bazelisk run //tools/email/bimi -- --selector myselector example.com

# MTA-STS Lookup — query MTA Strict Transport Security
bazelisk run //tools/email/mta-sts -- example.com

# TLSRPT Lookup — query SMTP TLS Reporting records
bazelisk run //tools/email/tlsrpt -- example.com
```

### Email Infrastructure Tools

```bash
# SMTP Test — test SMTP server connectivity and STARTTLS
bazelisk run //tools/email/smtp -- example.com
bazelisk run //tools/email/smtp -- --port 587 example.com

# Email Header Analyzer — parse and analyze email headers
bazelisk run //tools/email/header-analyzer -- headers.txt
cat headers.txt | bazelisk run //tools/email/header-analyzer -- -

# SPF Record Generator — build SPF records from parameters
bazelisk run //tools/email/spf-generator -- --include _spf.google.com --ip4 203.0.113.0/24 --all softfail

# DMARC Record Generator — build DMARC records from parameters
bazelisk run //tools/email/dmarc-generator -- example.com --policy reject --rua mailto:dmarc@example.com
```

### Network Tools

```bash
# TCP Port Check — test TCP connectivity
bazelisk run //tools/network/tcp -- example.com 443
bazelisk run //tools/network/tcp -- --banner example.com 25

# HTTP Check — check HTTP response details
bazelisk run //tools/network/http -- http://example.com
bazelisk run //tools/network/http -- --headers --body http://example.com

# HTTPS Check — check TLS/certificate details
bazelisk run //tools/network/https -- example.com

# Ping — ICMP echo requests
bazelisk run //tools/network/ping -- example.com
bazelisk run //tools/network/ping -- --count 10 example.com

# Traceroute — trace network path
bazelisk run //tools/network/trace -- example.com
bazelisk run //tools/network/trace -- --max-hops 20 example.com
```

### WHOIS / Registration Tools

```bash
# WHOIS Lookup — domain/IP registration information
bazelisk run //tools/whois/whois -- example.com

# ARIN Lookup — IP address ownership from ARIN
bazelisk run //tools/whois/arin -- 8.8.8.8

# ASN Lookup — Autonomous System Number information
bazelisk run //tools/whois/asn -- AS13335
bazelisk run //tools/whois/asn -- 1.1.1.1
```

### Blocklist & Security Tools

```bash
# Blacklist Check — check IP against DNS-based blacklists
bazelisk run //tools/blocklist/blacklist -- 203.0.113.1

# Domain Blocklist Check — check domain against URI blocklists
bazelisk run //tools/blocklist/blocklist -- example.com

# CERT Record Lookup — query DNS certificate records
bazelisk run //tools/blocklist/cert -- example.com

# LOC Record Lookup — query DNS geographic location records
bazelisk run //tools/blocklist/loc -- example.com

# IPSECKEY Record Lookup — query IPsec keying material
bazelisk run //tools/blocklist/ipseckey -- example.com
```

### Utility Tools

```bash
# What Is My IP — detect your public IP address
bazelisk run //tools/util/whatismyip

# Subnet Calculator — calculate subnet details from CIDR notation
bazelisk run //tools/util/subnet-calc -- 192.168.1.0/24
bazelisk run //tools/util/subnet-calc -- 10.0.0.0/8

# Password Generator — generate secure random passwords
bazelisk run //tools/util/password-gen
bazelisk run //tools/util/password-gen -- --length 32 --count 5
bazelisk run //tools/util/password-gen -- --no-special --length 20

# Email Extractor — extract email addresses from text
bazelisk run //tools/util/email-extract -- input.txt
bazelisk run //tools/util/email-extract -- --unique --sort input.txt
cat webpage.html | bazelisk run //tools/util/email-extract -- --domain

# DNS Propagation Check — check DNS propagation across global resolvers
bazelisk run //tools/util/dns-propagation -- example.com
bazelisk run //tools/util/dns-propagation -- --type MX example.com

# DNS Health Check — comprehensive DNS health check
bazelisk run //tools/util/dns-health -- example.com
```

### Composite / Report Tools

```bash
# Domain Health Report — comprehensive domain health check
bazelisk run //tools/composite/domain-health -- example.com

# Email Deliverability Check — check all email deliverability factors
bazelisk run //tools/composite/deliverability -- example.com

# Email Compliance Check — verify email sending compliance (Google/Yahoo requirements)
bazelisk run //tools/composite/compliance -- example.com

# DMARC Report Analyzer — parse DMARC aggregate XML reports
bazelisk run //tools/composite/dmarc-report -- report.xml

# Bulk Lookup — bulk DNS lookups from a file
bazelisk run //tools/composite/bulk -- domains.txt
bazelisk run //tools/composite/bulk -- --type MX domains.txt

# Mail Flow Diagnostic — end-to-end mail flow check
bazelisk run //tools/composite/mailflow -- example.com
```

## Examples with Common Flags

```bash
# JSON output
bazelisk run //tools/dns/mx -- --json google.com

# Custom DNS server
bazelisk run //tools/dns/a -- --server 1.1.1.1 example.com

# Custom timeout
bazelisk run //tools/network/tcp -- --timeout 5 example.com 443

# Combine flags
bazelisk run //tools/dns/txt -- --json --server 8.8.8.8 --timeout 15 example.com
```

## Project Structure

```
nortools/
├── MODULE.bazel          # Bazel module configuration
├── lib/
│   ├── cli/              # CLI framework (BaseCommand with common flags)
│   ├── dns/              # DNS resolver library (dnsjava wrapper)
│   ├── network/          # HTTP/TCP client utilities
│   └── output/           # Table/JSON output formatting
└── tools/
    ├── dns/              # Core DNS lookup tools (9)
    ├── dnssec/           # DNSSEC tools (5)
    ├── email/            # Email authentication & infrastructure (10)
    ├── network/          # Network connectivity tools (5)
    ├── whois/            # WHOIS/registration tools (3)
    ├── blocklist/        # Blocklist & security tools (5)
    ├── util/             # Utility tools (6)
    └── composite/        # Composite report tools (6)
```

PR comment screenshot trigger test line: 2026-02-20T21:07:32Z
