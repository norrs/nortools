#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/testlib.sh"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "SKIP: native linux CLI smoke test only runs on Linux."
  exit 0
fi

if [[ $# -lt 3 ]]; then
  echo "Usage: $0 <nortools-linux-x64.tar.gz> <label> <jq-binary>" >&2
  exit 2
fi

tarball="$1"
shift
label="$1"
shift
jq_bin="$1"
shift

assert_file_exists "$tarball"
assert_file_exists "$jq_bin"
assert_executable "$jq_bin"

mode_file="$(runfiles_resolve "_main/cli_native/smoke/modes/${label}.mode" || true)"
expected_file="$(runfiles_resolve "_main/cli_native/smoke/assertions/${label}.expected" || true)"
jq_assert_file="$(runfiles_resolve "_main/cli_native/smoke/assertions/${label}.jq" || true)"
args_file="$(runfiles_resolve "_main/cli_native/smoke/args/${label}.args" || true)"

assert_file_exists "$mode_file"
assert_file_exists "$expected_file"
assert_file_exists "$jq_assert_file"
assert_file_exists "$args_file"

mode="$(head -n1 "$mode_file")"
expected_substring="$(head -n1 "$expected_file")"
jq_assert_exprs=()
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  [[ -z "$line" ]] && continue
  [[ "$line" == \#* ]] && continue
  jq_assert_exprs+=("$line")
done < "$jq_assert_file"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

tar -xzf "$tarball" -C "$workdir"
binary="$workdir/nortools"
assert_executable "$binary"

raw_args=()
mapfile -t raw_args < "$args_file"
rewrite_args=()
for arg in "${raw_args[@]}"; do
  [[ -n "$arg" ]] || continue
  rewrite_args+=("$(resolve_arg_if_runfile "$arg")")
done

echo "Running native smoke: $label"

set +e
output="$($binary "${rewrite_args[@]}" 2>&1)"
rc=$?
set -e

if [[ $rc -ne 0 ]]; then
  echo "$output" | tail -n 80 >&2
  fail "tool failed (label=$label, exit=$rc)"
fi

assert_non_empty "$output" "tool output ($label)"
if [[ "$mode" == "json" ]]; then
  sanitized_output="$(strip_terminal_escapes "$output")"
  json_payload="$(extract_json_payload "$jq_bin" "$sanitized_output" || true)"
  if [[ -z "${json_payload//[[:space:]]/}" ]]; then
    echo "Raw tool output (last 120 lines):" >&2
    echo "$output" | tail -n 120 >&2
    fail "json payload ($label) is empty"
  fi
  assert_non_empty "$json_payload" "json payload ($label)"
  assert_json_valid "$jq_bin" "$json_payload"
  assert_json_non_empty_payload "$jq_bin" "$json_payload"
  for jq_assert_expr in "${jq_assert_exprs[@]}"; do
    [[ "$jq_assert_expr" == "--" ]] && continue
    assert_json_matches_jq_expr "$jq_bin" "$json_payload" "$jq_assert_expr"
  done
  if [[ "$expected_substring" != "--" ]]; then
    if ! json_contains_text "$jq_bin" "$json_payload" "$expected_substring"; then
      echo "WARN: JSON output did not contain expected text: $expected_substring" >&2
    fi
  fi
elif [[ "$mode" == "text" ]]; then
  for jq_assert_expr in "${jq_assert_exprs[@]}"; do
    if [[ "$jq_assert_expr" != "--" ]]; then
      fail "jq assertion is only supported in json mode"
    fi
  done
  if [[ "$expected_substring" != "--" ]]; then
    if ! grep -Fqi -- "$expected_substring" <<< "$output"; then
      fail "text output did not contain expected text: $expected_substring"
    fi
  fi
else
  fail "unknown assertion mode: $mode"
fi

echo "PASS: $label"
