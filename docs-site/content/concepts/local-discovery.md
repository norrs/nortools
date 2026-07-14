# Local Discovery

Local discovery protocols let devices announce or find services on the same network.

```mermaid
flowchart TD
  Device["Device"] --> MDNS["mDNS / DNS-SD"]
  Device --> SSDP["SSDP"]
  Device --> WSD["WS-Discovery"]
  Device --> NetBIOS["NetBIOS"]
  MDNS --> NorTools["NorTools inventory"]
  SSDP --> NorTools
  WSD --> NorTools
  NetBIOS --> NorTools
```

## Use NorTools

Use ZeroConf Discovery in the UI for a combined local inventory. Use individual CLI tools when you want one protocol at a time.

## For Network Engineers

Local discovery results are best-effort. Firewalls, VLANs, multicast filtering, and host sleep states can all hide devices.
