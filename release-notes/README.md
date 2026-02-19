# Release Notes Files

For tag-based desktop releases, you can provide release notes by committing:

- `release-notes/<tag>.md`

Example:

- `release-notes/v0.2.0.md`

The `release-desktop-all.yml` workflow will:

1. Use that file as the GitHub Release body.
2. Publish updater manifests whose `notes` field is sourced from the release body.

If `release-notes/<tag>.md` is missing, CI falls back to a generated list of commit subjects since the previous tag.
