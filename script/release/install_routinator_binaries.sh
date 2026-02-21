#!/usr/bin/env bash
set -euo pipefail

# Install prebuilt Routinator binaries into classpath resources so they can be embedded.
#
# Usage:
#   script/release/install_routinator_binaries.sh \
#     --linux-x64 /path/to/routinator-linux-x64 \
#     --linux-arm64 /path/to/routinator-linux-arm64 \
#     --macos-x64 /path/to/routinator-macos-x64 \
#     --macos-arm64 /path/to/routinator-macos-arm64 \
#     --windows-x64 /path/to/routinator.exe

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEST_ROOT="$ROOT_DIR/tools/whois/asn/src/main/resources/native/routinator"

linux_x64=""
linux_arm64=""
macos_x64=""
macos_arm64=""
windows_x64=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --linux-x64) linux_x64="${2:-}"; shift 2 ;;
    --linux-arm64) linux_arm64="${2:-}"; shift 2 ;;
    --macos-x64) macos_x64="${2:-}"; shift 2 ;;
    --macos-arm64) macos_arm64="${2:-}"; shift 2 ;;
    --windows-x64) windows_x64="${2:-}"; shift 2 ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

install_one() {
  local src="$1"
  local rel_dir="$2"
  local filename="$3"
  if [[ -z "$src" ]]; then
    return 0
  fi
  if [[ ! -f "$src" ]]; then
    echo "Binary not found: $src" >&2
    exit 1
  fi
  local out_dir="$DEST_ROOT/$rel_dir"
  mkdir -p "$out_dir"
  cp "$src" "$out_dir/$filename"
  chmod +x "$out_dir/$filename" || true
  echo "Installed: $out_dir/$filename"
}

install_one "$linux_x64" "linux-x64" "routinator"
install_one "$linux_arm64" "linux-arm64" "routinator"
install_one "$macos_x64" "macos-x64" "routinator"
install_one "$macos_arm64" "macos-arm64" "routinator"
install_one "$windows_x64" "windows-x64" "routinator.exe"
