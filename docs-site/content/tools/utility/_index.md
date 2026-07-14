---
title: Utility Tools
---

# Utility Tools

Utility tools cover common operational tasks.

## Quick Commands

```bash
nortools whatismyip
nortools subnet-calc 192.168.1.0/24
nortools password-gen --length 32
nortools email-extract input.txt
nortools dns-propagation example.com
nortools dns-health example.com
```

## In The UI

UI paths: Home -> What Is My IP, Subnet Calculator, Password Generator, Email Extract, or DNS Health.

![Subnet calculator](/screenshots/05-subnet-calculator.png)

## For Network Engineers

Use `dns-health` for structured domain DNS checks. Use `dns-propagation` when comparing answers across public resolvers.
