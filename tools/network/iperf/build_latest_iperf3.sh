#!/usr/bin/env bash
set -euo pipefail

CONFIGURE_PATH="${1:-}"
OUT_PATH="${2:-}"

if [[ -z "$CONFIGURE_PATH" || -z "$OUT_PATH" ]]; then
  echo "Usage: $0 <iperf-configure-path> <output-path>" >&2
  exit 2
fi

for tool in make cc; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is required to build iperf3 from Bazel-provided source" >&2
    exit 1
  fi
done

SRC_ROOT="$(cd "$(dirname "$CONFIGURE_PATH")" && pwd)"
WORKDIR="$(mktemp -d)"
cleanup() {
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

BUILD_DIR="$WORKDIR/iperf-src"
mkdir -p "$BUILD_DIR"
cp -R "$SRC_ROOT/." "$BUILD_DIR/"
chmod -R u+w "$BUILD_DIR"

(
  cd "$BUILD_DIR"
  if [[ ! -x ./configure ]]; then
    if [[ -x ./bootstrap.sh ]]; then
      ./bootstrap.sh
    else
      echo "iperf source does not contain configure or bootstrap.sh" >&2
      exit 1
    fi
  fi
  ./configure --disable-shared --enable-static --without-sctp
  make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 2)"
)

BIN_PATH="$BUILD_DIR/src/iperf3"
if [[ ! -f "$BIN_PATH" ]]; then
  echo "iperf3 build succeeded but binary is missing: $BIN_PATH" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT_PATH")"
cp "$BIN_PATH" "$OUT_PATH"
chmod +x "$OUT_PATH"

echo "Built iperf3 from Bazel-provided source -> $OUT_PATH"
