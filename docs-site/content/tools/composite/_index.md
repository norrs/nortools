---
title: Composite Report Tools
---

# Composite Report Tools

Composite tools combine several lower-level checks into a report.

## Quick Commands

```bash
nortools domain-health example.com
nortools deliverability example.com
nortools compliance example.com
nortools dmarc-report report.xml
nortools mailflow example.com
nortools bulk domains.txt --type MX
```

## In The UI

UI paths: Home -> Domain Health, or Home -> DNS Health.

![Domain health page](/screenshots/13-domain-health.png)

## For Network Engineers

Composite reports trade depth for speed. Use individual tools when you need protocol-specific detail, and use `--json` when preserving results.
