#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <tarball> [args...]" >&2
  exit 1
fi

if [[ "${OS:-}" == "Windows_NT" ]]; then
  echo "ERROR: run-native-linux-x64 is intended for Linux hosts only." >&2
  exit 1
fi

resolve_runfile() {
  local path="$1"
  local self="$0"

  if [[ -f "$path" ]]; then
    printf '%s\n' "$path"
    return 0
  fi

  if [[ -n "${RUNFILES_DIR:-}" && -f "${RUNFILES_DIR}/_main/$path" ]]; then
    printf '%s\n' "${RUNFILES_DIR}/_main/$path"
    return 0
  fi

  if [[ -n "${RUNFILES_DIR:-}" && -f "${RUNFILES_DIR}/$path" ]]; then
    printf '%s\n' "${RUNFILES_DIR}/$path"
    return 0
  fi

  if [[ -f "${self}.runfiles/_main/$path" ]]; then
    printf '%s\n' "${self}.runfiles/_main/$path"
    return 0
  fi

  if [[ -f "${self}.runfiles/$path" ]]; then
    printf '%s\n' "${self}.runfiles/$path"
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

tarball="$1"
shift
tarball="$(resolve_runfile "$tarball")"

if [[ ! -f "$tarball" ]]; then
  echo "ERROR: $tarball not found. The native image may not have built correctly." >&2
  exit 1
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

tar -xzf "$tarball" -C "$tmpdir"
cd "$tmpdir"

exec ./nortools "$@"
