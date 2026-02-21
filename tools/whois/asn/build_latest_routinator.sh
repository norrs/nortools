#!/usr/bin/env bash
set -euo pipefail

OUT_PATH="${1:-}"
if [[ -z "$OUT_PATH" ]]; then
  echo "Usage: $0 <output-path>" >&2
  exit 2
fi

if ! command -v cargo >/dev/null 2>&1; then
  echo "cargo is required to build Routinator" >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required to download Routinator source" >&2
  exit 1
fi
if ! command -v tar >/dev/null 2>&1; then
  echo "tar is required to extract Routinator source" >&2
  exit 1
fi

API_URL="https://api.github.com/repos/NLnetLabs/routinator/releases/latest"
AUTH_HEADER=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  AUTH_HEADER=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

LATEST_JSON="$(curl -fsSL "${AUTH_HEADER[@]}" "$API_URL")"
TAG="$(printf '%s\n' "$LATEST_JSON" | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
if [[ -z "$TAG" ]]; then
  echo "Could not resolve latest routinator tag from GitHub API" >&2
  exit 1
fi

WORKDIR="$(mktemp -d)"
cleanup() {
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

ARCHIVE="$WORKDIR/routinator.tar.gz"
curl -fsSL "https://github.com/NLnetLabs/routinator/archive/refs/tags/${TAG}.tar.gz" -o "$ARCHIVE"
tar -xzf "$ARCHIVE" -C "$WORKDIR"

SRC_DIR="$(find "$WORKDIR" -maxdepth 1 -type d -name 'routinator-*' | head -n1)"
if [[ -z "$SRC_DIR" ]]; then
  echo "Could not locate extracted Routinator source directory" >&2
  exit 1
fi

(
  cd "$SRC_DIR"
  cargo build --release --locked --bin routinator
)

BIN_PATH="$SRC_DIR/target/release/routinator"
if [[ ! -f "$BIN_PATH" ]]; then
  echo "Routinator build succeeded but binary is missing: $BIN_PATH" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT_PATH")"
cp "$BIN_PATH" "$OUT_PATH"
chmod +x "$OUT_PATH"

echo "Built Routinator ${TAG} -> $OUT_PATH"
