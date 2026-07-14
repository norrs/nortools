# Desktop UI

The desktop app presents diagnostics as guided pages.

## Common Flow

1. Open NorTools.
2. Choose a tool from the home screen.
3. Enter a domain, host, URL, IP address, CIDR, or file content.
4. Run the check.
5. Read the summary first, then inspect details and JSON when needed.

## UI And CLI Mapping

Every tool page includes a line like:

```text
UI path: Home -> DNS Lookup
Equivalent CLI: nortools mx example.com
```

Use the UI when you need help interpreting results. Use the CLI when you already know what you want to ask.

![NorTools home screen](/screenshots/01-home.png)
