# iperf3 Integration Design

This document describes how NorTools should integrate iperf3 as a first-class
network throughput tool.

The intended user experience is:

1. Start a local iperf3 server from NorTools.
2. Advertise that server on the local network with mDNS/DNS-SD.
3. Discover other NorTools or iperf3-compatible LAN servers.
4. Run client tests against discovered, manually entered, or curated public servers.
5. Show structured throughput, jitter, retransmit, loss, and error output without
   requiring users to know iperf3 command-line flags.

## Goals

- Use the bundled `iperf3` executable instead of reimplementing the protocol.
- Support local server start/stop from the desktop app.
- Publish local servers through DNS-SD over mDNS so nearby NorTools instances can
  find each other.
- Browse the local network for advertised iperf endpoints.
- Include a curated public server catalog based on https://iperf.fr/iperf-servers.php.
- Keep all long-running iperf processes bounded, visible, and stoppable.
- Preserve CLI-testability for the wrapper logic before the UI is added.

## Non-Goals

- Do not implement a native iperf protocol stack in Kotlin.
- Do not expose arbitrary shell execution through the iperf UI.
- Do not start a public-facing server automatically.
- Do not run background throughput tests without explicit user action.
- Do not attempt to replace nmap, ping, traceroute, or general service discovery.

## Packaging State

NorTools already has Bazel packaging support for the iperf runtime helper:

- Linux/macOS: `//tools/network/iperf:iperf3_latest_host_binary`
- Windows: `//tools/network/iperf:iperf3_latest_host_runtime_zip`

The application runtime should resolve `iperf3` from the app directory first:

| Platform | Expected app-relative path |
|---|---|
| Windows | `iperf3.exe` plus adjacent required DLLs |
| Linux | `iperf3` |
| macOS | `NorTools.app/Contents/MacOS/iperf3` |

For development builds, fall back to `PATH` only when the app-local helper is absent.
The UI should report which binary path is being used.

## Architecture

Add a small iperf domain layer rather than calling `ProcessBuilder` directly from
desktop route handlers.

Suggested modules:

| Module | Purpose |
|---|---|
| `lib/iperf` | Shared request/result models, command construction, JSON parsing, public catalog models |
| `tools/network/iperf` | Existing build/package helpers plus future CLI wrapper targets |
| `desktop` | Runtime process manager, web routes, server lifecycle |
| `web` | iperf page, local discovery view, public catalog picker, live output |

Core classes:

| Class | Responsibility |
|---|---|
| `IperfBinaryLocator` | Find packaged or PATH iperf3 binary and validate `iperf3 --version` |
| `IperfCommandBuilder` | Convert safe typed options to iperf3 args |
| `IperfJsonParser` | Parse `iperf3 --json` output into NorTools models |
| `IperfProcessManager` | Own server/client process lifecycle, stop, timeout, output capture |
| `IperfMdnsAdvertiser` | Publish/remove DNS-SD records for local server mode |
| `IperfDiscoveryService` | Browse DNS-SD for local iperf endpoints |
| `PublicIperfCatalog` | Load curated public server seed list |

## Server Mode

Server mode starts a local iperf3 process:

```text
iperf3 -s -p <port> --json
```

Recommended defaults:

| Option | Default | Notes |
|---|---:|---|
| Port | `5201` | Standard iperf3 port |
| Bind address | all interfaces | Advanced option can bind one interface/address |
| One-off mode | off | Keep server available until stopped |
| Parallel servers | off | Start one process first; port ranges can come later |
| mDNS publish | on | Only while the server process is alive |
| Startup firewall note | on Windows only | Explain that Windows may prompt for network access |

Server state should be explicit:

- `stopped`
- `starting`
- `running`
- `stopping`
- `failed`

The UI must always show the selected port, PID if available, bound address, uptime,
last client summary, and last error.

## mDNS/DNS-SD Advertisement

Use DNS-SD over mDNS for local discovery. There does not appear to be a widely
standardized iperf3 DNS-SD service type, so NorTools should use a clear custom type:

```text
_iperf3._tcp.local.
```

Service instance name:

```text
NorTools iperf3 on <hostname>
```

SRV record:

```text
target=<local-hostname>.local.
port=<server-port>
```

TXT records:

| Key | Example | Purpose |
|---|---|---|
| `app` | `nortools` | Distinguish NorTools-published endpoints |
| `app_version` | `0.0.0` | Display/debugging |
| `iperf_version` | `3.21` | Compatibility display |
| `proto` | `tcp,udp` | Supported test protocols |
| `json` | `1` | Indicates JSON-capable iperf3 |
| `tls` | `0` | Reserved; iperf3 auth/TLS is not first milestone |
| `note` | `desktop` | Optional short user-provided label |

Discovery should browse `_iperf3._tcp.local.` and resolve PTR, SRV, TXT, A, and AAAA.
If the existing `lib/zeroconf` DNS-SD work is incomplete, implement the minimum browse
and resolve path there first rather than hiding mDNS code inside the iperf feature.

## Local Discovery UX

The iperf page should have these endpoint sources:

| Source | Behavior |
|---|---|
| Manual | Host and port input |
| Local | DNS-SD browse results from `_iperf3._tcp.local.` |
| Public | Curated public server catalog |
| Recent | Last successful manual/local/public targets |

Local discovery should be a bounded scan, not an unbounded background listener:

- Default browse duration: 3 seconds.
- Refresh button to rescan.
- Optional "listen continuously" toggle later.
- Show interface/address where the service was observed.
- De-duplicate by resolved address, port, and service instance.

## Public Server Catalog

Use https://iperf.fr/iperf-servers.php as the source for a bundled seed catalog.
That page states that an iperf3 server allows only one connection at a time, so NorTools
must handle "server busy" as a normal result, not as an application failure.

Store the seed catalog as structured data, for example:

```text
web/src/main/resources/public-data/iperf3-public-servers.json
```

Suggested schema:

```json
{
  "source": "https://iperf.fr/iperf-servers.php",
  "retrieved": "YYYY-MM-DD",
  "servers": [
    {
      "host": "ping.online.net",
      "region": "Europe",
      "country": "France",
      "location": "Ile-de-France",
      "ports": [{ "from": 5200, "to": 5209, "protocols": ["tcp", "udp"] }],
      "ipVersions": ["ipv4", "ipv6"],
      "status": "ok",
      "lastTested": "2025-03"
    }
  ]
}
```

Initial seed entries can include the public servers marked OK on the source page:

| Host | Region | Ports | Protocols | IP versions | Source status |
|---|---|---:|---|---|---|
| `ping.online.net` | Europe / France | `5200-5209` | TCP/UDP | IPv4 or IPv6 | OK 2025-03 |
| `ping6.online.net` | Europe / France | `5200-5209` | TCP/UDP | IPv4 or IPv6 | OK 2025-03 |
| `iperf3.moji.fr` | Europe / France | `5200-5240` | TCP/UDP | IPv4 and IPv6 | OK 2025-03 |
| `speedtest.milkywan.fr` | Europe / France | `9200-9240` | TCP/UDP | IPv4 and IPv6 | OK 2025-03 |
| `speedtest.serverius.net` | Europe / Netherlands | `5002` | TCP/UDP | IPv4 and IPv6 | OK 2025-03 |
| `nl.iperf.014.fr` | Europe / Netherlands | `10415-10420` | TCP/UDP | IPv4 only | OK 2025-03 |
| `ch.iperf.014.fr` | Europe / Switzerland | `15315-15320` | TCP/UDP | IPv4 only | OK 2025-03 |
| `iperf.volia.net` | Europe / Ukraine | `5201` | TCP/UDP | IPv4 only | OK 2025-03 |
| `speedtest.uztelecom.uz` | Asia / Uzbekistan | `5200-5209` | TCP/UDP | IPv4 and IPv6 | OK 2025-03 |
| `iperf.angolacables.co.ao` | Africa / Angola | `9200-9240` | TCP/UDP | IPv4 and IPv6 | OK 2025-03 |

Do not automatically scrape this page at runtime. The app should ship a known catalog,
display the source and retrieved date, and allow users to refresh/update the catalog in
a later explicit feature.

## Client Mode

Client mode starts an iperf3 process with safe typed options:

```text
iperf3 -c <host> -p <port> --json -t <seconds>
```

Initial options:

| Option | Default | Bounds |
|---|---:|---|
| Duration | 10 seconds | 1-60 seconds |
| Protocol | TCP | TCP or UDP |
| Port | endpoint default | 1-65535 |
| Parallel streams | 1 | 1-16 |
| Reverse mode | off | `-R` |
| Bidirectional mode | off | `--bidir`, if supported |
| UDP bitrate | empty | Required for UDP if user enables a limit |
| IP version | auto | auto, IPv4, IPv6 |

Use `--json` for completed runs. For live updates, parse text output line-by-line in a
later milestone, or run short completed tests first and render the final JSON. The first
UI can be useful with final JSON only.

## Result Model

Normalize iperf JSON into a NorTools result model:

| Field | Meaning |
|---|---|
| `targetHost` / `targetPort` | Resolved endpoint |
| `protocol` | TCP or UDP |
| `durationSeconds` | Actual test duration |
| `sentBitsPerSecond` | Sender throughput |
| `receivedBitsPerSecond` | Receiver throughput where available |
| `retransmits` | TCP retransmits where available |
| `jitterMs` | UDP jitter |
| `lostPackets` / `totalPackets` / `lostPercent` | UDP loss |
| `cpuUtilization` | Local/remote CPU when iperf provides it |
| `rawJson` | Collapsible diagnostics payload |
| `warnings` | Busy server, timeout, unsupported option, parse mismatch |

The UI should show a compact headline result plus tabs for intervals, raw JSON, and
the exact iperf command arguments used.

## Safety and Abuse Controls

The UI can generate high traffic. Keep defaults conservative:

- No automatic tests against public servers.
- No server auto-start on application launch.
- Require explicit Start for server mode.
- Require explicit Run for client tests.
- Cap duration, parallel streams, and UDP bitrate.
- Show a warning when testing public servers.
- Kill child processes on app shutdown.
- Prevent multiple simultaneous client tests unless explicitly added later.
- Treat mDNS TXT data as untrusted display data.
- Do not allow free-form iperf args in the first implementation.

## Error Handling

Common errors should have specific messages:

| Error | User-facing handling |
|---|---|
| iperf binary missing | Explain packaging/dev environment issue and searched paths |
| Server busy | Show as endpoint busy; suggest retrying later |
| Connection refused | Server not running or wrong port |
| Timeout | No response within configured timeout |
| Unsupported option | Disable option or show iperf version requirement |
| Permission/firewall | Explain local OS firewall may block server mode |
| Parse failure | Show raw output and keep process exit code |

## Implementation Plan

1. Add `lib/iperf` models and command builder.
2. Add `IperfBinaryLocator` and process execution wrapper.
3. Add a CLI smoke target that runs `iperf3 --version` and parses metadata.
4. Add desktop route for client tests against manual targets.
5. Add desktop route for server start/stop and process state.
6. Publish running server via `_iperf3._tcp.local.` using `lib/zeroconf`.
7. Add local discovery route that browses `_iperf3._tcp.local.`.
8. Add bundled public server catalog and UI picker.
9. Add iperf page in the web UI.
10. Add tests for command construction, catalog parsing, JSON result parsing, and
    process lifecycle cancellation.

## Test Strategy

Unit tests:

- Command argument construction rejects invalid ports, durations, and protocols.
- JSON parser handles TCP, UDP, reverse mode, and busy-server errors.
- Public catalog parser rejects malformed port ranges.
- mDNS TXT parser tolerates unknown keys and invalid values.

Integration tests:

- Start local `iperf3 -s` on a random port and run a short client test.
- Start server through `IperfProcessManager`, stop it, and confirm the port closes.
- Verify server advertisement is created and removed with server lifecycle where the
  platform permits multicast tests.

Release checks:

- Windows zip contains `iperf3.exe` and required DLLs.
- Linux/macOS archives contain executable `iperf3`.
- `iperf3 --version` works from an extracted package.

## Open Decisions

- Whether to keep `_iperf3._tcp.local.` as the final custom service type or also browse
  `_nortools-iperf3._tcp.local.` for stricter NorTools-only discovery.
- Whether server mode should support multiple ports/processes in the first UI.
- Whether public server catalog updates should be manual download, release-time update,
  or remote fetch with user consent.
- Whether UDP tests should require a user-entered bitrate before running.

