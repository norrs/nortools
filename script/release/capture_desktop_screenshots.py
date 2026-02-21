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
]


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


class DogtailNavigator:
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
        return re.sub(r"\s+", " ", name).strip().casefold()

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


def perform_route_action(route_key: str, display: str, window_id: str) -> None:
    # Coordinates are relative to the app window; tuned for default 1200x800.
    if route_key == "dns":
        xdotool_click(display, window_id, 315, 132)
        xdotool_type(display, "example.com")
        xdotool_key(display, "Return")
        time.sleep(2.5)
    elif route_key == "http":
        xdotool_click(display, window_id, 315, 120)
        xdotool_type(display, "http://example.com")
        xdotool_key(display, "Return")
        time.sleep(2.5)
    elif route_key == "https":
        xdotool_click(display, window_id, 315, 110)
        xdotool_type(display, "google.com")
        xdotool_key(display, "Return")
        time.sleep(3.5)
    elif route_key == "subnet":
        xdotool_click(display, window_id, 315, 120)
        xdotool_type(display, "192.168.1.0/24")
        xdotool_key(display, "Return")
        time.sleep(2.0)
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

    with tempfile.TemporaryDirectory(prefix="nortools-screenshots-") as tmp:
        workdir = Path(tmp)
        binary = extract_tarball(tarball, workdir)
        navigator = create_navigator()

        env = os.environ.copy()
        env["DISPLAY"] = args.display
        env.setdefault("LANG", "C.UTF-8")
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
                capture_screen_with_retry(output_dir / f"{filename}.png", args.display, window_id)

        finally:
            if app_proc is not None:
                kill_process_tree(app_proc)
            if xvfb is not None:
                kill_process_tree(xvfb)

    return 0


if __name__ == "__main__":
    raise SystemExit(run())
