# Subnet Planner Plan

## Existing Coverage

- CLI `tools/util/subnet-calc` calculates details for one IPv4 or IPv6 CIDR.
- Web `/subnet` calculates one IPv4 CIDR and displays network, broadcast, mask, wildcard mask, host range, and total hosts.
- Wildcard mask already exists.
- No split, summarize, overlap, VLSM, allocation, or import/export workflow exists.

## Location

- Extend existing `tools/util/subnet-calc`.
- Extract reusable logic to `lib/subnet`.
- Update web handler `UtilityHandlers.kt`.
- Expand `subnet-page.vue` into a calculator plus planner interface.

## Implementation Steps

1. Create `lib/subnet`.
   - Models:
     - `IpNetwork`
     - `SubnetDetails`
     - `SubnetSplitRequest`
     - `SubnetAllocationRequest`
     - `SubnetOverlapResult`
     - `SubnetSummaryResult`
   - Support IPv4 first; keep IPv6 details and summarization as a second milestone if needed.

2. Move current CIDR calculation into `lib/subnet`.
   - Keep CLI and web outputs compatible.
   - Fix any divergence between CLI IPv6 support and web IPv4-only support.

3. Add split mode.
   - CLI examples:
     - `bazelisk run //tools/util/subnet-calc -- split 10.0.0.0/16 --new-prefix 24`
     - `bazelisk run //tools/util/subnet-calc -- split 10.0.0.0/16 --count 8`
   - Output subnets with network, prefix, first/last host, usable hosts.

4. Add VLSM allocation mode.
   - Input requirements as host counts or prefixes.
   - Allocate largest first.
   - Report unused gaps.
   - Example:
     - `bazelisk run //tools/util/subnet-calc -- plan 10.0.0.0/16 --hosts 500 --hosts 120 --hosts 60`

5. Add overlap mode.
   - Compare two or more CIDRs.
   - Report equal, contains, contained-by, partial overlap, no overlap.
   - Useful for firewall and routing planning.

6. Add summarize mode.
   - Summarize adjacent CIDRs into the smallest set of covering CIDRs.
   - Keep exact coverage; do not include addresses outside the inputs unless `--allow-supernet` is explicit.

7. Update web page.
   - Add tabs: Calculator, Split, Plan, Overlap, Summarize.
   - Keep dense operational UI, not a marketing page.
   - Add copy/download JSON for generated plans.

8. Tests.
   - IPv4 details regression tests.
   - Split by prefix and count.
   - VLSM allocation order.
   - Overlap matrix.
   - Summarization exactness.

## Acceptance Criteria

- Existing `subnet-calc <cidr>` still works.
- New planner modes are offline and deterministic.
- Web and CLI use the same `lib/subnet` logic.
- JSON output is suitable for export or later import.
