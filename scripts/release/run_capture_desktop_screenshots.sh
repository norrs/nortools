#!/usr/bin/env bash
set -euo pipefail

die() {
  echo "ERROR: $*" >&2
  exit 1
}

need_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || die "Missing required command: ${cmd}"
}

check_python_atspi() {
  python3 - <<'PY' >/dev/null 2>&1
import gi
gi.require_version("Atspi", "2.0")
from gi.repository import Atspi  # noqa: F401
PY
}

check_python_navigator() {
  python3 - <<'PY' >/dev/null 2>&1
try:
    import dogtail  # noqa: F401
except Exception:
    import gi
    gi.require_version("Atspi", "2.0")
    from gi.repository import Atspi  # noqa: F401
PY
}

locate_capture_script() {
  if [[ -n "${RUNFILES_DIR:-}" && -f "${RUNFILES_DIR}/_main/scripts/release/capture_desktop_screenshots.py" ]]; then
    echo "${RUNFILES_DIR}/_main/scripts/release/capture_desktop_screenshots.py"
    return 0
  fi
  if [[ -n "${BUILD_WORKSPACE_DIRECTORY:-}" && -f "${BUILD_WORKSPACE_DIRECTORY}/scripts/release/capture_desktop_screenshots.py" ]]; then
    echo "${BUILD_WORKSPACE_DIRECTORY}/scripts/release/capture_desktop_screenshots.py"
    return 0
  fi
  die "Could not locate capture_desktop_screenshots.py in runfiles or workspace."
}

need_cmd python3
need_cmd dbus-run-session
need_cmd Xvfb
need_cmd xdotool
need_cmd import
need_cmd identify

check_python_atspi || die "Python Atspi bindings missing (install PyGObject/AT-SPI bindings for python3)."
check_python_navigator || die "Neither dogtail nor Atspi Python bindings are usable."

capture_script="$(locate_capture_script)"

if [[ "${1:-}" == "--check-only" ]]; then
  echo "Preflight OK"
  exit 0
fi

args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tarball)
      [[ $# -ge 2 ]] || die "--tarball requires a value"
      tarball_value="$2"
      if [[ "$tarball_value" != /* && -n "${BUILD_WORKSPACE_DIRECTORY:-}" && -f "${BUILD_WORKSPACE_DIRECTORY}/${tarball_value}" ]]; then
        tarball_value="${BUILD_WORKSPACE_DIRECTORY}/${tarball_value}"
      fi
      args+=("$1" "$tarball_value")
      shift 2
      ;;
    --tarball=*)
      tarball_value="${1#--tarball=}"
      if [[ "$tarball_value" != /* && -n "${BUILD_WORKSPACE_DIRECTORY:-}" && -f "${BUILD_WORKSPACE_DIRECTORY}/${tarball_value}" ]]; then
        tarball_value="${BUILD_WORKSPACE_DIRECTORY}/${tarball_value}"
      fi
      args+=("--tarball=${tarball_value}")
      shift
      ;;
    *)
      args+=("$1")
      shift
      ;;
  esac
done

dbus-run-session -- python3 "$capture_script" "${args[@]}"
