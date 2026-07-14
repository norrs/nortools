#!/usr/bin/env bash
set -euo pipefail

mode=""
output=""
bindir=""
node_locations=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      mode="$2"
      shift 2
      ;;
    --output)
      output="$2"
      shift 2
      ;;
    --bindir)
      bindir="$2"
      shift 2
      ;;
    --node-locations)
      node_locations="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$mode" || -z "$output" || -z "$bindir" || -z "$node_locations" ]]; then
  echo "Usage: bazel-docs.sh --mode <generate|check|build> --output <path> --bindir <path> --node-locations <paths>" >&2
  exit 2
fi

case "$mode" in
  generate|check|build)
    ;;
  *)
    echo "Unsupported mode: $mode" >&2
    exit 2
    ;;
esac

root="$PWD"
output_path="$root/$output"
mkdir -p "$(dirname "$output_path")"

node=""
for candidate in $node_locations; do
  case "$candidate" in
    */node|*node.exe)
      node="$root/$candidate"
      ;;
  esac
done

if [[ -z "$node" ]]; then
  echo "Unable to locate node from: $node_locations" >&2
  exit 1
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/nortools-docs-site.XXXXXX")"
trap 'rm -rf "$work"' EXIT

cp -RL "$root/docs-site" "$work/docs-site"
cp -L "$root/README.md" "$work/README.md"
cp -L "$root/RFC.md" "$work/RFC.md"

mkdir -p "$work/cli_native/smoke"
cp -RL "$root/cli_native/smoke/args" "$work/cli_native/smoke/args"
cp -L "$root/cli_native/smoke/test_names.bzl" "$work/cli_native/smoke/test_names.bzl"

mkdir -p "$work/web/src/main/kotlin/no/norrs/nortools/web"
cp -L \
  "$root/web/src/main/kotlin/no/norrs/nortools/web/WebPortal.kt" \
  "$work/web/src/main/kotlin/no/norrs/nortools/web/WebPortal.kt"

mkdir -p "$work/docs"
cp -RL "$root/docs/screenshots" "$work/docs/screenshots"
mkdir -p "$work/docs/assets"
cp -L "$root/docs/assets/nortools-logo.png" "$work/docs/assets/nortools-logo.png"

cd "$work/docs-site"
export BAZEL_BINDIR="$root/$bindir"

"$node" scripts/generate-tool-reference.mjs

if [[ "$mode" == "check" || "$mode" == "build" ]]; then
  "$node" scripts/check-docs.mjs
fi

if [[ "$mode" == "build" ]]; then
  hugo_bin=""
  if [[ -x "$work/docs-site/node_modules/hugo-bin/vendor/hugo" ]]; then
    hugo_bin="$work/docs-site/node_modules/hugo-bin/vendor/hugo"
  elif command -v hugo >/dev/null 2>&1; then
    hugo_bin="$(command -v hugo)"
  fi

  if [[ -z "$hugo_bin" ]]; then
    echo "Unable to locate hugo in docs-site node_modules or PATH" >&2
    exit 1
  fi

  "$hugo_bin" --minify --cleanDestinationDir
  test -f "$work/docs-site/public/index.html"
  test -f "$work/docs-site/public/index.xml"
  tar -C "$work/docs-site/public" -czf "$output_path" .
else
  printf '%s\n' "$mode" > "$output_path"
fi
