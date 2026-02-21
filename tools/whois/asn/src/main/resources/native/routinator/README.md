Embedded Routinator Binary Layout
=================================

Place platform-specific `routinator` binaries in the following paths so NorTools can run
RPKI route origin validation without depending on PATH:

- `native/routinator/linux-x64/routinator`
- `native/routinator/linux-arm64/routinator`
- `native/routinator/macos-x64/routinator`
- `native/routinator/macos-arm64/routinator`
- `native/routinator/windows-x64/routinator.exe`

Runtime resolution order:

1. `--routinator-bin <path>` (CLI flag)
2. `NORTOOLS_ROUTINATOR_BIN` (environment variable)
3. Embedded binary from the paths above
4. `routinator` from PATH

If none is available, route validation is reported as `UNAVAILABLE`.
