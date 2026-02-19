# Native CLI Smoke Specs

This folder contains the per-test smoke configuration used by:
- `//cli_native:native_linux_cli_all_tools_smoke`
- `//cli_native:native_linux_cli_smoke_suite`

Test names are defined in `test_names.bzl` (`SMOKE_TEST_NAMES`) and used as the single source of truth.

Each test is identified by its target name, for example:
- `native_linux_cli_smoke_http`

For each test name, keep these files in sync:

- `args/<name>.args`: one CLI arg per line.
- `modes/<name>.mode`: either `json` or `text`.
- `assertions/<name>.expected`: expected substring (or `--` to disable).
- `assertions/<name>.jq`: jq assertion expressions, one per line.

## jq Assertions

- Only used when mode is `json`.
- Each non-empty line in `.jq` is evaluated as an independent assertion.
- All assertions must pass.
- Lines starting with `#` are treated as comments.
- Use `--` to indicate no jq assertion on a line.

## Examples

`args/native_linux_cli_smoke_http.args`

```
http
--json
http://example.com
```

`modes/native_linux_cli_smoke_http.mode`

```
json
```

`assertions/native_linux_cli_smoke_http.expected`

```
--
```

`assertions/native_linux_cli_smoke_http.jq`

```
# object has enough fields
length>=3

# URL field exists
has("URL")
```
