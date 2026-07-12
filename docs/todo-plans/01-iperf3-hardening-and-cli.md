# iperf3 Hardening And CLI Plan

## Existing Coverage

- `docs/iperf3-integration-design.md` already defines the intended architecture.
- `tools/network/iperf/BUILD.bazel` and helper scripts build/package iperf3.
- `web/src/main/kotlin/no/norrs/nortools/web/IperfHandlers.kt` already has binary detection, client jobs, server start/stop, mDNS advertisement, discovery, public catalog, and JSON parsing.
- `web/src/main/resources/vue/components/iperf-page.vue` already exposes local server, client test, mDNS discovery, public catalog, results, and JSON.

Missing pieces:

- No `lib/iperf` shared domain layer.
- No CLI target for `nortools iperf`.
- Process management, command construction, and parsing live inside web handlers.
- Tests are mostly route registration, not parser/command/process behavior.
- Packaging verification is not represented as smoke tests.

## Location

- Move reusable code to `lib/iperf`.
- Keep packaging under `tools/network/iperf`.
- Add CLI binary at `tools/network/iperf/src/main/kotlin/.../IperfTool.kt`.
- Keep web route and page under the existing `/iperf` feature.

## Implementation Steps

1. Create `lib/iperf`.
   - Models: `IperfClientRequest`, `IperfClientResult`, `IperfResultSummary`, `IperfServerStatus`, `LocatedIperfBinary`, `PublicIperfServer`.
   - Services: `IperfBinaryLocator`, `IperfCommandBuilder`, `IperfJsonParser`, `PublicIperfCatalog`.
   - Keep all command arguments typed. Do not expose free-form iperf args.

2. Extract command building from `IperfHandlers.kt`.
   - Preserve supported options: host, port, duration, TCP/UDP, IPv4/IPv6/auto, parallel streams, reverse mode, UDP bitrate.
   - Add tests that assert exact argv for common requests.
   - Ensure binary path is not included in reported user command unless needed for debugging.

3. Extract JSON parsing.
   - Parse TCP sender/receiver bitrate, retransmits, UDP jitter, lost packets, total packets, loss percent, CPU utilization when present.
   - Preserve raw JSON when parsing succeeds.
   - Return a structured parse error and raw output when parsing fails.

4. Add CLI wrapper.
   - Suggested modes:
     - `bazelisk run //tools/network/iperf -- status`
     - `bazelisk run //tools/network/iperf -- client example.com --port 5201 --duration 5`
     - `bazelisk run //tools/network/iperf -- server --port 5201 --one-off`
     - `bazelisk run //tools/network/iperf -- public-servers`
   - Support `--json` through existing `BaseCommand`.
   - Keep server mode foreground and stoppable with Ctrl+C in CLI.

5. Update web handlers to depend on `lib/iperf`.
   - `IperfHandlers.kt` should keep request parsing, job storage, and HTTP response mapping.
   - Move binary location, command building, catalog, and JSON parsing out.

6. Add tests.
   - Unit tests in `lib/iperf` for command building and JSON parsing.
   - Web tests for request validation and job lifecycle using fake process execution if possible.
   - Packaging smoke tests that validate `iperf3 --version` for packaged artifacts where supported.

7. Update docs and route lists.
   - Add README CLI examples.
   - Add RFC entry for iperf3 as a throughput diagnostic tool with iperf3 project reference rather than an RFC.
   - Refresh screenshots after UI changes.

## Acceptance Criteria

- `IperfHandlers.kt` no longer owns parser or command construction logic.
- A CLI user can run one short client test and get table or JSON output.
- The web UI keeps current behavior.
- Unit tests cover TCP, UDP, reverse, IPv4/IPv6, timeout, server busy output, malformed JSON, and missing binary.
- No network range probing is introduced.
