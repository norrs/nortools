---
title: DNS Tools
---

# DNS Tools

Use DNS tools when you need to see what records a domain publishes.

## Quick Commands

```bash
nortools a example.com
nortools aaaa example.com
nortools mx example.com
nortools txt example.com
nortools ns example.com
nortools soa example.com
nortools cname www.example.com
nortools srv _sip._tcp.example.com
nortools ptr 8.8.8.8
```

Add JSON:

```bash
nortools mx --json example.com
```

Use a specific resolver:

```bash
nortools mx --server 1.1.1.1 example.com
```

## In The UI

UI path: Home -> DNS Lookup

The DNS Lookup page lets you choose record type, enter a name, and inspect records in a table or JSON view.

![DNS lookup page](/screenshots/02-dns-lookup.png)

## For Network Engineers

Use `--server` when comparing resolver behavior, and `--json` when preserving exact TTLs and record data.
