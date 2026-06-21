# Zero-Configuration Networking Implementation Plan

This plan tracks adding local-network discovery and name-resolution diagnostics to nortools.
The goal is a set of CLI, web, and desktop tools that can explain why a device, service,
or hostname is or is not discoverable on the current network.

## Scope

Zero-configuration networking is not one protocol. For nortools, split the work into
three layers:

1. Link-local name resolution: answer "which address owns this local name?"
2. Service discovery: answer "which services/devices are being advertised?"
3. Diagnostic correlation: explain what was observed across protocols, interfaces,
   multicast groups, TTLs, payloads, and resolver behavior.

## Protocol Map

| Area | Protocol | Transport | Primary purpose | First nortools support |
|---|---|---:|---|---|
| DNS-SD over mDNS | mDNS, DNS-SD, Bonjour | UDP 5353 multicast | Discover service types and instances using DNS records | Active query, passive listen, record decode |
| Link-local DNS name resolution | LLMNR | UDP/TCP 5355 multicast | Resolve single-label names on local links | Active query, passive listen, conflict checks |
| Windows name resolution | NetBIOS Name Service | UDP 137 broadcast | Resolve NetBIOS names and inspect registrations | Name query, node status, passive listen |
| UPnP discovery | SSDP | UDP 1900 multicast | Discover UPnP devices and services | M-SEARCH probe, NOTIFY listener |
| WS-Discovery / WSD | SOAP-over-UDP | UDP 3702 multicast | Discover devices implementing WS-Discovery, including printers/scanners | Probe, Resolve, Hello/Bye listener |
| Device profiles | Devices Profile for Web Services | WS-Discovery plus SOAP/HTTP metadata | Inspect device metadata after discovery | Follow-up metadata fetch where safe |
| Media profile | DLNA | SSDP plus HTTP XML descriptions | Identify DLNA devices and advertised media capabilities | SSDP classification and description parsing |
| Historical Apple local naming | AppleTalk NBP | AppleTalk, not IP | Legacy name binding, not modern Bonjour | Document only unless a concrete need appears |

Notes:

- Apple Bonjour uses mDNS plus DNS-SD. AppleTalk Name Binding Protocol is a historical
  AppleTalk protocol and should not be mixed into the first implementation.
- LLMNR is name resolution, not service discovery. It still belongs in this feature
  because it affects the same "why can I find this device?" debugging workflow.
- NetBIOS Name Service is name registration and resolution. SSDP and WS-Discovery do
  not use NetBIOS, but Windows environments often expose all of these side by side.

## User-Facing Tools

Track these as separate CLI targets so each protocol can be tested independently:

| Status | Tool | Target | Purpose |
|---|---|---|---|
| Planned | `mdns` | `//tools/zeroconf/mdns` | Query and listen for mDNS records on `224.0.0.251` / `ff02::fb` |
| Planned | `dns-sd` | `//tools/zeroconf/dns-sd` | Browse service types and instances, then resolve SRV/TXT/A/AAAA |
| Planned | `llmnr` | `//tools/zeroconf/llmnr` | Query and listen for LLMNR names on IPv4/IPv6 multicast |
| Started | `netbios-ns` | `//tools/zeroconf/netbios-ns` | Query NetBIOS names and node status over UDP 137 |
| Planned | `ssdp` | `//tools/zeroconf/ssdp` | Run SSDP `M-SEARCH`, listen for `NOTIFY`, fetch device descriptions |
| Planned | `ws-discovery` | `//tools/zeroconf/ws-discovery` | Send WS-Discovery Probe/Resolve and listen for Hello/Bye |
| Planned | `zeroconf-scan` | `//tools/composite/zeroconf-scan` | Run a bounded multi-protocol scan and correlate devices/services |

The web/desktop UI should be built incrementally as protocols land. Add one
"ZeroConf Discovery" page early, then expose protocol modules behind the same page and
API route family. This keeps every protocol testable from CLI, web, and desktop during
implementation instead of waiting for the final composite scanner.

## Shared Implementation

Create a shared library:

- `lib/zeroconf`
  - Network interface enumeration with IPv4/IPv6, multicast capability, loopback filtering,
    and per-interface errors.
  - Explicit IP-family selection: IPv4 only, IPv6 only, or both. Default to both where
    the protocol supports it, and report when an interface lacks support for the selected
    family.
  - UDP multicast sender/listener primitives with timeout, TTL/hop-limit, receive loop,
    source address capture, and packet timestamps.
  - A shared result model for observations: protocol, interface, local address, remote
    address, multicast group, port, packet direction, decoded records, raw payload preview,
    warnings, and errors.
  - Safety limits: default scan timeout, max packets, max description fetch bytes,
    no unbounded background listeners in CLI mode.
  - JSON-friendly models so the existing output formatter and web handlers can reuse
    the same results.

Implementation choices:

- Use dnsjava for DNS message encode/decode where it fits mDNS and LLMNR packet format.
- Use Java/Kotlin `DatagramChannel` or `MulticastSocket` for multicast control because
  standard DNS resolvers are not enough for link-local multicast diagnostics.
- Use existing `lib/network` HTTP client patterns for SSDP/DLNA/DPWS description fetches.
- Avoid native packet capture for the first version. Add pcap export/import later only
  if raw socket visibility becomes necessary.
- Include passive listener mode for protocols where announcements or ambient traffic are
  useful: mDNS, DNS-SD, LLMNR, NetBIOS Name Service, SSDP, and WS-Discovery.

## Per-Protocol Design

### mDNS and DNS-SD

Capabilities:

- Query `_services._dns-sd._udp.local.` PTR to enumerate service types.
- Query a selected service type, for example `_http._tcp.local.`, to enumerate instances.
- Resolve instance PTR to SRV, TXT, A, and AAAA records.
- Listen passively for announcements, goodbye records, cache-flush records, TTLs, and
  known-answer behavior.
- Flag common problems: no multicast-capable interface, IPv4 works but IPv6 does not,
  service has SRV but no address, stale goodbye TTL, hostname collision, malformed TXT.

CLI shape:

```bash
bazelisk run //tools/zeroconf/mdns -- browse --timeout 5
bazelisk run //tools/zeroconf/mdns -- query _http._tcp.local. PTR
bazelisk run //tools/zeroconf/mdns -- listen --ip-family both --timeout 10
bazelisk run //tools/zeroconf/dns-sd -- resolve "Printer._ipp._tcp.local."
```

### LLMNR

Capabilities:

- Query A/AAAA for a single-label name over UDP 5355.
- Listen for LLMNR queries/responses.
- Detect duplicate responders for the same name.
- Report whether a queried name falls back to unicast DNS, mDNS, or system resolver behavior
  outside the LLMNR probe.

CLI shape:

```bash
bazelisk run //tools/zeroconf/llmnr -- query laptop
bazelisk run //tools/zeroconf/llmnr -- listen --timeout 10
```

### NetBIOS Name Service

Capabilities:

- Encode/decode NetBIOS names and suffixes.
- Query name records over UDP 137 broadcast.
- Run node status queries to list registered names for a host.
- Flag common Windows discovery issues: missing workstation/server service names,
  duplicate names, workgroup/domain mismatch, no response on selected interface.

CLI shape:

```bash
bazelisk run //tools/zeroconf/netbios-ns -- query MYPC
bazelisk run //tools/zeroconf/netbios-ns -- node-status 192.168.1.25
```

### SSDP, UPnP, and DLNA

Capabilities:

- Send `M-SEARCH` for `ssdp:all`, `upnp:rootdevice`, and selected `urn:` service/device types.
- Listen for `NOTIFY` alive/byebye/update messages.
- Parse response headers: `ST`, `USN`, `LOCATION`, `SERVER`, `CACHE-CONTROL`, `EXT`.
- Fetch and parse device description XML from `LOCATION` with strict timeout and byte limits.
- Classify DLNA devices using SSDP headers and device description fields.
- Flag common problems: multicast request sent but no response, invalid `LOCATION`, private
  address mismatch, expired cache max-age, duplicate USN.

CLI shape:

```bash
bazelisk run //tools/zeroconf/ssdp -- search ssdp:all
bazelisk run //tools/zeroconf/ssdp -- listen --timeout 10
bazelisk run //tools/zeroconf/ssdp -- describe http://192.168.1.10:5000/rootDesc.xml
```

### WS-Discovery, WSD, and DPWS

Capabilities:

- Send WS-Discovery Probe messages to UDP 3702 multicast.
- Send Resolve for endpoint references seen in ProbeMatches or Hello messages.
- Listen for Hello and Bye announcements.
- Parse SOAP envelope, message ID, relates-to, endpoint reference, types, scopes, XAddrs,
  metadata version.
- Optionally fetch metadata from XAddrs with strict limits.
- Flag common printer/scanner discovery issues: no XAddrs, unreachable XAddr, stale metadata,
  SOAP parse errors, endpoint seen on one interface only.

CLI shape:

```bash
bazelisk run //tools/zeroconf/ws-discovery -- probe
bazelisk run //tools/zeroconf/ws-discovery -- listen --timeout 10
bazelisk run //tools/zeroconf/ws-discovery -- resolve urn:uuid:...
```

## Composite Diagnostic Flow

`zeroconf-scan` should run a bounded scan:

1. Enumerate interfaces and choose eligible multicast/broadcast interfaces.
2. Run mDNS service browse and passive listen.
3. Run LLMNR active query only if a name is supplied.
4. Run NetBIOS broadcast query only if a name or IPv4 subnet target is supplied.
5. Run SSDP `M-SEARCH`.
6. Run WS-Discovery Probe.
7. Correlate observations by IP address, hostname, service instance, UUID/USN/EPR, and
   description URL.
8. Emit a summary table plus protocol-specific evidence.

Suggested CLI:

```bash
bazelisk run //tools/composite/zeroconf-scan -- --timeout 8 --json
bazelisk run //tools/composite/zeroconf-scan -- --name printer --include llmnr,netbios,mdns,ssdp,wsd
```

## Web/Desktop UX

Add a `zeroconf-discovery-page.vue` page early with:

- Interface selector.
- Protocol toggles: mDNS/DNS-SD, LLMNR, NetBIOS NS, SSDP, WS-Discovery.
- IP-family selector: IPv4, IPv6, or both.
- Timeout and packet limit controls.
- Passive listener mode for supported protocols, with visible timeout and packet limit
  controls so listeners are bounded by default.
- Scan results grouped by device/service.
- Protocol evidence drawer showing decoded packets and raw payload previews.
- Warning badges for blocked multicast, decode errors, duplicate names, stale TTL/cache,
  and unreachable description URLs.

Backend routes should live in a new `ZeroconfHandlers.kt` and call the same library used
by the CLI tools.

UI implementation order:

1. Add the page, route, navigation entry, and a reusable result table/detail drawer scaffold.
2. Wire NetBIOS Name Service first: query, node status, IPv4-only warning, timeout, and
   bounded listen controls.
3. Add each later protocol to the same page as soon as its library/CLI implementation lands.
4. Keep the composite scan view as an aggregation layer over the protocol modules, not as
   the first UI deliverable.

## Build Integration

Add Bazel packages:

- `lib/zeroconf/BUILD.bazel`
- `tools/zeroconf/mdns/BUILD.bazel`
- `tools/zeroconf/dns-sd/BUILD.bazel`
- `tools/zeroconf/llmnr/BUILD.bazel`
- `tools/zeroconf/netbios-ns/BUILD.bazel`
- `tools/zeroconf/ssdp/BUILD.bazel`
- `tools/zeroconf/ws-discovery/BUILD.bazel`
- `tools/composite/zeroconf-scan/BUILD.bazel`

Update:

- `README.md` tool list and examples.
- `RFC.md` with mDNS, DNS-SD, LLMNR, NetBIOS, SSDP, UPnP, WS-Discovery, DPWS, and DLNA references.
- CLI smoke tests under `cli_native/smoke`.
- Native image reachability/resource config only if XML/SOAP parsing or reflection requires it.

## Test Plan

Unit tests:

- DNS-SD record parsing and service-instance correlation.
- LLMNR packet encode/decode and duplicate responder detection.
- NetBIOS name encoding, suffix parsing, and node-status decode.
- SSDP header parsing and device description XML parsing.
- WS-Discovery SOAP ProbeMatch/ResolveMatch/Hello/Bye parsing.
- Interface filtering and timeout behavior.

Integration-style tests:

- Use local UDP test servers that answer one protocol at a time.
- Avoid relying on real LAN devices in CI.
- Keep live-network scans as manual smoke tests because multicast depends heavily on host OS,
  firewall rules, VPNs, and network interface state.

Manual test matrix:

| OS | mDNS | LLMNR | NetBIOS NS | SSDP | WS-Discovery |
|---|---|---|---|---|---|
| Windows | Required | Required | Required | Required | Required |
| macOS | Required | Optional | Optional | Required | Optional |
| Linux | Required | Optional | Optional | Required | Optional |

## Milestones

- [ ] M1: Add `lib/zeroconf` shared models, interface enumeration, multicast UDP primitives,
  IP-family selection, bounded passive listener support, and deterministic tests.
  - Started: shared IP-family/interface model and NetBIOS deterministic packet tests.
- [ ] M2: Add early web/desktop ZeroConf Discovery harness and `ZeroconfHandlers.kt`.
  - First UI route should support NetBIOS Name Service so web/desktop can test the
    implementation while the remaining protocols are still planned.
- [ ] M3: Add mDNS and DNS-SD active query/listen tools and wire them into the page.
- [ ] M4: Add LLMNR query/listen tool and wire it into the page.
- [ ] M5: Add NetBIOS Name Service query/node-status tool.
  - Started: `netbios-ns` CLI supports name query, node status, bounded passive listen,
    IPv4/IPv6 option reporting, and structured output.
- [ ] M6: Add SSDP search/listen/describe tool with UPnP and DLNA classification and wire
  it into the page.
- [ ] M7: Add WS-Discovery probe/listen/resolve tool with SOAP parsing and wire it into
  the page.
- [ ] M8: Add `zeroconf-scan` composite correlation tool backed by the protocol modules.
- [ ] M9: Update README, RFC reference, CLI smoke tests, screenshots, and release notes.

## Open Decisions

- Default IP-family behavior per protocol. The tool must expose IPv4, IPv6, and both;
  the remaining decision is whether "both" should be the default for every supported protocol.
- Passive listeners are in scope, but must be bounded by timeout and packet count in both
  CLI and web/desktop modes.
- Pcap import/export is deferred until after the first working protocol tools.
- NetBIOS starts with Name Service only. SMB-oriented diagnostics can be added later as a
  separate feature area.
