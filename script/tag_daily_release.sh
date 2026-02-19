#!/usr/bin/env bash
set -euo pipefail

# Create and push a tag of form v0.0.YYMMDDNNN.
# Sequence NNN is derived from LOCAL tags for today's date.
# Examples: v0.0.260219001, v0.0.260219002
#
# Usage:
#   script/tag_daily_release.sh
#   script/tag_daily_release.sh --dry-run
#   script/tag_daily_release.sh --remote upstream

REMOTE="origin"
DRY_RUN="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --remote)
      REMOTE="${2:-}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN="true"
      shift
      ;;
    -h|--help)
      cat <<USAGE
Usage: $0 [--remote <name>] [--dry-run]

Creates a tag in format v0.0.YYMMDDNNN based on local tags and pushes it.

Options:
  --remote <name>  Git remote to push to (default: origin)
  --dry-run        Print the tag that would be created/pushed without doing it
USAGE
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$REMOTE" ]]; then
  echo "Remote name cannot be empty" >&2
  exit 2
fi

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "Not inside a git repository" >&2
  exit 1
fi

if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
  echo "Remote '$REMOTE' does not exist" >&2
  exit 1
fi

date_part="$(date +%y%m%d)"
base="v0.0.${date_part}"

# Collect local tags that exactly match v0.0.YYMMDDNNN for today.
existing_today="$({ git tag -l "${base}*" || true; } | sed -n "s/^${base}\([0-9][0-9][0-9]\)$/\1/p")"

next_seq=1
if [[ -n "$existing_today" ]]; then
  max_seq="$(printf '%s\n' "$existing_today" | sort -n | tail -n1)"
  next_seq=$((10#$max_seq + 1))
fi

if (( next_seq > 999 )); then
  echo "Daily sequence exhausted for ${date_part} (max 999)" >&2
  exit 1
fi

tag="$(printf "%s%03d" "$base" "$next_seq")"

if git rev-parse -q --verify "refs/tags/${tag}" >/dev/null 2>&1; then
  echo "Tag already exists locally: ${tag}" >&2
  exit 1
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo "[dry-run] Would create tag: ${tag}"
  echo "[dry-run] Would push tag to ${REMOTE}: ${tag}"
  exit 0
fi

git tag "$tag"
git push "$REMOTE" "$tag"

echo "Created and pushed tag: ${tag}"
