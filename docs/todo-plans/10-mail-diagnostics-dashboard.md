# Mail Diagnostics Dashboard Plan

## Existing Coverage

Existing CLI tools:

- `tools/email/header-analyzer`: parses headers, Received path, Authentication-Results, and key headers.
- `tools/composite/dmarc-report`: parses one DMARC aggregate XML file and summarizes source IPs, counts, disposition, DKIM, and SPF.
- `tools/composite/deliverability`: checks MX, SPF, DMARC, DKIM common selectors, MTA-STS, TLSRPT, and PTR.
- `tools/composite/compliance`: checks SPF, DKIM, DMARC, MTA-STS, TLSRPT, BIMI, PTR, and broad sender compliance.
- Web domain health already has mail checks, including deeper secure mail checks in `CompositeHandlers.kt`.

Missing pieces:

- No web page for DMARC aggregate report upload/parsing.
- No combined dashboard for DNS policy, message headers, DMARC reports, and deliverability.
- Parsers are embedded in CLI files rather than reusable libraries.
- No ZIP/GZIP DMARC report support.
- No CSV export or grouped report views.

## Location

- Shared library: `lib/maildiag`.
- Keep current CLI tools and migrate them to the shared library.
- Add web handlers in `MailDiagnosticsHandlers.kt`.
- Add page `/mail-diagnostics`.
- Optionally add composite CLI `tools/composite/mail-diagnostics` after the web models stabilize.

## Implementation Steps

1. Extract reusable parsers.
   - `DmarcAggregateReportParser`
   - `EmailHeaderParser`
   - `AuthenticationResultsParser`
   - `ReceivedHeaderParser`
   - Preserve current CLI behavior through adapters.

2. Expand DMARC report support.
   - Accept XML, `.gz`, `.zip`.
   - Parse multiple reports in one upload or directory.
   - Aggregate by:
     - source IP
     - header-from domain
     - envelope-from domain
     - disposition
     - DKIM result
     - SPF result
     - alignment result
     - reporting organization
   - Track total volume, pass/fail counts, forwarded-like patterns, and top failing sources.

3. Add DNS policy snapshot for the mail domain.
   - Reuse existing SPF, DKIM discovery, DMARC, MTA-STS, TLSRPT, BIMI, MX, PTR, and blocklist logic where practical.
   - Keep long external checks explicit and bounded.

4. Add header analysis section.
   - Paste/upload headers.
   - Show hop timeline, delay between hops where dates are parseable, authentication results, DKIM selectors, list-unsubscribe, and suspicious mismatches.

5. Add web API.
   - `POST /api/mail-diagnostics/dmarc-report`
   - `POST /api/mail-diagnostics/headers`
   - `GET /api/mail-diagnostics/domain/{domain}`
   - Use request size limits and clear errors for huge uploads.

6. Add dashboard UI.
   - Tabs: Domain Policy, DMARC Reports, Header Analyzer, Exports.
   - Tables for top sources and failures.
   - CSV export for aggregate rows.
   - JSON tab for raw normalized results.

7. Update existing CLI tools.
   - Migrate one at a time to `lib/maildiag`.
   - Keep command names and flags stable.
   - Add `--csv` to `dmarc-report` once aggregate models exist.

8. Tests.
   - XML parser fixtures.
   - GZIP and ZIP fixture tests.
   - Header folding and Authentication-Results parser tests.
   - Aggregation tests with multiple reports.
   - Web upload tests with small fixtures.

## Acceptance Criteria

- Existing CLI tools still work.
- DMARC reports can be parsed in the web UI.
- Multiple reports can be aggregated and exported.
- Header analysis and domain policy results use shared parsers/models.
- No mailbox access or background fetching is added in MVP.
