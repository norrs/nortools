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
    ("01-home", None, "home"),
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
    parser.add_argument("--startup-timeout", type=float, default=90.0, help="Seconds to wait for app startup.")
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


def find_window_id(display: str) -> str:
    result = run_cmd(["xdotool", "search", "--name", "nortools"], display=display, check=False)
    if result.returncode != 0 or not result.stdout.strip():
        raise RuntimeError(f"Could not find nortools window on display {display}.")
    candidates = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if len(candidates) == 1:
        return candidates[0]

    best_id = candidates[0]
    best_area = -1
    for candidate in candidates:
        try:
            geo = get_window_geometry(display, candidate)
            area = int(geo.get("WIDTH", 0)) * int(geo.get("HEIGHT", 0))
            if area > best_area:
                best_area = area
                best_id = candidate
        except Exception:
            continue
    return best_id


def capture_screen(output_path: Path, display: str, window_id: str) -> None:
    geo = get_window_geometry(display, window_id)
    w = max(1, int(geo.get("WIDTH", 1200)))
    h = max(1, int(geo.get("HEIGHT", 800)))
    x = int(geo.get("X", 0))
    y = int(geo.get("Y", 0))
    crop = f"{w}x{h}+{x}+{y}"
    subprocess.run(
        ["import", "-display", display, "-window", "root", "-crop", crop, "+repage", str(output_path)],
        check=True,
    )


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

    def find_app_root(self):
        desktop = self.Atspi.get_desktop(0)
        for child in self._walk(desktop):
            try:
                role = child.get_role()
                name = str(child.get_name() or "")
            except Exception:
                continue
            if role == self.Atspi.Role.APPLICATION and "nortools" in name.lower():
                return child
        raise RuntimeError("NorTools app not found in AT-SPI desktop tree.")

    def click_link(self, _app, link_name: str) -> bool:
        app = self.find_app_root()
        candidates = []
        for node in self._walk(app):
            try:
                role = node.get_role()
                name = str(node.get_name() or "")
            except Exception:
                continue
            if role == self.Atspi.Role.LINK and name.strip() == link_name:
                candidates.append(node)
        if not candidates:
            raise RuntimeError(f"Could not find link in accessibility tree: {link_name}")

        def left_edge(node) -> int:
            try:
                ext = self.Atspi.Component.get_extents(node, self.Atspi.CoordType.SCREEN)
                return int(getattr(ext, "x", 10_000))
            except Exception:
                return 10_000

        target = sorted(candidates, key=left_edge)[0]

        # Trigger semantic action first; this works even when sidebar link is scrolled out.
        actions = int(self.Atspi.Action.get_n_actions(target) or 0)
        did_action = False
        for i in range(actions):
            action_name = str(self.Atspi.Action.get_action_name(target, i) or "").lower()
            if action_name in ("click", "activate", "press", "open", "jump"):
                self.Atspi.Action.do_action(target, i)
                did_action = True
                break
        if actions > 0:
            self.Atspi.Action.do_action(target, 0)
            did_action = True

        if not did_action:
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

            def app_ready() -> bool:
                if app_proc and app_proc.poll() is not None:
                    return True
                try:
                    app_ref["app"] = navigator.find_app_root()
                    return True
                except Exception:
                    return False

            if not wait_for(app_ready, timeout=args.startup_timeout):
                raise RuntimeError("Timed out waiting for NorTools accessibility tree.")
            if app_proc.poll() is not None:
                output = app_proc.stdout.read().decode("utf-8", errors="replace") if app_proc.stdout else ""
                raise RuntimeError(f"NorTools exited before screenshots could be captured.\n{output}")

            app = app_ref["app"]
            if app is None:
                raise RuntimeError("NorTools app handle missing after startup.")

            window_id = find_window_id(args.display)

            def click_with_retry(link_name: str, timeout: float = 20.0) -> None:
                start = time.monotonic()
                last_error: Exception | None = None
                while time.monotonic() - start < timeout:
                    try:
                        navigator.click_link(app, link_name)
                        return
                    except Exception as exc:
                        last_error = exc
                        time.sleep(0.5)
                if last_error is not None:
                    raise RuntimeError(f"Failed to click sidebar link '{link_name}' within {timeout}s: {last_error}") from last_error
                raise RuntimeError(f"Failed to click sidebar link '{link_name}' within {timeout}s")

            for filename, link_name, route_key in ROUTES:
                if link_name:
                    click_with_retry(link_name)
                    time.sleep(args.click_delay)
                perform_route_action(route_key, args.display, window_id)
                capture_screen(output_dir / f"{filename}.png", args.display, window_id)

        finally:
            if app_proc is not None:
                kill_process_tree(app_proc)
            if xvfb is not None:
                kill_process_tree(xvfb)

    return 0


if __name__ == "__main__":
    raise SystemExit(run())
