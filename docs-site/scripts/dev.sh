#!/usr/bin/env bash
set -euo pipefail

workspace="${BUILD_WORKSPACE_DIRECTORY:-}"
if [[ -z "$workspace" ]]; then
  echo "ERROR: run this through bazel run //docs-site:dev" >&2
  exit 1
fi

cd "$workspace"
exec bazelisk run @pnpm//:pnpm -- --dir "$workspace/docs-site" docs:dev
