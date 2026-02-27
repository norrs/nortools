#!/usr/bin/env bash
set -euo pipefail

WHOIS_BIN="$1"

run_lookup() {
  local query="$1"
  echo "== WHOIS $query =="
  "$WHOIS_BIN" "$query"
}

assert_contains() {
  local haystack="$1"
  local pattern="$2"
  local context="$3"
  if ! grep -Eq "$pattern" <<<"$haystack"; then
    echo "Assertion failed for $context"
    echo "Expected pattern: $pattern"
    echo "Output:"
    echo "$haystack"
    exit 1
  fi
}

out_com="$(run_lookup "example.com")"
assert_contains "$out_com" "^Query[[:space:]]+example\\.com$" "example.com query"
assert_contains "$out_com" "^WHOIS Server[[:space:]]+" "example.com server"

out_no="$(run_lookup "example.no")"
assert_contains "$out_no" "^Query[[:space:]]+example\\.no$" "example.no query"
assert_contains "$out_no" "^Terms URL[[:space:]]+https://www\\.norid\\.no/en/domeneoppslag/vilkar$" "example.no terms url"
assert_contains "$out_no" "^Disclaimer[[:space:]]+Norid AS holds the copyright" "example.no disclaimer"

out_org="$(run_lookup "gathering.org")"
assert_contains "$out_org" "^Query[[:space:]]+gathering\\.org$" "gathering.org query"
assert_contains "$out_org" "Lookup Chain[[:space:]]+whois\\.pir\\.org -> whois\\.networksolutions\\.com" "gathering.org chain"
assert_contains "$out_org" "^Terms of Use[[:space:]]+" "gathering.org terms"

out_ip="$(run_lookup "8.8.8.8")"
assert_contains "$out_ip" "^Query[[:space:]]+8\\.8\\.8\\.8$" "8.8.8.8 query"
assert_contains "$out_ip" "^WHOIS Server[[:space:]]+" "8.8.8.8 server"
assert_contains "$out_ip" "(NetRange|CIDR|OrgName)" "8.8.8.8 network fields"

echo "WHOIS manual integration checks passed"
