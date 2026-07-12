# NTP Clock Drift Plan

## Existing Coverage

- No substantive NTP/SNTP implementation was found.
- Existing network tools are target-driven: TCP, HTTP, HTTPS, ping, trace, iperf.

## Location

- New network tool: `tools/network/ntp`.
- Shared implementation: `lib/ntp`.
- Web page: `/ntp`.
- Web handlers can live in `NetworkHandlers.kt` initially or a new `NtpHandlers.kt` if the file is too large.

## Scope

Query explicit NTP servers and calculate local clock offset. Do not scan networks for NTP servers.

## Implementation Steps

1. Create `lib/ntp`.
   - Implement minimal SNTP/NTP client over UDP port 123.
   - Models:
     - `NtpRequest`
     - `NtpResponse`
     - `NtpServerResult`
     - `NtpClockAssessment`
   - Parse:
     - leap indicator
     - version
     - mode
     - stratum
     - poll
     - precision
     - root delay
     - root dispersion
     - reference ID
     - reference, originate, receive, transmit timestamps
   - Calculate round-trip delay and local offset.

2. Add CLI.
   - Examples:
     - `bazelisk run //tools/network/ntp -- time.cloudflare.com`
     - `bazelisk run //tools/network/ntp -- --count 5 pool.ntp.org`
     - `bazelisk run //tools/network/ntp -- --json time.google.com`
   - Flags:
     - `--timeout`
     - `--count`
     - `--interval-ms`
     - `--server` should not be reused here because the positional target is the NTP server.

3. Add multi-server mode.
   - Accept repeated host arguments or `--file`.
   - Keep concurrency bounded.
   - Report median offset when more than one response exists.

4. Add web API and page.
   - `GET /api/ntp/{host}`
   - Optional query params: count, timeout.
   - Page shows offset, delay, stratum, leap status, and per-sample table.

5. Add findings.
   - WARN if no response.
   - WARN if stratum is 0 or invalid.
   - WARN if leap indicator says unsynchronized.
   - INFO/WARN thresholds for offset:
     - INFO below 100 ms.
     - WARN at or above 1000 ms by default.

6. Tests.
   - Packet encoder/decoder tests with fixture bytes.
   - Timestamp conversion tests.
   - Offset calculation tests.
   - UDP integration test can use a fake local UDP server.

## Acceptance Criteria

- A user can query one NTP server and see offset/delay/stratum.
- Timeout behavior is clear and bounded.
- Tests do not depend on public NTP servers.
- No NTP server discovery is added.
