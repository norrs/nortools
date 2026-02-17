# Development Notes

## Frontend Ownership

The UI is now served from resources under `web/src/main/resources`:

- `web/src/main/resources/vue` for JavalinVue layout/components

The old standalone `frontend/` Vite project and legacy SPA bundle are no longer part of active Bazel targets.

## Verify Main Targets

```bash
bazelisk test //web:web_test
bazelisk build //web:web //desktop:desktop //desktop:desktop_jar_deploy.jar
```

## Native Smoke

```bash
bazelisk run //desktop:run-native-linux-x64
```
