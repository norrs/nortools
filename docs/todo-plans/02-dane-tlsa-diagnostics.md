# DANE TLSA Diagnostics Plan

## Existing Coverage

- Web domain health already checks SMTP DANE for MX hosts:
  - Looks up `_25._tcp.<mx-host>` TLSA records.
  - Parses usage, selector, matching type, and data.
  - Compares TLSA against STARTTLS certificate data.
  - Adds DANE existence, validity, and rollover checks.
- Help page exists at `/help/mail-dane`.
- Generic DNS lookup can query `TLSA` via custom record type.

Missing pieces:

- No standalone CLI or API for "check this service endpoint with DANE".
- No HTTPS DANE/TLSA mode for `_443._tcp.<host>`.
- DANE parsing and matching are private inside `CompositeHandlers.kt`.
- No reusable test fixture library for TLSA records and certificates.

## Location

- New shared library: `lib/tlsa`.
- New CLI: `tools/dnssec/tlsa`.
- Reuse library from web domain health instead of private helper functions.
- Optional web page: `/dane` or a DANE panel inside existing DNSSEC/HTTPS pages.

## Scope

Support explicit endpoint checks only:

- SMTP: domain MX hosts on port 25 by default.
- HTTPS: explicit host and port, default 443.
- Raw TLSA lookup: explicit owner name such as `_443._tcp.example.com`.

Do not probe arbitrary ports or ranges.

## Implementation Steps

1. Create `lib/tlsa` models.
   - `TlsaRecord`: owner, usage, selector, matchingType, associationData.
   - `TlsaTarget`: protocol, host, port, ownerName.
   - `TlsaCertificateMatch`: matched, reason, fingerprint, selectedMaterial.
   - `DaneDiagnosticResult`: DNS records, parse errors, certificate chain summary, match status, rollover status.

2. Extract parser and matcher from `CompositeHandlers.kt`.
   - Preserve current behavior for selector 0 and 1.
   - Preserve SHA-256 and SHA-512 matching.
   - Return structured errors for unsupported usage, selector, matching type, and malformed hex.

3. Add certificate acquisition helpers.
   - For HTTPS, reuse or extract TLS probing from `NetworkHandlers.kt`.
   - For SMTP, reuse existing STARTTLS probing from domain health where possible.
   - Keep timeouts bounded and configurable.

4. Add CLI target.
   - Examples:
     - `bazelisk run //tools/dnssec/tlsa -- --https example.com`
     - `bazelisk run //tools/dnssec/tlsa -- --smtp example.com`
     - `bazelisk run //tools/dnssec/tlsa -- --name _443._tcp.example.com --cert-host example.com`
   - Flags:
     - `--port`
     - `--server`
     - `--timeout`
     - `--json`
     - `--no-cert-check` for DNS-only parsing.

5. Update web domain health.
   - Replace private DANE parsing/matching with `lib/tlsa`.
   - Keep check names stable for the existing UI.

6. Add optional web UI.
   - Add a compact DANE/TLSA page or add DANE mode to DNSSEC lookup.
   - Show owner name, DNSSEC-aware status when available, parsed records, certificate material, match details, and rotation guidance.

7. Tests.
   - Parser tests for valid and invalid TLSA.
   - Matching tests with local certificate fixtures.
   - Domain health regression tests for existing DANE check names.

## Acceptance Criteria

- Existing domain health DANE checks still work.
- Users can run a dedicated DANE/TLSA check without running full domain health.
- HTTPS and SMTP modes use explicit targets only.
- JSON output is stable enough for future UI reuse.
