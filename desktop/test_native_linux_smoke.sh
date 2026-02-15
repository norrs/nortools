#!/usr/bin/env bash
set -euo pipefail

# Skip on non-Linux to avoid failures on macOS/Windows builders.
if [[ "$(uname -s)" != "Linux" ]]; then
  echo "SKIP: native linux smoke test only runs on Linux."
  exit 0
fi

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <nortools-linux-x64.tar.gz>" >&2
  exit 2
fi

tarball="$1"
if [[ ! -f "$tarball" ]]; then
  echo "ERROR: tarball not found: $tarball" >&2
  exit 3
fi

workdir="$(mktemp -d)"
trap 'kill 0 >/dev/null 2>&1 || true; rm -rf "$workdir"' EXIT

# Extract binary
tar -xzf "$tarball" -C "$workdir"
cd "$workdir"

# Start binary in background; try to hint headless mode if app supports it.
# These envs are best-effort and ignored if unknown.
export KREMA_HEADLESS=1
export NORTOOLS_HEADLESS=1
export NORTOOLS_NO_GUI=1

# Capture logs for debugging on failure. Run headless to avoid WebView/UI.
./nortools --headless > "$workdir/server.log" 2>&1 &
server_pid=$!
echo "Started nortools (pid=$server_pid), logging to $workdir/server.log"

# Helper: try HTTP GET using curl; fall back to bash /dev/tcp if curl absent.
http_get() {
  local host="$1" port="$2" path="$3"
  if command -v curl >/dev/null 2>&1; then
    curl -s -m 5 "http://${host}:${port}${path}" 2>/dev/null || return 1
  else
    # Minimal /dev/tcp fallback (no TLS support)
    exec 3<>"/dev/tcp/${host}/${port}" || return 1
    printf 'GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n' "$path" "$host" >&3
    # Strip HTTP headers; print body
    awk 'BEGIN{hdr=1} { if(hdr){ if($0=="\r"||$0=="") hdr=0 } else { print } }' <&3
    exec 3>&-
  fi
}

# Attempt to discover listening port from the log output
extract_port_from_log() {
  sed -n 's/.*Listening on http:\/\/[^:]*:\([0-9][0-9]*\)\/.*/\1/p' "$workdir/server.log" | tail -n1 || true
}

# Probe advertised port first, then a few common fallbacks
fallback_ports=(8080 7070 7000 8090)
path="/api/dns/A/norrs.no"

echo "Waiting for server to start..."
start_ts="$(date +%s)"
found_port=""
response_body=""
timeout_sec=60

while :; do
  now="$(date +%s)"
  if (( now - start_ts > timeout_sec )); then
    echo "ERROR: Server did not respond within ${timeout_sec}s." >&2
    echo "----- server.log (last 200 lines) -----" >&2
    tail -n 200 "$workdir/server.log" >&2 || true
    exit 4
  fi

  # Try advertised port from logs
  advertised_port="$(extract_port_from_log || true)"
  if [[ -n "${advertised_port:-}" ]]; then
    if body="$(http_get 127.0.0.1 "$advertised_port" "$path" || true)"; then
      if grep -q '"records"' <<<"$body" || grep -q '"type":"A"' <<<"$body" || grep -q '"isSuccessful":' <<<"$body"; then
        found_port="$advertised_port"
        response_body="$body"
        break
      fi
    fi
  fi

  # Fallback ports
  for p in "${fallback_ports[@]}"; do
    if body="$(http_get 127.0.0.1 "$p" "$path" || true)"; then
      if grep -q '"records"' <<<"$body" || grep -q '"type":"A"' <<<"$body" || grep -q '"isSuccessful":' <<<"$body"; then
        found_port="$p"
        response_body="$body"
        break
      fi
    fi
  done
  [[ -n "$found_port" ]] && break

  # Quick check if process died
  if ! kill -0 "$server_pid" >/dev/null 2>&1; then
    echo "ERROR: Server process exited early. Log follows:" >&2
    echo "----- server.log (all) -----" >&2
    cat "$workdir/server.log" >&2 || true
    exit 5
  fi

  sleep 0.4
done

echo "SUCCESS: Reached API at http://127.0.0.1:${found_port}${path}"
echo "Response:"
echo "$response_body" | head -n 50

# Basic assertion: ensure JSON indicates success-like payload
if ! ( grep -q '"records"' <<<"$response_body" || grep -q '"isSuccessful":true' <<<"$response_body" ); then
  echo "ERROR: API response did not contain expected fields." >&2
  exit 6
fi

# All good — the trap will clean up the process/tree.
exit 0
