# Install NorTools

NorTools is distributed as a desktop application and as a command-line executable inside the release archives.

## Download

Download the latest release from GitHub:

```text
https://github.com/norrs/nortools/releases/latest
```

Choose the package for your operating system.

## Desktop App

Use the desktop app when you want guided forms, tabs, health summaries, and visual results.

After installation, open NorTools and start from the home screen. The docs for each tool include the matching UI path.

## Command Line

Use the executable when you want repeatable diagnostics.

```bash
nortools mx example.com
nortools https example.com
nortools domain-health example.com
```

Add `--json` when you want machine-readable output.

```bash
nortools mx --json example.com
```

## Developer Builds

Contributor commands use Bazel and are separate from end-user commands.

```bash
bazelisk run //tools/dns/mx -- example.com
```
