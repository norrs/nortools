#!/usr/bin/env python3
"""Archive GitHub release notes and optionally prune old releases.

The script appends missing release bodies to release-notes/archive.md before it
deletes anything from GitHub. By default it only archives and reports what would
be pruned. Pass --prune to delete releases older than the retention window.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


DEFAULT_ARCHIVE = Path("release-notes/archive.md")
API_ROOT = "https://api.github.com"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo",
        default=os.environ.get("GITHUB_REPOSITORY") or infer_repo_from_git(),
        help="GitHub repository in owner/name form. Defaults to GITHUB_REPOSITORY or origin remote.",
    )
    parser.add_argument("--archive", default=str(DEFAULT_ARCHIVE), help="Append-only archive file path.")
    parser.add_argument("--keep", type=int, default=10, help="Number of newest GitHub releases to keep.")
    parser.add_argument(
        "--token-env",
        default="GITHUB_TOKEN",
        help="Environment variable containing a GitHub token.",
    )
    parser.add_argument(
        "--prune",
        action="store_true",
        help="Delete GitHub releases older than --keep after archiving their notes.",
    )
    parser.add_argument(
        "--delete-tags",
        action="store_true",
        help="Also delete matching git tags when --prune deletes releases.",
    )
    return parser.parse_args()


def infer_repo_from_git() -> str | None:
    try:
        result = subprocess.run(
            ["git", "remote", "get-url", "origin"],
            text=True,
            capture_output=True,
            check=True,
        )
    except Exception:
        return None
    remote = result.stdout.strip()
    match = re.search(r"github\.com[:/](?P<repo>[^/]+/[^/.]+)(?:\.git)?$", remote)
    return match.group("repo") if match else None


def request_json(method: str, url: str, token: str | None, payload: Any = None) -> Any:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "nortools-release-archive",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            body = response.read()
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GitHub API {method} {url} failed: {exc.code} {detail}") from exc
    if not body:
        return None
    return json.loads(body.decode("utf-8"))


def fetch_releases(repo: str, token: str | None) -> list[dict[str, Any]]:
    releases: list[dict[str, Any]] = []
    page = 1
    encoded_repo = urllib.parse.quote(repo, safe="/")
    while True:
        url = f"{API_ROOT}/repos/{encoded_repo}/releases?per_page=100&page={page}"
        batch = request_json("GET", url, token)
        if not batch:
            return releases
        releases.extend(batch)
        if len(batch) < 100:
            return releases
        page += 1


def marker_for(tag: str) -> str:
    return f"<!-- nortools-release-archive:{tag} -->"


def end_marker_for(tag: str) -> str:
    return f"<!-- /nortools-release-archive:{tag} -->"


def ensure_archive_file(path: Path) -> str:
    if path.exists():
        return path.read_text(encoding="utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    initial = (
        "# GitHub Release Notes Archive\n\n"
        "This file is append-only. Use `script/release/archive_github_releases.py` "
        "to append release bodies before pruning old GitHub releases.\n\n"
    )
    path.write_text(initial, encoding="utf-8")
    return initial


def release_entry(release: dict[str, Any]) -> str:
    tag = str(release.get("tag_name") or "untagged")
    title = str(release.get("name") or tag)
    published = str(release.get("published_at") or release.get("created_at") or "unknown")
    url = str(release.get("html_url") or "")
    body = str(release.get("body") or "").rstrip()
    if not body:
        body = "_No release notes body was published._"
    return "\n".join(
        [
            marker_for(tag),
            f"## {title}",
            "",
            f"- Tag: `{tag}`",
            f"- Published: {published}",
            f"- GitHub release: {url}",
            "",
            body,
            "",
            end_marker_for(tag),
            "",
        ],
    )


def append_missing_releases(archive_path: Path, releases: list[dict[str, Any]]) -> list[str]:
    content = ensure_archive_file(archive_path)
    appended: list[str] = []
    with archive_path.open("a", encoding="utf-8", newline="\n") as fh:
        for release in reversed(releases):
            tag = str(release.get("tag_name") or "")
            if not tag or marker_for(tag) in content:
                continue
            entry = release_entry(release)
            fh.write("\n")
            fh.write(entry)
            content += "\n" + entry
            appended.append(tag)
    return appended


def delete_release(repo: str, release: dict[str, Any], token: str) -> None:
    release_id = release.get("id")
    if not release_id:
        raise RuntimeError(f"Release has no id: {release.get('tag_name')}")
    encoded_repo = urllib.parse.quote(repo, safe="/")
    url = f"{API_ROOT}/repos/{encoded_repo}/releases/{release_id}"
    request_json("DELETE", url, token)


def delete_tag_ref(repo: str, tag: str, token: str) -> None:
    encoded_repo = urllib.parse.quote(repo, safe="/")
    encoded_tag = urllib.parse.quote(f"tags/{tag}", safe="/")
    url = f"{API_ROOT}/repos/{encoded_repo}/git/refs/{encoded_tag}"
    request_json("DELETE", url, token)


def main() -> int:
    args = parse_args()
    if not args.repo:
        print("Repository is required. Pass --repo owner/name or set GITHUB_REPOSITORY.", file=sys.stderr)
        return 2
    if args.keep < 1:
        print("--keep must be at least 1", file=sys.stderr)
        return 2

    token = os.environ.get(args.token_env)
    if args.prune and not token:
        print(f"--prune requires ${args.token_env} with GitHub contents write access.", file=sys.stderr)
        return 2

    releases = fetch_releases(args.repo, token)
    releases.sort(key=lambda item: str(item.get("created_at") or ""), reverse=True)
    appended = append_missing_releases(Path(args.archive), releases)

    prune_candidates = releases[args.keep :]
    print(f"Fetched releases: {len(releases)}")
    print(f"Archived new release notes: {len(appended)}")
    for tag in appended:
        print(f"  archived {tag}")
    print(f"Retention: keeping newest {args.keep}; old releases: {len(prune_candidates)}")

    if not prune_candidates:
        return 0

    if not args.prune:
        print("Dry run: pass --prune to delete old GitHub releases after archiving.")
        for release in prune_candidates:
            print(f"  would prune {release.get('tag_name')} ({release.get('html_url')})")
        return 0

    for release in prune_candidates:
        tag = str(release.get("tag_name") or "")
        delete_release(args.repo, release, token or "")
        print(f"Deleted release {tag}")
        if args.delete_tags and tag:
            delete_tag_ref(args.repo, tag, token or "")
            print(f"Deleted tag {tag}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
