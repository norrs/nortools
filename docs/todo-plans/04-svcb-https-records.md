# SVCB HTTPS DNS Records Plan

## Existing Coverage

- Generic DNS API accepts arbitrary record types using `Type.value(type)`.
- DNS lookup page has a custom record field and examples include CAA/PTR/TLSA.
- DNS record help mentions SVCB and HTTPS.
- No CLI target exists for SVCB or HTTPS DNS RR parsing.
- Existing `tools/network/https` uses the command name `https`, so a DNS HTTPS record CLI must not conflict with the network HTTPS checker.

## Location

- Add dedicated DNS tool at `tools/dns/svcb`.
- Use one CLI command, `svcb`, for both `SVCB` and `HTTPS` record types.
- Add shared SvcParam parsing helpers to `lib/dns`.
- Add richer display to the existing DNS lookup page rather than creating a separate page first.

## Implementation Steps

1. Add parser models in `lib/dns`.
   - `ServiceBindingRecord`: owner, type, priority, targetName, params.
   - `SvcParam`: key, value, decodedValue.
   - Known params: `mandatory`, `alpn`, `no-default-alpn`, `port`, `ipv4hint`, `ech`, `ipv6hint`, `dohpath`, `ohttp`.

2. Determine dnsjava support.
   - If dnsjava exposes typed SVCB/HTTPS records, use typed accessors.
   - If not, parse `record.rdataToString()` conservatively.
   - Keep raw record data in all responses.

3. Add CLI.
   - Examples:
     - `bazelisk run //tools/dns/svcb -- example.com`
     - `bazelisk run //tools/dns/svcb -- --type HTTPS example.com`
     - `bazelisk run //tools/dns/svcb -- --type SVCB _dns.example.com`
   - Flags:
     - `--type HTTPS|SVCB`, default `HTTPS`.
     - `--server`
     - `--timeout`
     - `--json`

4. Update web DNS lookup page.
   - Include `HTTPS` and `SVCB` as first-class options.
   - When record type is SVCB/HTTPS, show priority, target, and decoded params.
   - Keep generic table fallback for unknown or unparsable data.

5. Add optional domain health checks.
   - Add informational checks only:
     - "Web / HTTPS DNS Record".
     - "Web / HTTP/3 Advertised" when ALPN contains `h3`.
     - "Web / ECH Advertised" when `ech` exists.
   - Do not fail a domain for missing HTTPS RR.

6. Tests.
   - Parser tests for simple alias mode, service mode, ALPN, port, IP hints, ECH base64, unknown params.
   - CLI output tests for table and JSON.
   - Web route tests for `/api/dns/HTTPS/{domain}` if not already covered.

## Acceptance Criteria

- Users can inspect HTTPS/SVCB records without using a generic custom query.
- Existing `tools/network/https` remains the HTTPS/TLS checker.
- Parsed output keeps raw values available.
- Missing records are INFO, not failure.
