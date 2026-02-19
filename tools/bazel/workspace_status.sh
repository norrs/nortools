#!/usr/bin/env bash
set -euo pipefail

git_commit="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
git_branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
git_describe="$(git describe --tags --always 2>/dev/null || echo unknown)"
krema_version="$(
  printf '%s' "$git_describe" \
    | sed -E 's/^[vV]//; s/\.\+/./g; s/\+/./g; s/\.{2,}/./g; s/-.*$//; s/^\.//; s/\.$//'
)"
# Backward compatibility: convert 0.0.YYYYMMDDNNN -> 0.0.YYMMDDNNN.
if printf '%s' "$krema_version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]{11}$'; then
  krema_version="$(printf '%s' "$krema_version" | awk -F. '{printf "%s.%s.%s", $1, $2, substr($3, 3)}')"
fi
if [ -z "$krema_version" ] || [ "$krema_version" = "unknown" ]; then
  krema_version="0.0.0"
fi
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
echo "STABLE_KREMA_VERSION ${krema_version}"
