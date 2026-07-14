# TLS Troubleshooting

Start with:

```bash
nortools https example.com
```

Check:

- Does the certificate match the hostname?
- Is the certificate expired or close to expiry?
- Is the chain trusted?
- Does SNI change the certificate?
- Which TLS protocol and cipher suite were negotiated?
- Did the server negotiate ALPN?
