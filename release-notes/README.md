# Release Notes Files

For tag-based desktop releases, you can provide release notes by committing:

- `release-notes/<tag>.md`

Example:

- `release-notes/v0.2.0.md`

The `release-desktop-all.yml` workflow will:

1. Use that file as the GitHub Release body.
2. Publish updater manifests whose `notes` field is sourced from the release body.

If `release-notes/<tag>.md` is missing, CI falls back to a generated list of commit subjects since the previous tag.

## Archive and Prune GitHub Releases

Use `script/release/archive_github_releases.py` to append GitHub release bodies to
`release-notes/archive.md` before deleting old release entries from GitHub.

Examples:

```bash
# Archive missing release notes and show which releases would be pruned.
GITHUB_TOKEN=... python script/release/archive_github_releases.py --repo norrs/nortools

# Archive notes, keep the newest 10 GitHub releases, and delete older releases.
GITHUB_TOKEN=... python script/release/archive_github_releases.py --repo norrs/nortools --prune
```

The script does not delete git tags unless `--delete-tags` is also passed.
