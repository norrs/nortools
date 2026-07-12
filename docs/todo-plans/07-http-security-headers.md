# HTTP Security Headers Plan

## Existing Coverage

- CLI `tools/network/http` can show response headers.
- Web `/http` API returns up to 20 headers.
- Web `/https` also returns headers as part of HTTPS diagnostics.
- No security-header analysis exists for HSTS, CSP, cookies, CORS, frame protections, referrer policy, or permissions policy.

## Location

- Extend existing HTTP tool rather than creating a new category:
  - `tools/network/http`
  - `web/src/main/kotlin/no/norrs/nortools/web/NetworkHandlers.kt`
  - `web/src/main/resources/vue/components/http-page.vue`
- Shared analyzer library: `lib/httpsec`.

## Implementation Steps

1. Create `lib/httpsec`.
   - Models:
     - `HttpSecurityReport`
     - `HttpSecurityFinding`
     - `HeaderAssessment`
     - `CookieAssessment`
   - Inputs:
     - URL
     - status code
     - redirect chain if available
     - headers
     - scheme

2. Implement header checks.
   - HSTS:
     - HTTPS only.
     - `max-age` present and sane.
     - `includeSubDomains` and `preload` as informational.
   - CSP:
     - Present.
     - Warn on `unsafe-inline`, `unsafe-eval`, wildcard sources, missing `default-src`.
   - Frame protection:
     - `frame-ancestors` in CSP or `X-Frame-Options`.
   - MIME sniffing:
     - `X-Content-Type-Options: nosniff`.
   - Referrer policy:
     - Present and not overly permissive.
   - Permissions policy:
     - Present and parseable.
   - CORS:
     - Warn on `Access-Control-Allow-Origin: *` with credentials.
   - Cookies:
     - Parse each `Set-Cookie`.
     - Assess `Secure`, `HttpOnly`, `SameSite`, domain, path, expiry.

3. Update CLI.
   - Add `--security`.
   - Add `--security-only` if a concise report is useful.
   - Keep `--headers` behavior unchanged.
   - Include findings in `--json`.

4. Update web API and UI.
   - Add `security` field to `HttpCheckResponse`.
   - Show a compact score and findings on `/http`.
   - Optionally show the same analysis on `/https`.

5. Add scoring.
   - Use severity counts rather than a misleading numeric grade for MVP.
   - Suggested severities: PASS, INFO, WARN, FAIL.
   - Keep missing optional headers as INFO unless there is a clear security risk.

6. Tests.
   - Header parsing tests with realistic headers.
   - Cookie parser tests.
   - CORS wildcard plus credentials test.
   - CLI JSON regression tests.

## Acceptance Criteria

- A user can run one URL and get actionable security-header findings.
- Existing HTTP output remains compatible when `--security` is not used.
- The analyzer works from captured headers without making extra network requests.
- No crawling or site-wide scanning is added.
