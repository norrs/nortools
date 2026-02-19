#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

assert_file_exists() {
  local path="$1"
  [[ -f "$path" ]] || fail "file not found: $path"
}

assert_executable() {
  local path="$1"
  [[ -x "$path" ]] || fail "file is not executable: $path"
}

assert_non_empty() {
  local value="$1"
  local label="${2:-value}"
  [[ -n "${value//[[:space:]]/}" ]] || fail "$label is empty"
}

assert_json_valid() {
  local jq_bin="$1"
  local json="$2"
  echo "$json" | "$jq_bin" -e . >/dev/null || fail "output is not valid JSON"
}

assert_json_non_empty_payload() {
  local jq_bin="$1"
  local json="$2"
  echo "$json" | "$jq_bin" -e 'if type == "array" then length > 0 elif type == "object" then (keys | length) > 0 else false end' >/dev/null \
    || fail "JSON payload is empty"
}

assert_json_contains_text() {
  local jq_bin="$1"
  local json="$2"
  local needle="$3"
  echo "$json" | "$jq_bin" -e --arg needle "$needle" 'tostring | ascii_downcase | contains($needle | ascii_downcase)' >/dev/null \
    || fail "JSON output does not contain expected text: $needle"
}

json_contains_text() {
  local jq_bin="$1"
  local json="$2"
  local needle="$3"
  echo "$json" | "$jq_bin" -e --arg needle "$needle" 'tostring | ascii_downcase | contains($needle | ascii_downcase)' >/dev/null
}

assert_json_matches_jq_expr() {
  local jq_bin="$1"
  local json="$2"
  local expr="$3"
  echo "$json" | "$jq_bin" -e "$expr" >/dev/null || fail "JSON output failed jq assertion: $expr"
}

extract_json_payload() {
  local jq_bin="$1"
  local text="$2"

  if echo "$text" | "$jq_bin" -e . >/dev/null 2>&1; then
    printf "%s" "$text"
    return 0
  fi

  local lines=()
  mapfile -t lines <<< "$text"
  local total="${#lines[@]}"
  local s e candidate line trimmed first_char

  for (( s=0; s<total; s++ )); do
    line="${lines[$s]}"
    trimmed="${line#"${line%%[![:space:]]*}"}"
    first_char="${trimmed:0:1}"
    if [[ "$first_char" != "{" && "$first_char" != "[" ]]; then
      continue
    fi
    for (( e=total-1; e>=s; e-- )); do
      line="${lines[$e]}"
      trimmed="${line#"${line%%[![:space:]]*}"}"
      first_char="${trimmed:0:1}"
      if [[ "$first_char" != "}" && "$first_char" != "]" ]]; then
        continue
      fi
      candidate="$(printf "%s\n" "${lines[@]:$s:$((e-s+1))}")"
      if echo "$candidate" | "$jq_bin" -e . >/dev/null 2>&1; then
        printf "%s" "$candidate"
        return 0
      fi
    done
  done

  return 1
}

runfiles_resolve() {
  local path="$1"

  if [[ -n "${RUNFILES_DIR:-}" && -e "${RUNFILES_DIR}/${path}" ]]; then
    printf "%s" "${RUNFILES_DIR}/${path}"
    return 0
  fi

  if [[ -n "${RUNFILES_MANIFEST_FILE:-}" && -f "${RUNFILES_MANIFEST_FILE}" ]]; then
    local line
    line="$(grep -m1 "^${path} " "${RUNFILES_MANIFEST_FILE}" || true)"
    if [[ -n "$line" ]]; then
      printf "%s" "${line#* }"
      return 0
    fi
  fi

  return 1
}

resolve_arg_if_runfile() {
  local arg="$1"

  if [[ "$arg" != *"/"* ]]; then
    printf "%s" "$arg"
    return 0
  fi

  if [[ -e "$arg" ]]; then
    realpath "$arg"
    return 0
  fi

  if [[ -e "$PWD/$arg" ]]; then
    realpath "$PWD/$arg"
    return 0
  fi

  local resolved
  resolved="$(runfiles_resolve "$arg" || true)"
  if [[ -n "$resolved" ]]; then
    printf "%s" "$resolved"
    return 0
  fi

  printf "%s" "$arg"
}
