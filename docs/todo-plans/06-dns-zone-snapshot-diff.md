# DNS Zone Snapshot And Diff Plan

## Existing Coverage

- `tools/util/dns-propagation` compares DNS answers across resolvers at one point in time.
- DNS health checks compare SOA serials, NS consistency, and some delegated answers.
- No persisted snapshot format exists.
- No CLI or web flow compares DNS state over time.

## Location

- New utility CLI: `tools/util/dns-snapshot`.
- Shared library: `lib/dns-snapshot` or `lib/dns` subpackage `snapshot`.
- Optional web page: `/dns-snapshot`.
- Reuse `DnsResolver` and `OutputFormatter`.

## Scope

This is not a zone transfer tool and not a subdomain enumerator. It should only snapshot explicit owner names and record types supplied by the user or a small built-in domain-health set.

## Snapshot Model

Suggested JSON:

```json
{
  "schemaVersion": 1,
  "createdAt": "2026-07-12T00:00:00Z",
  "domain": "example.com",
  "resolver": "system",
  "queries": [
    {
      "name": "example.com",
      "type": "A",
      "status": "NOERROR",
      "records": [
        {"name": "example.com.", "type": "A", "ttl": 300, "data": "93.184.216.34"}
      ]
    }
  ]
}
```

## Implementation Steps

1. Create shared snapshot library.
   - Models: `DnsSnapshot`, `DnsSnapshotQuery`, `DnsSnapshotRecord`, `DnsSnapshotDiff`, `DnsRecordChange`.
   - Normalize names with trailing dot.
   - Sort records deterministically by name, type, data.
   - Treat TTL changes as optional diff noise controlled by a flag.

2. Add default query profiles.
   - `basic`: A, AAAA, CNAME, MX, NS, SOA, TXT, CAA.
   - `mail`: MX, SPF TXT, DMARC TXT, DKIM explicit selectors if supplied, MTA-STS, TLSRPT, BIMI.
   - `dnssec`: DS, DNSKEY, RRSIG, NSEC/NSEC3PARAM where applicable.
   - User can pass explicit `--type` values.

3. Add CLI.
   - `bazelisk run //tools/util/dns-snapshot -- capture example.com --profile basic --out snapshot.json`
   - `bazelisk run //tools/util/dns-snapshot -- diff before.json after.json`
   - `bazelisk run //tools/util/dns-snapshot -- capture example.com --type A --type MX --json`
   - Flags:
     - `--server`
     - `--timeout`
     - `--ignore-ttl`
     - `--include-empty`

4. Add web support.
   - First milestone: paste/upload two snapshot JSON files and display diff.
   - Later: capture current snapshot from the web UI and download JSON.
   - Keep file processing local to the app request.

5. Integrate with existing tools.
   - Add "Save snapshot" from DNS health/domain health later.
   - Allow dns-propagation output to be converted to a snapshot later, but do not block MVP.

6. Tests.
   - Snapshot normalization tests.
   - Diff tests for added, removed, changed, TTL-only changes.
   - CLI tests with fixture snapshots.

## Acceptance Criteria

- Users can capture and diff explicit DNS records.
- Diff output is deterministic.
- No subdomain guessing or AXFR attempt is added.
- JSON schema is documented and versioned.
