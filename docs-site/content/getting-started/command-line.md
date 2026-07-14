# Command Line

The released executable is named `nortools`.

## Basic Shape

```bash
nortools <command> [options] <input>
```

Examples:

```bash
nortools a example.com
nortools mx example.com
nortools https example.com
nortools subnet-calc 192.168.1.0/24
```

## Common Options

Many commands support:

```bash
--json
--server 1.1.1.1
--timeout 5
```

`--json` is the best format for scripts and incident notes.

## Full Reference

See [CLI Reference](../reference/cli/) for generated command examples.
