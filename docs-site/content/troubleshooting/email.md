# Email Troubleshooting

Start with the broad report:

```bash
nortools deliverability example.com
```

Then inspect individual policies:

```bash
nortools spf example.com
nortools dkim --discover example.com
nortools dmarc example.com
nortools mta-sts example.com
nortools tlsrpt example.com
```
