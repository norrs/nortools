#!/usr/bin/env bash
set -euo pipefail

OUT_PATH="${1:-}"
if [[ -z "$OUT_PATH" ]]; then
  echo "Usage: $0 <output-path>" >&2
  exit 2
fi

if command -v iperf3 >/dev/null 2>&1; then
  mkdir -p "$(dirname "$OUT_PATH")"
  cp "$(command -v iperf3)" "$OUT_PATH"
  chmod +x "$OUT_PATH"
  echo "Using existing iperf3 -> $OUT_PATH"
  exit 0
fi

for tool in curl tar make cc; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is required when iperf3 is not already installed" >&2
    exit 1
  fi
done

API_URL="https://api.github.com/repos/esnet/iperf/releases/latest"
AUTH_ARGS=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  AUTH_ARGS=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

if [[ ${#AUTH_ARGS[@]} -gt 0 ]]; then
  LATEST_JSON="$(curl -fsSL "${AUTH_ARGS[@]}" "$API_URL")"
else
  LATEST_JSON="$(curl -fsSL "$API_URL")"
fi
TAG="$(printf '%s\n' "$LATEST_JSON" | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
if [[ -z "$TAG" ]]; then
  echo "Could not resolve latest iperf tag from GitHub API" >&2
  exit 1
fi
ARCHIVE_URL="$(printf '%s\n' "$LATEST_JSON" | sed -n 's/.*"browser_download_url"[[:space:]]*:[[:space:]]*"\([^"]*iperf-[^"]*\.tar\.gz\)".*/\1/p' | head -n1)"
if [[ -z "$ARCHIVE_URL" ]]; then
  ARCHIVE_URL="https://github.com/esnet/iperf/archive/refs/tags/${TAG}.tar.gz"
fi

WORKDIR="$(mktemp -d)"
cleanup() {
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

ARCHIVE="$WORKDIR/iperf.tar.gz"
curl -fsSL "$ARCHIVE_URL" -o "$ARCHIVE"
tar -xzf "$ARCHIVE" -C "$WORKDIR"

SRC_DIR="$(find "$WORKDIR" -maxdepth 1 -type d -name 'iperf-*' | head -n1)"
if [[ -z "$SRC_DIR" ]]; then
  echo "Could not locate extracted iperf source directory" >&2
  exit 1
fi

(
  cd "$SRC_DIR"
  if [[ ! -x ./configure ]]; then
    if [[ -x ./bootstrap.sh ]]; then
      ./bootstrap.sh
    else
      echo "iperf source does not contain configure or bootstrap.sh" >&2
      exit 1
    fi
  fi
  ./configure --disable-shared --enable-static
  make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"
)

BIN_PATH="$SRC_DIR/src/iperf3"
if [[ ! -f "$BIN_PATH" ]]; then
  echo "iperf3 build succeeded but binary is missing: $BIN_PATH" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT_PATH")"
cp "$BIN_PATH" "$OUT_PATH"
chmod +x "$OUT_PATH"

echo "Built iperf3 ${TAG} -> $OUT_PATH"
