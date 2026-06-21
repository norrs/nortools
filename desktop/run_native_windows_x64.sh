#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <zipfile> <runner.ps1> [args...]" >&2
  exit 1
fi

if [[ "${OS:-}" != "Windows_NT" ]]; then
  echo "ERROR: run-native-windows-x64 is intended for Windows hosts only." >&2
  exit 1
fi

zipfile="$1"
shift
ps1="$1"
shift

resolve_runfile() {
  local path="$1"

  if [[ -f "$path" ]]; then
    printf '%s\n' "$path"
    return 0
  fi

  if [[ -n "${RUNFILES_MANIFEST_FILE:-}" && -f "${RUNFILES_MANIFEST_FILE}" ]]; then
    local resolved
    resolved="$(awk -v key="$path" -v main="_main/$path" '
      $1 == key || $1 == main {
        print substr($0, index($0, $2))
        exit
      }
    ' "${RUNFILES_MANIFEST_FILE}")"

    if [[ -n "$resolved" && -f "$resolved" ]]; then
      printf '%s\n' "$resolved"
      return 0
    fi
  fi

  printf '%s\n' "$path"
}

zipfile="$(resolve_runfile "$zipfile")"
ps1="$(resolve_runfile "$ps1")"

if [[ ! -f "$zipfile" ]]; then
  echo "ERROR: $zipfile not found. The native image may not have built correctly." >&2
  exit 1
fi

if [[ ! -f "$ps1" ]]; then
  echo "ERROR: runner script not found: $ps1" >&2
  exit 1
fi

if ! command -v powershell >/dev/null 2>&1; then
  echo "ERROR: powershell not found on PATH." >&2
  exit 1
fi

if command -v cygpath >/dev/null 2>&1; then
  zipfile="$(cygpath -w "$zipfile")"
  ps1="$(cygpath -w "$ps1")"
fi

exec powershell -NoProfile -ExecutionPolicy Bypass -File "$ps1" -ZipFile "$zipfile" "$@"
