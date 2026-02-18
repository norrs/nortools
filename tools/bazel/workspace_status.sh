#!/usr/bin/env bash
set -euo pipefail

git_commit="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
git_branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
git_describe="$(git describe --tags --always 2>/dev/null || echo unknown)"
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
  git_dirty="true"
  scm_status="Modified"
else
  git_dirty="false"
  scm_status="Clean"
fi

# Conventional keys consumed by Java build info stamping.
echo "BUILD_SCM_REVISION ${git_commit}"
echo "BUILD_SCM_STATUS ${scm_status}"
echo "BUILD_SCM_BRANCH ${git_branch}"

# Project-specific keys for direct status access.
echo "STABLE_GIT_COMMIT ${git_commit}"
echo "STABLE_GIT_BRANCH ${git_branch}"
echo "STABLE_GIT_DIRTY ${git_dirty}"
echo "STABLE_GIT_DESCRIBE ${git_describe}"
