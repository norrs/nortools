# Certificate Transparency And OCSP Plan

## Existing Coverage

- CLI `tools/network/https` reports status, TLS protocol, cipher suite, certificate subject/issuer, SANs, validity, key info, and SHA-256 fingerprint.
- Web HTTPS handler reports richer diagnostics: redirect chain, SNI/no-SNI probes, ALPN, hostname matching, and certificate chain.
- `NetworkHandlers.kt` maps the OCSP Signing extended key usage OID, but does not perform OCSP checks.

Missing pieces:

- No OCSP responder URL extraction or request.
- No CRL distribution point extraction.
- No SCT parsing from certificate extension or TLS handshake.
- No CT log lookup or certificate transparency summary.
- No reusable TLS/certificate domain library.

## Location

- Extend existing HTTPS tool and page:
  - `tools/network/https`
  - `web/src/main/kotlin/no/norrs/nortools/web/NetworkHandlers.kt`
  - `web/src/main/resources/vue/components/https-page.vue`
- Consider extracting certificate helpers into `lib/tls` before adding more logic.

## Implementation Steps

1. Extract certificate helpers.
   - Move reusable certificate parsing from CLI and web into `lib/tls`.
   - Models: `CertificateInfo`, `CertificateExtensionInfo`, `RevocationInfo`, `CertificateTransparencyInfo`.
   - Keep web response compatibility or provide mapping adapters.

2. Add extension extraction.
   - Authority Information Access:
     - OCSP URLs.
     - CA Issuers URLs.
   - CRL Distribution Points.
   - SCT extension OID `1.3.6.1.4.1.11129.2.4.2`.
   - Preserve raw OIDs for unsupported extensions.

3. Implement OCSP check.
   - Build OCSP request for the leaf certificate using issuer certificate.
   - Query each OCSP responder with a short timeout.
   - Return status: good, revoked, unknown, unavailable, skipped.
   - Include responder URL, producedAt, thisUpdate, nextUpdate when available.
   - If Java standard APIs are too limited, first milestone can extract and display OCSP URLs, then add request support later.

4. Implement CT/SCT support.
   - Parse SCT list from certificate extension when present.
   - Optional online lookup:
     - Make it explicit with `--ct-lookup` in CLI and a user-triggered action in web.
     - Use bounded HTTP requests.
   - Do not depend on runtime scraping for normal HTTPS checks.

5. Update CLI.
   - Flags:
     - `--revocation`
     - `--ct`
     - `--extensions`
   - Default can show extension summary without making extra network calls.

6. Update web UI.
   - Add certificate detail panels for revocation and CT.
   - Show "not checked" distinctly from "failed".
   - Keep the main HTTPS summary compact.

7. Tests.
   - Fixture certificate parsing tests.
   - OCSP URL extraction tests.
   - SCT extension parsing tests.
   - Network OCSP tests should be opt-in or mocked.

## Acceptance Criteria

- HTTPS diagnostics show whether OCSP/CRL/SCT data is present.
- Optional OCSP checks are bounded and explicit.
- Existing HTTPS behavior remains stable when revocation/CT lookups are disabled.
- No bulk certificate enumeration or CT scraping is introduced.
