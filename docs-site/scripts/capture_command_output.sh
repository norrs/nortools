#!/usr/bin/env bash
set -euo pipefail

workspace="${BUILD_WORKSPACE_DIRECTORY:-}"
if [[ -z "$workspace" ]]; then
  echo "ERROR: run this through bazel run //docs-site:capture_command_output" >&2
  exit 1
fi

cd "$workspace"

runfiles="${RUNFILES_DIR:-$0.runfiles}"
if [[ ! -d "$runfiles" ]]; then
  echo "ERROR: unable to locate Bazel runfiles for @pnpm//:pnpm" >&2
  exit 1
fi

pnpm="$(find "$runfiles" -path '*/pnpm_/pnpm' -print -quit)"
if [[ -z "$pnpm" ]]; then
  echo "ERROR: unable to locate @pnpm//:pnpm in Bazel runfiles" >&2
  exit 1
fi

export BAZEL_BINDIR="${BAZEL_BINDIR:-.}"
exec "$pnpm" --dir "$workspace/docs-site" docs:capture
