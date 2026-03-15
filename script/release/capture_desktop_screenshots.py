#!/usr/bin/env python3
"""Capture deterministic Linux desktop UI screenshots for a release tarball.

Pipeline:
1. Start Xvfb virtual display.
2. Launch the native desktop app.
3. Wait until app is visible through AT-SPI (dogtail).
4. Navigate selected UI routes via sidebar links.
5. Capture screenshots via ImageMagick `import`.
"""

from __future__ import annotations

import argparse
import html
import os
import re
import shutil
import signal
import subprocess
import tarfile
import tempfile
import time
from pathlib import Path


ROUTES = [
    ("01-home", ("Home", "NorTools"), "home"),
    ("02-dns-lookup", "DNS Lookup", "dns"),
    ("03-http-check", "HTTP Check", "http"),
    ("04-https-ssl", "HTTPS / SSL", "https"),
    ("05-subnet-calculator", "Subnet Calculator", "subnet"),
    ("06-password-generator", "Password Generator", "password"),
    ("07-about", "About", "about"),
    ("08-traceroute", "Traceroute", "traceroute"),
    ("09-interfaces-routing", ("Interfaces & Routing", "Interfaces and Routing"), "interfaces"),
    ("10-whois-lookup", "WHOIS Lookup", "whois"),
    ("11-reverse-dns", "Reverse DNS", "reverse_dns"),
    ("12-dns-health", "DNS Health", "dns_health"),
    ("13-domain-health", "Domain Health", "domain_health"),
]

RESULT_SIGNALS: dict[str, tuple[list[str], bool, float]] = {
    # DNS lookups can legitimately return either records, an empty-state message,
    # or an API error depending on CI resolver/network conditions.
    # Treat any of these as "result rendered" and allow extra time.
    "dns": (["records (", "no dns records returned", "api error"], False, 30.0),
    # Avoid matching the static description text "response time" before results
    # are rendered. Wait for result-only content.
    "http": (["headers (", "no response headers available."], False, 30.0),
    "https": (["chain diagram", "certificate details"], True, 30.0),
    "subnet": (["total hosts", "network address"], True, 30.0),
    "traceroute": (["hop diagram", "hops to"], True, 30.0),
    "interfaces": (["routes (", "interfaces ("], True, 30.0),
    "whois": (["whois server", "overview"], True, 30.0),
    "reverse_dns": (["ptr records", "status"], True, 30.0),
    "dns_health": (["nameservers", "soa"], True, 30.0),
    "domain_health": (["pass", "total"], True, 30.0),
}
MIN_ROUTE_SIGNAL_TIMEOUT_SECONDS = 30.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tarball", required=True, help="Path to nortools Linux tar.gz release artifact.")
    parser.add_argument("--output-dir", default="docs/screenshots", help="Output directory for PNG files.")
    parser.add_argument("--display", default=":99", help="X display to use (default: :99).")
    parser.add_argument(
        "--screen",
        default="1920x1080x24",
        help="Xvfb screen geometry (default: 1920x1080x24).",
    )
    parser.add_argument("--startup-timeout", type=float, default=180.0, help="Seconds to wait for app startup.")
    parser.add_argument("--click-delay", type=float, default=1.5, help="Seconds to wait after each navigation click.")
    parser.add_argument(
        "--no-xvfb",
        action="store_true",
        help="Use an existing DISPLAY instead of starting Xvfb.",
    )
    return parser.parse_args()


def _env_flag(name: str) -> bool:
    value = str(os.environ.get(name, "")).strip().lower()
    return value in {"1", "true", "yes", "on"}


def kill_process_tree(proc: subprocess.Popen[bytes]) -> None:
    if proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGTERM)
        proc.wait(timeout=10)
    except Exception:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except Exception:
            pass


def wait_for(predicate, timeout: float, interval: float = 0.5) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return False


def extract_tarball(tarball: Path, workdir: Path) -> Path:
    extract_dir = workdir / "app"
    extract_dir.mkdir(parents=True, exist_ok=True)
    with tarfile.open(tarball, "r:gz") as tf:
        tf.extractall(path=extract_dir)
    binary = extract_dir / "nortools"
    if not binary.exists():
        raise FileNotFoundError(f"Expected executable not found in tarball: {binary}")
    binary.chmod(binary.stat().st_mode | 0o111)
    return binary


def run_cmd(cmd: list[str], display: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["DISPLAY"] = display
    return subprocess.run(cmd, env=env, text=True, capture_output=True, check=check)


def _pick_best_window(display: str, candidates: list[str]) -> str:
    infos: list[tuple[int, str]] = []
    large_infos: list[tuple[int, str]] = []
    for candidate in candidates:
        try:
            geo = get_window_geometry(display, candidate)
            width = int(geo.get("WIDTH", 0))
            height = int(geo.get("HEIGHT", 0))
            area = width * height
            infos.append((area, candidate))
            if width >= 600 and height >= 400:
                large_infos.append((area, candidate))
        except Exception:
            continue
    if large_infos:
        return max(large_infos, key=lambda item: item[0])[1]
    if infos:
        return max(infos, key=lambda item: item[0])[1]
    return candidates[0]


def list_visible_window_ids(display: str) -> list[str]:
    result = run_cmd(["xdotool", "search", "--onlyvisible", "--name", ".*"], display=display, check=False)
    if result.returncode != 0:
        return []
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def find_window_id(display: str, app_pid: int | None = None, ignore_ids: set[str] | None = None) -> str:
    search_cmds: list[list[str]] = []
    if app_pid is not None:
        search_cmds.append(["xdotool", "search", "--onlyvisible", "--pid", str(app_pid)])
        search_cmds.append(["xdotool", "search", "--pid", str(app_pid)])
    search_cmds.extend(
        [
            ["xdotool", "search", "--onlyvisible", "--name", "[Nn]or[Tt]ools"],
            ["xdotool", "search", "--onlyvisible", "--class", "[Nn]or[Tt]ools"],
            ["xdotool", "search", "--name", "[Nn]or[Tt]ools"],
            ["xdotool", "search", "--name", "nortools"],
            ["xdotool", "search", "--onlyvisible", "--class", ".*"],
            ["xdotool", "search", "--onlyvisible", "--name", ".*"],
        ]
    )

    seen = set()
    candidates: list[str] = []
    for cmd in search_cmds:
        result = run_cmd(cmd, display=display, check=False)
        if result.returncode != 0:
            continue
        for line in result.stdout.splitlines():
            window_id = line.strip()
            if not window_id or window_id in seen:
                continue
            seen.add(window_id)
            candidates.append(window_id)

    if ignore_ids:
        candidates = [window_id for window_id in candidates if window_id not in ignore_ids]

    if not candidates:
        raise RuntimeError(f"Could not find NorTools window on display {display}.")
    if len(candidates) == 1:
        return candidates[0]
    return _pick_best_window(display, candidates)


def capture_screen(output_path: Path, display: str, window_id: str) -> None:
    geo = get_window_geometry(display, window_id)
    w = max(1, int(geo.get("WIDTH", 1200)))
    h = max(1, int(geo.get("HEIGHT", 800)))
    x = int(geo.get("X", 0))
    y = int(geo.get("Y", 0))
    if w < 300 or h < 200:
        raise RuntimeError(f"Selected NorTools window is too small for capture: {w}x{h} (id={window_id})")
    crop = f"{w}x{h}+{x}+{y}"
    subprocess.run(
        ["import", "-display", display, "-window", "root", "-crop", crop, "+repage", str(output_path)],
        check=True,
    )


def analyze_screenshot(output_path: Path) -> tuple[int, float]:
    result = subprocess.run(
        ["identify", "-colorspace", "gray", "-format", "%k %[fx:mean]", str(output_path)],
        text=True,
        capture_output=True,
        check=True,
    )
    raw = result.stdout.strip()
    parts = raw.split()
    if len(parts) < 2:
        raise RuntimeError(f"Unexpected screenshot analysis output for {output_path}: {raw!r}")
    return int(parts[0]), float(parts[1])


def capture_screen_with_retry(
    output_path: Path,
    display: str,
    window_id: str,
    *,
    max_attempts: int = 8,
    retry_delay: float = 1.0,
    min_luma: float = 0.05,
    min_colors: int = 12,
) -> None:
    if max_attempts < 1:
        raise ValueError("max_attempts must be >= 1")

    temp_output = output_path.with_suffix(".tmp.png")
    last_stats: tuple[int, float] | None = None
    try:
        for attempt in range(1, max_attempts + 1):
            capture_screen(temp_output, display, window_id)
            colors, mean_luma = analyze_screenshot(temp_output)
            last_stats = (colors, mean_luma)
            near_black = mean_luma < min_luma and colors <= min_colors
            if not near_black:
                temp_output.replace(output_path)
                return
            if attempt < max_attempts:
                time.sleep(retry_delay)
        temp_output.replace(output_path)
        if last_stats is not None:
            colors, mean_luma = last_stats
            raise RuntimeError(
                "Captured screenshot remained near-black after retries: "
                f"{output_path.name} colors={colors} mean_luma={mean_luma:.4f}"
            )
        raise RuntimeError(f"Captured screenshot remained near-black after retries: {output_path.name}")
    finally:
        if temp_output.exists():
            temp_output.unlink(missing_ok=True)


def get_active_window_id(display: str) -> str | None:
    result = run_cmd(["xdotool", "getactivewindow"], display=display, check=False)
    if result.returncode != 0:
        return None
    value = result.stdout.strip()
    return value or None


def get_window_geometry(display: str, window_id: str) -> dict[str, int]:
    result = run_cmd(["xdotool", "getwindowgeometry", "--shell", window_id], display=display)
    values: dict[str, int] = {}
    for line in result.stdout.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        if re.fullmatch(r"-?\d+", value.strip()):
            values[key.strip()] = int(value.strip())
    return values


def window_is_usable(display: str, window_id: str, min_width: int = 600, min_height: int = 400) -> bool:
    try:
        geo = get_window_geometry(display, window_id)
    except Exception:
        return False
    return int(geo.get("WIDTH", 0)) >= min_width and int(geo.get("HEIGHT", 0)) >= min_height


def xdotool_click(display: str, window_id: str, rel_x: int, rel_y: int) -> None:
    geo = get_window_geometry(display, window_id)
    x = geo.get("X", 0) + max(1, rel_x)
    y = geo.get("Y", 0) + max(1, rel_y)
    run_cmd(["xdotool", "mousemove", "--sync", str(x), str(y), "click", "1"], display=display)


def xdotool_type(display: str, text: str) -> None:
    run_cmd(["xdotool", "key", "ctrl+a", "BackSpace"], display=display)
    run_cmd(["xdotool", "type", "--delay", "1", text], display=display)


def xdotool_key(display: str, *keys: str) -> None:
    run_cmd(["xdotool", "key", *keys], display=display)


def xdotool_focus_window(display: str, window_id: str) -> None:
    run_cmd(["xdotool", "windowactivate", "--sync", window_id], display=display, check=False)


def _normalize_text(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip().casefold()


def _compact_text(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


class DogtailNavigator:
    def _walk(self, node):
        yield node
        children = list(getattr(node, "children", []) or [])
        for child in children:
            yield from self._walk(child)

    def has_text_fragments(self, app, fragments: list[str], require_all: bool = True) -> bool:
        wanted = [_normalize_text(fragment) for fragment in fragments if fragment]
        if not wanted:
            return False
        matched = [False] * len(wanted)
        for node in self._walk(app):
            texts = [
                _normalize_text(str(getattr(node, "name", "") or "")),
                _normalize_text(str(getattr(node, "description", "") or "")),
                _normalize_text(str(getattr(node, "text", "") or "")),
            ]
            for text in texts:
                if not text:
                    continue
                for idx, needle in enumerate(wanted):
                    if not matched[idx] and needle in text:
                        matched[idx] = True
            if require_all and all(matched):
                return True
            if not require_all and any(matched):
                return True
        return all(matched) if require_all else any(matched)

    def fragment_debug_snapshot(self, app, fragments: list[str]) -> dict[str, object]:
        wanted = [_normalize_text(fragment) for fragment in fragments if fragment]
        matched = [False] * len(wanted)
        matched_text: dict[str, str] = {}
        samples: list[str] = []
        scanned_nodes = 0
        for node in self._walk(app):
            scanned_nodes += 1
            texts = [
                _normalize_text(str(getattr(node, "name", "") or "")),
                _normalize_text(str(getattr(node, "description", "") or "")),
                _normalize_text(str(getattr(node, "text", "") or "")),
            ]
            for text in texts:
                if not text:
                    continue
                if len(samples) < 8 and text not in samples:
                    samples.append(text[:180])
                for idx, needle in enumerate(wanted):
                    if not matched[idx] and needle in text:
                        matched[idx] = True
                        matched_text[needle] = text[:220]
        return {
            "wanted": wanted,
            "matched": matched,
            "matched_text": matched_text,
            "samples": samples,
            "scanned_nodes": scanned_nodes,
        }

    def find_app_root(self):
        from dogtail import tree

        preferred = ("NorTools", "nortools")
        for name in preferred:
            try:
                return tree.root.application(name)
            except Exception:
                pass
        for app in tree.root.applications():
            app_name = str(getattr(app, "name", "") or "").lower()
            if "nortools" in app_name:
                return app
        raise RuntimeError("NorTools app not found in AT-SPI application list.")

    def click_link(self, app, link_name: str) -> bool:
        from dogtail.predicate import GenericPredicate

        predicate = GenericPredicate(name=link_name, roleName="link")
        link = app.findChild(predicate, recursive=True, retry=False, requireResult=False)
        if not link:
            raise RuntimeError(f"Could not find link in accessibility tree: {link_name}")
        link.click()
        return True


class AtspiNavigator:
    def __init__(self):
        import gi

        gi.require_version("Atspi", "2.0")
        from gi.repository import Atspi

        self.Atspi = Atspi

    def _walk(self, node):
        yield node
        count = int(node.get_child_count() or 0)
        for idx in range(count):
            child = node.get_child_at_index(idx)
            if child is None:
                continue
            yield from self._walk(child)

    @staticmethod
    def _normalize_name(name: str) -> str:
        return _normalize_text(name)

    def has_text_fragments(self, app, fragments: list[str], require_all: bool = True) -> bool:
        wanted = [self._normalize_name(fragment) for fragment in fragments if fragment]
        if not wanted:
            return False
        matched = [False] * len(wanted)
        for node in self._walk(app):
            texts: list[str] = []
            try:
                texts.append(self._normalize_name(str(node.get_name() or "")))
            except Exception:
                pass
            try:
                texts.append(self._normalize_name(str(node.get_description() or "")))
            except Exception:
                pass
            # Some GTK/AT-SPI widgets expose visible content via Atspi.Text and
            # not via name/description. Probe this when available.
            try:
                char_count = int(self.Atspi.Text.get_character_count(node) or 0)
                if char_count > 0:
                    snippet = self.Atspi.Text.get_text(node, 0, min(char_count, 4096))
                    texts.append(self._normalize_name(str(snippet or "")))
            except Exception:
                pass
            for text in texts:
                if not text:
                    continue
                for idx, needle in enumerate(wanted):
                    if not matched[idx] and needle in text:
                        matched[idx] = True
            if require_all and all(matched):
                return True
            if not require_all and any(matched):
                return True
        return all(matched) if require_all else any(matched)

    def fragment_debug_snapshot(self, app, fragments: list[str]) -> dict[str, object]:
        wanted = [self._normalize_name(fragment) for fragment in fragments if fragment]
        matched = [False] * len(wanted)
        matched_text: dict[str, str] = {}
        samples: list[str] = []
        scanned_nodes = 0
        for node in self._walk(app):
            scanned_nodes += 1
            texts: list[str] = []
            try:
                texts.append(self._normalize_name(str(node.get_name() or "")))
            except Exception:
                pass
            try:
                texts.append(self._normalize_name(str(node.get_description() or "")))
            except Exception:
                pass
            try:
                char_count = int(self.Atspi.Text.get_character_count(node) or 0)
                if char_count > 0:
                    snippet = self.Atspi.Text.get_text(node, 0, min(char_count, 4096))
                    texts.append(self._normalize_name(str(snippet or "")))
            except Exception:
                pass
            for text in texts:
                if not text:
                    continue
                if len(samples) < 8 and text not in samples:
                    samples.append(text[:180])
                for idx, needle in enumerate(wanted):
                    if not matched[idx] and needle in text:
                        matched[idx] = True
                        matched_text[needle] = text[:220]
        return {
            "wanted": wanted,
            "matched": matched,
            "matched_text": matched_text,
            "samples": samples,
            "scanned_nodes": scanned_nodes,
        }

    def _iter_nortools_apps(self):
        return [app for app in self._iter_applications() if "nortools" in str(app.get_name() or "").lower()]

    def _iter_applications(self):
        desktop = self.Atspi.get_desktop(0)
        candidates = []
        for child in self._walk(desktop):
            try:
                if child.get_role() == self.Atspi.Role.APPLICATION:
                    candidates.append(child)
            except Exception:
                continue
        return candidates

    def _route_score(self, app) -> int:
        wanted_names: list[str] = []
        for _, route_name, _ in ROUTES:
            if isinstance(route_name, tuple):
                wanted_names.extend(self._normalize_name(name) for name in route_name if name)
            elif route_name:
                wanted_names.append(self._normalize_name(route_name))
        score = 0
        for node in self._walk(app):
            try:
                name = self._normalize_name(str(node.get_name() or ""))
            except Exception:
                continue
            if not name:
                continue
            for wanted in wanted_names:
                if wanted and wanted in name:
                    score += 1
                    break
        return score

    def _iter_candidate_apps(self):
        nortools_apps = self._iter_nortools_apps()
        if nortools_apps:
            return nortools_apps
        apps = []
        for app in self._iter_applications():
            try:
                score = self._route_score(app)
            except Exception:
                continue
            if score > 0:
                apps.append((score, app))
        if not apps:
            return []
        apps.sort(key=lambda item: item[0], reverse=True)
        return [app for _, app in apps]

    def find_app_root(self):
        apps = self._iter_candidate_apps()
        if not apps:
            raise RuntimeError("NorTools app not found in AT-SPI desktop tree.")

        # Some runtimes expose more than one "nortools" application node.
        # Prefer the one that actually exposes sidebar links.
        def app_score(app) -> tuple[int, int]:
            link_count = 0
            node_count = 0
            for node in self._walk(app):
                node_count += 1
                try:
                    if node.get_role() == self.Atspi.Role.LINK:
                        link_count += 1
                except Exception:
                    continue
            return (link_count, node_count)

        return max(apps, key=app_score)

    def _match_rank(self, candidate_name: str, target_name: str) -> int | None:
        normalized = self._normalize_name(candidate_name)
        target = self._normalize_name(target_name)
        if not normalized:
            return None
        if normalized == target:
            return 0
        if normalized.startswith(f"{target} "):
            return 1
        if f" {target} " in f" {normalized} ":
            return 2
        if target in normalized:
            return 3
        return None

    def _left_edge(self, node) -> int:
        try:
            ext = self.Atspi.Component.get_extents(node, self.Atspi.CoordType.SCREEN)
            return int(getattr(ext, "x", 10_000))
        except Exception:
            return 10_000

    def _do_action(self, node) -> bool:
        try:
            actions = int(self.Atspi.Action.get_n_actions(node) or 0)
        except Exception:
            return False
        if actions <= 0:
            return False

        for i in range(actions):
            try:
                action_name = str(self.Atspi.Action.get_action_name(node, i) or "").lower()
            except Exception:
                continue
            if action_name in ("click", "activate", "press", "open", "jump"):
                try:
                    return bool(self.Atspi.Action.do_action(node, i))
                except Exception:
                    continue

        for i in range(actions):
            try:
                if self.Atspi.Action.do_action(node, i):
                    return True
            except Exception:
                continue
        return False

    def click_link(self, _app, link_name: str) -> bool:
        apps = []
        if _app is not None:
            apps.append(_app)
        for app in self._iter_candidate_apps():
            if app not in apps:
                apps.append(app)
        if not apps:
            raise RuntimeError("NorTools app not found in AT-SPI desktop tree.")

        role_priority = {
            self.Atspi.Role.LINK: 0,
            self.Atspi.Role.PUSH_BUTTON: 1,
            self.Atspi.Role.MENU_ITEM: 2,
            self.Atspi.Role.LIST_ITEM: 3,
        }
        candidates = []
        for app in apps:
            for node in self._walk(app):
                try:
                    role = node.get_role()
                    name = str(node.get_name() or "")
                except Exception:
                    continue
                match_rank = self._match_rank(name, link_name)
                if match_rank is None:
                    continue
                candidates.append((match_rank, role_priority.get(role, 9), self._left_edge(node), node))

        if not candidates:
            raise RuntimeError(f"Could not find link in accessibility tree: {link_name}")

        _, _, _, target = sorted(candidates, key=lambda item: (item[0], item[1], item[2]))[0]
        if not self._do_action(target):
            raise RuntimeError(f"Link found but has no invokable actions: {link_name}")
        return True


def create_navigator():
    try:
        return AtspiNavigator()
    except Exception:
        return DogtailNavigator()


def _refresh_app_ref(navigator, app_ref: dict[str, object]) -> object | None:
    app = app_ref.get("app")
    if app is not None:
        return app
    try:
        app = navigator.find_app_root()
        app_ref["app"] = app
        return app
    except Exception:
        return None


def _collect_fragment_debug_snapshot(navigator, app_ref: dict[str, object], fragments: list[str]) -> dict[str, object] | None:
    snapshot_fn = getattr(navigator, "fragment_debug_snapshot", None)
    if not callable(snapshot_fn):
        return None
    app = _refresh_app_ref(navigator, app_ref)
    if app is None:
        return None
    try:
        return snapshot_fn(app, fragments)
    except Exception:
        try:
            app = navigator.find_app_root()
            app_ref["app"] = app
        except Exception:
            return None
        try:
            return snapshot_fn(app, fragments)
        except Exception:
            return None


def _format_fragment_debug_info(debug_snapshot: object, sample_limit: int = 5) -> str:
    if not isinstance(debug_snapshot, dict):
        return ""
    wanted = list(debug_snapshot.get("wanted", []) or [])
    matched = list(debug_snapshot.get("matched", []) or [])
    matched_fragments = [wanted[idx] for idx, ok in enumerate(matched) if idx < len(wanted) and ok]
    missing_fragments = [wanted[idx] for idx, ok in enumerate(matched) if idx < len(wanted) and not ok]
    samples = list(debug_snapshot.get("samples", []) or [])[:sample_limit]
    scanned_nodes = int(debug_snapshot.get("scanned_nodes", 0) or 0)
    return (
        f"; matched={matched_fragments} missing={missing_fragments} "
        f"scanned_nodes={scanned_nodes} sample_texts={samples}"
    )


def _node_debug_fields(node, navigator) -> tuple[str, str, str, str]:
    role = ""
    name = ""
    description = ""
    text_value = ""
    try:
        role = _compact_text(str(node.get_role_name() or ""))
    except Exception:
        role = _compact_text(str(getattr(node, "roleName", "") or ""))
    try:
        name = _compact_text(str(node.get_name() or ""))
    except Exception:
        name = _compact_text(str(getattr(node, "name", "") or ""))
    try:
        description = _compact_text(str(node.get_description() or ""))
    except Exception:
        description = _compact_text(str(getattr(node, "description", "") or ""))
    try:
        text_value = _compact_text(str(getattr(node, "text", "") or ""))
    except Exception:
        text_value = ""
    atspi = getattr(navigator, "Atspi", None)
    if atspi is not None and not text_value:
        try:
            char_count = int(atspi.Text.get_character_count(node) or 0)
            if char_count > 0:
                text_value = _compact_text(str(atspi.Text.get_text(node, 0, min(char_count, 1024)) or ""))
        except Exception:
            pass
    return role, name, description, text_value


def _collect_accessibility_rows(navigator, app, max_nodes: int = 1200) -> tuple[list[tuple[str, str, str, str]], bool]:
    walk_fn = getattr(navigator, "_walk", None)
    if not callable(walk_fn):
        return [], False
    rows: list[tuple[str, str, str, str]] = []
    truncated = False
    for node in walk_fn(app):
        if len(rows) >= max_nodes:
            truncated = True
            break
        role, name, description, text_value = _node_debug_fields(node, navigator)
        if not any((role, name, description, text_value)):
            continue
        rows.append((role[:120], name[:200], description[:300], text_value[:300]))
    return rows, truncated


def _render_accessibility_dump_html(
    route_key: str,
    fragments: list[str],
    require_all: bool,
    timeout: float,
    debug_snapshot: dict[str, object] | None,
    rows: list[tuple[str, str, str, str]],
    truncated: bool,
) -> str:
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime())
    heading = html.escape(f"Route timeout accessibility dump: {route_key}")
    summary_lines = [
        f"generated_at={timestamp}",
        f"route={route_key}",
        f"timeout_seconds={timeout:.1f}",
        f"fragments={fragments}",
        f"require_all={require_all}",
    ]
    if isinstance(debug_snapshot, dict):
        summary_lines.append(_format_fragment_debug_info(debug_snapshot, sample_limit=8).lstrip("; "))
    summary_lines.append(f"rows={len(rows)} truncated={truncated}")
    summary_block = "\n".join(html.escape(line) for line in summary_lines if line)
    table_rows = []
    for role, name, description, text_value in rows:
        table_rows.append(
            "<tr>"
            f"<td>{html.escape(role)}</td>"
            f"<td>{html.escape(name)}</td>"
            f"<td>{html.escape(description)}</td>"
            f"<td>{html.escape(text_value)}</td>"
            "</tr>"
        )
    body_rows = "\n".join(table_rows)
    return (
        "<!doctype html>\n"
        "<html><head><meta charset='utf-8' />"
        f"<title>{heading}</title>"
        "<style>"
        "body{font-family:monospace;padding:16px;}"
        "table{border-collapse:collapse;width:100%;font-size:12px;}"
        "th,td{border:1px solid #ccc;vertical-align:top;padding:4px;text-align:left;}"
        "pre{background:#f5f5f5;padding:8px;white-space:pre-wrap;}"
        "</style></head><body>"
        f"<h1>{heading}</h1>"
        f"<pre>{summary_block}</pre>"
        "<table><thead><tr><th>role</th><th>name</th><th>description</th><th>text</th></tr></thead><tbody>"
        f"{body_rows}</tbody></table>"
        "</body></html>\n"
    )


def _capture_timeout_screenshot(output_path: Path, display: str, window_id: str | None) -> None:
    if window_id:
        try:
            capture_screen(output_path, display, window_id)
            return
        except Exception:
            pass
    subprocess.run(["import", "-display", display, "-window", "root", str(output_path)], check=True)


def _write_timeout_artifacts(
    route_key: str,
    *,
    navigator,
    app_ref: dict[str, object],
    fragments: list[str],
    require_all: bool,
    timeout: float,
    display: str | None,
    window_id: str | None,
    debug_output_dir: Path | None,
) -> list[Path]:
    if debug_output_dir is None:
        return []
    debug_output_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    artifacts: list[Path] = []
    if display:
        screenshot_path = debug_output_dir / f"debug-{route_key}-timeout-{stamp}.png"
        try:
            _capture_timeout_screenshot(screenshot_path, display, window_id)
            print(f"[capture] debug: wrote timeout screenshot: {screenshot_path}", flush=True)
            artifacts.append(screenshot_path)
        except Exception as exc:
            print(f"[capture] warning: failed to capture timeout screenshot for '{route_key}': {exc}", flush=True)
    debug_snapshot = _collect_fragment_debug_snapshot(navigator, app_ref, fragments)
    rows: list[tuple[str, str, str, str]] = []
    truncated = False
    app = _refresh_app_ref(navigator, app_ref)
    if app is not None:
        try:
            rows, truncated = _collect_accessibility_rows(navigator, app, max_nodes=1400)
        except Exception as exc:
            print(f"[capture] warning: failed to collect AT-SPI rows for '{route_key}': {exc}", flush=True)
    try:
        dump_html = _render_accessibility_dump_html(
            route_key=route_key,
            fragments=fragments,
            require_all=require_all,
            timeout=timeout,
            debug_snapshot=debug_snapshot,
            rows=rows,
            truncated=truncated,
        )
        html_path = debug_output_dir / f"debug-{route_key}-atspi-{stamp}.html"
        html_path.write_text(dump_html, encoding="utf-8")
        print(f"[capture] debug: wrote AT-SPI dump: {html_path}", flush=True)
        artifacts.append(html_path)
    except Exception as exc:
        print(f"[capture] warning: failed to write AT-SPI dump for '{route_key}': {exc}", flush=True)
    return artifacts


def wait_for_route_result_signal(
    route_key: str,
    navigator,
    app_ref: dict[str, object],
    *,
    display: str | None = None,
    window_id: str | None = None,
    debug_output_dir: Path | None = None,
) -> None:
    if _env_flag("CAPTURE_SCREENSHOTS_SKIP_ROUTE_SIGNALS"):
        return

    signal = RESULT_SIGNALS.get(route_key)
    if signal is None:
        return
    fragments, require_all, timeout = signal
    timeout = max(float(timeout), MIN_ROUTE_SIGNAL_TIMEOUT_SECONDS)
    has_text_fragments = getattr(navigator, "has_text_fragments", None)
    if not callable(has_text_fragments):
        return

    last_refresh = [0.0]
    last_probe_error = [""]

    def predicate() -> bool:
        now = time.monotonic()
        app = app_ref.get("app")
        if app is None or (now - last_refresh[0]) >= 1.5:
            try:
                app = navigator.find_app_root()
                app_ref["app"] = app
                last_refresh[0] = now
            except Exception:
                last_probe_error[0] = "find_app_root failed"
                return False
        try:
            last_probe_error[0] = ""
            return bool(has_text_fragments(app, fragments, require_all))
        except Exception as exc:
            last_probe_error[0] = f"{type(exc).__name__}: {exc}"
            try:
                app_ref["app"] = navigator.find_app_root()
            except Exception:
                pass
            return False

    interval = 0.4
    started = time.monotonic()
    next_progress_log = started + 2.5
    completed = False
    while time.monotonic() - started < timeout:
        if predicate():
            completed = True
            break
        now = time.monotonic()
        if now >= next_progress_log:
            progress_snapshot = _collect_fragment_debug_snapshot(navigator, app_ref, fragments)
            progress_info = _format_fragment_debug_info(progress_snapshot, sample_limit=3)
            probe_error_info = f"; probe_error={last_probe_error[0]}" if last_probe_error[0] else ""
            print(
                f"[capture] waiting for route signal '{route_key}' "
                f"{(now - started):.1f}/{timeout:.1f}s{progress_info}{probe_error_info}",
                flush=True,
            )
            next_progress_log = now + 2.5
        time.sleep(interval)

    if not completed:
        artifacts = _write_timeout_artifacts(
            route_key,
            navigator=navigator,
            app_ref=app_ref,
            fragments=fragments,
            require_all=require_all,
            timeout=timeout,
            display=display,
            window_id=window_id,
            debug_output_dir=debug_output_dir,
        )
        artifacts_info = f"; artifacts={[str(path) for path in artifacts]}" if artifacts else ""
        ci_mode = _env_flag("CI")
        allow_dns_timeout = _env_flag("CAPTURE_SCREENSHOTS_ALLOW_DNS_TIMEOUT") or ci_mode
        if route_key == "dns" and allow_dns_timeout:
            print(
                f"[capture] warning: DNS AT-SPI result signal timed out after {timeout:.1f}s; continuing in CI mode"
                f"{artifacts_info}.",
                flush=True,
            )
            return
        debug_snapshot = _collect_fragment_debug_snapshot(navigator, app_ref, fragments)
        debug_info = _format_fragment_debug_info(debug_snapshot, sample_limit=6)
        probe_error_info = f"; probe_error={last_probe_error[0]}" if last_probe_error[0] else ""
        raise RuntimeError(
            f"Timed out waiting for AT-SPI result signal on route '{route_key}' "
            f"after {timeout:.1f}s (fragments={fragments}, require_all={require_all})"
            f"{debug_info}{probe_error_info}{artifacts_info}"
        )


def perform_route_action(route_key: str, display: str, window_id: str) -> None:
    # Coordinates are relative to the app window; tuned for default 1200x800.
    if route_key == "dns":
        # DNS page layout has moved over time; trigger both enter-submit and
        # explicit button click to avoid focus-sensitive flakes in CI.
        xdotool_focus_window(display, window_id)
        xdotool_key(display, "Escape")
        time.sleep(0.1)
        xdotool_click(display, window_id, 315, 112)
        time.sleep(0.1)
        xdotool_click(display, window_id, 315, 112)
        xdotool_type(display, "example.com")
        xdotool_key(display, "Return")
        time.sleep(0.2)
        xdotool_click(display, window_id, 1060, 112)
        time.sleep(2.5)
    elif route_key == "http":
        xdotool_focus_window(display, window_id)
        # Close any open popovers from the previous route before interacting.
        xdotool_key(display, "Escape")
        time.sleep(0.1)
        # Input can miss focus in CI; click twice and submit by Enter, then
        # click the button as fallback.
        xdotool_click(display, window_id, 315, 120)
        time.sleep(0.1)
        xdotool_click(display, window_id, 315, 120)
        xdotool_type(display, "http://example.com")
        xdotool_key(display, "Return")
        time.sleep(0.2)
        xdotool_click(display, window_id, 1050, 124)
        time.sleep(2.5)
    elif route_key == "https":
        xdotool_focus_window(display, window_id)
        xdotool_key(display, "Escape")
        time.sleep(0.1)
        xdotool_click(display, window_id, 315, 92)
        time.sleep(0.1)
        xdotool_click(display, window_id, 315, 92)
        xdotool_type(display, "google.com")
        xdotool_key(display, "Return")
        time.sleep(0.2)
        xdotool_click(display, window_id, 1060, 92)
        time.sleep(4.0)
    elif route_key == "subnet":
        xdotool_click(display, window_id, 315, 120)
        xdotool_type(display, "192.168.1.0/24")
        xdotool_click(display, window_id, 1050, 134)
        time.sleep(2.5)
    elif route_key == "password":
        xdotool_click(display, window_id, 286, 128)  # Length input
        xdotool_type(display, "20")
        xdotool_key(display, "Tab")
        xdotool_type(display, "3")
        xdotool_key(display, "Return")
        time.sleep(2.0)
    elif route_key == "about":
        # Let async /api/about render cards before capture.
        time.sleep(2.0)
    elif route_key == "traceroute":
        xdotool_click(display, window_id, 315, 132)
        xdotool_type(display, "8.8.8.8")
        xdotool_key(display, "Return")
        time.sleep(8.0)
    elif route_key == "interfaces":
        # Interfaces page auto-loads on mount.
        time.sleep(3.0)
    elif route_key == "whois":
        xdotool_click(display, window_id, 315, 120)
        xdotool_type(display, "192.168.10.0")
        xdotool_key(display, "Return")
        time.sleep(4.0)
    elif route_key == "reverse_dns":
        xdotool_click(display, window_id, 315, 120)
        xdotool_type(display, "1.1.1.1")
        xdotool_key(display, "Return")
        time.sleep(3.5)
    elif route_key == "dns_health":
        xdotool_click(display, window_id, 315, 120)
        xdotool_type(display, "example.com")
        xdotool_key(display, "Return")
        time.sleep(8.0)
    elif route_key == "domain_health":
        xdotool_click(display, window_id, 315, 120)
        xdotool_type(display, "example.com")
        xdotool_key(display, "Return")
        time.sleep(8.0)


def run() -> int:
    args = parse_args()
    tarball = Path(args.tarball).resolve()
    if not tarball.exists():
        raise FileNotFoundError(f"Tarball not found: {tarball}")

    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    if not args.no_xvfb and shutil.which("Xvfb") is None:
        raise RuntimeError("Xvfb is required but not found in PATH.")
    if shutil.which("import") is None:
        raise RuntimeError("ImageMagick `import` is required but not found in PATH.")
    if shutil.which("traceroute") is None:
        raise RuntimeError("`traceroute` is required but not found in PATH.")

    with tempfile.TemporaryDirectory(prefix="nortools-screenshots-") as tmp:
        workdir = Path(tmp)
        binary = extract_tarball(tarball, workdir)
        navigator = create_navigator()

        env = os.environ.copy()
        env["DISPLAY"] = args.display
        env.setdefault("LANG", "C.UTF-8")
        env["NORTOOLS_DISABLE_UPDATER"] = "1"
        os.environ["DISPLAY"] = args.display

        xvfb: subprocess.Popen[bytes] | None = None
        if not args.no_xvfb:
            xvfb = subprocess.Popen(
                ["Xvfb", args.display, "-screen", "0", args.screen, "-ac"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                env=env,
                preexec_fn=os.setsid,
            )
            time.sleep(1.0)
        app_proc: subprocess.Popen[bytes] | None = None
        wait_for(lambda: len(list_visible_window_ids(args.display)) > 0, timeout=5.0, interval=0.25)
        baseline_window_ids = set(list_visible_window_ids(args.display))

        try:
            app_proc = subprocess.Popen(
                [str(binary), "--ui"],
                cwd=binary.parent,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                env=env,
                preexec_fn=os.setsid,
            )

            app_ref = {"app": None}
            window_ref = {"window_id": None}

            def app_ready() -> bool:
                if app_proc and app_proc.poll() is not None:
                    return True
                try:
                    window_ref["window_id"] = find_window_id(
                        args.display,
                        app_proc.pid if app_proc else None,
                        ignore_ids=baseline_window_ids,
                    )
                except Exception:
                    pass
                try:
                    app_ref["app"] = navigator.find_app_root()
                    return True
                except Exception:
                    return window_ref["window_id"] is not None

            if not wait_for(app_ready, timeout=args.startup_timeout):
                visible_windows = list_visible_window_ids(args.display)
                raise RuntimeError(
                    "Timed out waiting for NorTools accessibility tree. "
                    f"display={args.display} pid={app_proc.pid if app_proc else 'n/a'} "
                    f"visible_window_count={len(visible_windows)}"
                )
            if app_proc.poll() is not None:
                output = app_proc.stdout.read().decode("utf-8", errors="replace") if app_proc.stdout else ""
                raise RuntimeError(f"NorTools exited before screenshots could be captured.\n{output}")

            def resolve_main_window_id(timeout: float = 45.0) -> str:
                start = time.monotonic()
                last_error: Exception | None = None
                while time.monotonic() - start < timeout:
                    candidates: list[str] = []
                    if window_ref["window_id"]:
                        candidates.append(window_ref["window_id"])
                    active = get_active_window_id(args.display)
                    if active:
                        candidates.append(active)
                    try:
                        candidates.append(
                            find_window_id(
                                args.display,
                                app_proc.pid if app_proc else None,
                                ignore_ids=baseline_window_ids,
                            )
                        )
                    except Exception as exc:
                        last_error = exc

                    for candidate in candidates:
                        if candidate and window_is_usable(args.display, candidate):
                            window_ref["window_id"] = candidate
                            return candidate

                    time.sleep(0.5)

                if last_error is not None:
                    raise RuntimeError(f"Timed out waiting for usable NorTools window: {last_error}") from last_error
                raise RuntimeError("Timed out waiting for usable NorTools window.")

            window_id = resolve_main_window_id()

            def click_with_retry(link_name: str, timeout: float = 30.0) -> None:
                start = time.monotonic()
                last_error: Exception | None = None
                while time.monotonic() - start < timeout:
                    try:
                        if app_ref["app"] is None:
                            try:
                                app_ref["app"] = navigator.find_app_root()
                            except Exception:
                                pass
                        navigator.click_link(app_ref["app"], link_name)
                        return
                    except Exception as exc:
                        last_error = exc
                        time.sleep(0.5)
                if last_error is not None:
                    raise RuntimeError(f"Failed to click sidebar link '{link_name}' within {timeout}s: {last_error}") from last_error
                raise RuntimeError(f"Failed to click sidebar link '{link_name}' within {timeout}s")

            for filename, link_name, route_key in ROUTES:
                if isinstance(link_name, tuple):
                    clicked = False
                    last_error: Exception | None = None
                    for candidate in link_name:
                        try:
                            click_with_retry(candidate, timeout=8.0 if route_key == "home" else 30.0)
                            clicked = True
                            break
                        except Exception as exc:
                            last_error = exc
                    if not clicked and route_key != "home":
                        if last_error is not None:
                            raise RuntimeError(
                                f"Failed to click any sidebar link for route '{route_key}': {link_name}"
                            ) from last_error
                        raise RuntimeError(f"Failed to click any sidebar link for route '{route_key}': {link_name}")
                    time.sleep(args.click_delay)
                elif link_name:
                    click_with_retry(link_name)
                    time.sleep(args.click_delay)
                if not window_is_usable(args.display, window_id, min_width=300, min_height=200):
                    window_id = resolve_main_window_id(timeout=15.0)
                perform_route_action(route_key, args.display, window_id)
                wait_for_route_result_signal(
                    route_key,
                    navigator,
                    app_ref,
                    display=args.display,
                    window_id=window_id,
                    debug_output_dir=output_dir,
                )
                capture_screen_with_retry(output_dir / f"{filename}.png", args.display, window_id)

        finally:
            if app_proc is not None:
                kill_process_tree(app_proc)
            if xvfb is not None:
                kill_process_tree(xvfb)

    return 0


if __name__ == "__main__":
    raise SystemExit(run())
