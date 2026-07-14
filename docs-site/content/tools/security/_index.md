---
title: Security And Blocklist Tools
---

# Security And Blocklist Tools

These tools check DNS-based lists and DNS records used for security metadata.

## Quick Commands

```bash
nortools blacklist 203.0.113.1
nortools blocklist example.com
nortools cert example.com
nortools loc example.com
nortools ipseckey example.com
```

## In The UI

UI path: Home -> Blacklist.

## What The Result Means

A blocklist hit means the queried IP or domain appears on at least one list. It does not always mean abuse is currently happening. Check the list provider's policy before acting.

## For Network Engineers

DNSBL and URI blocklist results are DNS answers. Treat timeouts, NXDOMAIN, and listing records distinctly in incident notes.
