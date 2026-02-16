# Development Notes

## Updating Frontend npm Dependencies (Bazel + pnpm)

The frontend dependency graph is hermetic and lockfile-driven:

- `pnpm-lock.yaml` is the source of truth for npm resolution.
- `MODULE.bazel` uses `npm_translate_lock(...)` to turn that lockfile into Bazel repos.
- `frontend/BUILD.bazel` controls which npm packages are present during Vite build via `BUILD_DEPS`.

If any of these are skipped, Bazel can fail with errors like:
`Rollup failed to resolve import "..."`

### Workflow

1. Edit `frontend/package.json` and add/remove dependency entries.
2. Regenerate lockfile from repo root:
   - If you have `pnpm` locally:
     - `pnpm install --lockfile-only`
   - If you do not have `pnpm` locally, use Bazel-managed pnpm:
     - `bazelisk run @pnpm//:pnpm -- install --lockfile-only`
3. Update `frontend/BUILD.bazel` `BUILD_DEPS` list to include the package label(s):
   - Example: add `"markdown-it"` as `":node_modules/markdown-it"`.
4. Verify:
   - `bazelisk build //frontend:build`
   - If the frontend is consumed by desktop/web, also verify:
     - `bazelisk build //desktop:run-native-linux-x64`
     - `bazelisk test //web:web_test`

### Notes

- Keep `pnpm-lock.yaml` committed with `frontend/package.json` changes.
- For TypeScript-only type packages, include them in `BUILD_DEPS` when they are needed by compilation.
- If IDE/Bazel server cache gets stale after dependency changes, restart Bazel server once:
  - `bazelisk shutdown`

