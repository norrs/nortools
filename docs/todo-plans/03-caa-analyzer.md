# CAA Analyzer Plan

## Existing Coverage

- CLI `tools/util/dns-health` checks CAA presence and reports raw records.
- CLI `tools/composite/domain-health` checks CAA presence as informational web health.
- Web domain health has deeper CAA checks through `CaaRecords.kt` and `CompositeHandlers.kt`:
  - Syntax parsing.
  - `issue` and `issuewild`.
  - `iodef`.
  - Basic issuer compatibility with the live certificate chain.
- DNS health web checks also parse CAA and report syntax/issue/iodef status.
- Generic DNS lookup page supports CAA.

Missing pieces:

- No shared CAA analyzer library reused by CLI and web.
- No dedicated CAA CLI/API that explains effective policy and inheritance.
- No parent-domain CAA walk.
- No clear distinction between "no CAA at this exact owner" and "effective CAA inherited from parent".

## Location

- Shared parser/analyzer: `lib/caa` or `lib/dns` subpackage `caa`.
- Existing web parsing in `web/CaaRecords.kt` should move into the shared library.
- Enhance existing `dns-health` and `domain-health`.
- Optional detailed CLI target: `tools/dns/caa`.

## Implementation Steps

1. Create shared CAA models.
   - `CaaRecord`: owner, flags, critical, tag, value, raw.
   - `CaaPolicy`: queriedName, effectiveOwner, records, inherited, parentWalk.
   - `CaaFinding`: severity, title, detail.
   - `CaaIssuerAssessment`: allowedIssuers, forbidden, applicableForWildcard, matchesCertificateChain.

2. Move parser out of web.
   - Preserve current parsing behavior.
   - Add strict validation for flag integer, critical bit, known tags, empty values, and malformed quoting.
   - Keep unknown tags as parsed records with INFO findings.

3. Implement effective policy lookup.
   - Query the exact name first.
   - Walk parent labels until CAA records are found or the public suffix boundary is reached.
   - If no public suffix library is available, start with a conservative parent walk that stops at one label and document the limitation.

4. Add issuer compatibility.
   - Reuse certificate chain data from HTTPS checks when available.
   - Normalize common CA aliases in one shared table.
   - Treat `issue ";"` as issuance forbidden.
   - Evaluate `issuewild` when a wildcard SAN or CN is present.

5. Add optional CLI.
   - `bazelisk run //tools/dns/caa -- example.com`
   - Flags:
     - `--effective`
     - `--cert-host`
     - `--server`
     - `--json`
   - If not adding a dedicated CLI, expose equivalent detail through `dns-health --section caa` in a later CLI refactor.

6. Update web and composite checks.
   - Replace local CAA parsing in web with shared library.
   - Expand DNS health and domain health details without changing check names unless needed.

7. Tests.
   - Parser tests for `issue`, `issuewild`, `iodef`, critical flag, unknown tag, `issue ";"`.
   - Effective policy tests with mocked resolver responses.
   - Issuer compatibility tests with fixture chains.

## Acceptance Criteria

- CAA logic is not duplicated between web and CLI.
- Existing CAA health checks remain present.
- Detailed output explains exact versus inherited CAA policy.
- No unrelated DNS probing is introduced.
