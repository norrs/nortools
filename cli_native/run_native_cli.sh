#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <tarball> <command> [args...]" >&2
  exit 1
fi

tarball="$1"
shift

if [[ ! -f "$tarball" ]]; then
  echo "ERROR: $tarball not found. The native image may not have built correctly." >&2
  exit 1
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

resolve_runfile() {
  local path="$1"
  if [[ -n "${RUNFILES_DIR:-}" && -e "${RUNFILES_DIR}/${path}" ]]; then
    printf "%s" "${RUNFILES_DIR}/${path}"
    return 0
  fi
  if [[ -n "${RUNFILES_MANIFEST_FILE:-}" && -f "${RUNFILES_MANIFEST_FILE}" ]]; then
    local line
    line="$(grep -m1 "^${path} " "${RUNFILES_MANIFEST_FILE}" || true)"
    if [[ -n "$line" ]]; then
      printf "%s" "${line#* }"
      return 0
    fi
  fi
  return 1
}

rewrite_args=()
for arg in "$@"; do
  if [[ "$arg" == *"/"* ]]; then
    if [[ -e "$arg" ]]; then
      rewrite_args+=("$(realpath "$arg")")
      continue
    fi
    if [[ -e "$PWD/$arg" ]]; then
      rewrite_args+=("$(realpath "$PWD/$arg")")
      continue
    fi
  fi
  if [[ "$arg" == *"/"* ]]; then
    if resolved="$(resolve_runfile "$arg")"; then
      rewrite_args+=("$resolved")
      continue
    fi
  fi
  rewrite_args+=("$arg")
done
set -- "${rewrite_args[@]}"

tar -xzf "$tarball" -C "$tmpdir"
cd "$tmpdir"

exec ./nortools "$@"
