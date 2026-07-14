---
title: WHOIS, RDAP, ASN, And RPKI
---

# WHOIS, RDAP, ASN, And RPKI

Use these tools to identify registration, network ownership, ASN data, and route origin validity.

## Quick Commands

```bash
nortools whois example.com
nortools arin 8.8.8.8
nortools asn AS13335
nortools asn 1.1.1.1
```

## In The UI

UI paths: Home -> WHOIS Lookup, or Home -> RPKI Route.

## For Network Engineers

`asn` combines ASN lookup, RDAP context, and optional route origin validation. Use it when checking whether an IP prefix appears to be originated by the expected ASN.
