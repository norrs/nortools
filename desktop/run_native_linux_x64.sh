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

tarball="$1"
shift

   if [[ ! -f "$tarball" ]]; then
     echo "ERROR: $tarball not found. The native image may not have built correctly." >&2
     exit 1
   fi

   tmpdir="$(mktemp -d)"
   trap 'rm -rf "$tmpdir"' EXIT

   tar -xzf "$tarball" -C "$tmpdir"
   cd "$tmpdir"

   exec ./nortools "$@"
