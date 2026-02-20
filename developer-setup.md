# Developer Setup (Minimal)

Run these commands from repo root.

## Linux

```bash
sudo apt update
sudo apt install -y mise bazelisk
mise trust
mise install
bazelisk run //desktop:run-native-linux-x64
```

## macOS (Apple Silicon)

```bash
brew install mise bazelisk
mise trust
mise install
bazelisk run //desktop:run-native-macos-arm64
```

## Windows (PowerShell)

```powershell
winget install jdx.mise bazelbuild.bazelisk
mise trust
mise install
bazelisk run //desktop:run-native-windows-x64
```

## Notes

- Repo pins Java to `graalvm-community-25.0.2` in `mise.toml`.
- `mise install` installs the pinned GraalVM and other required tools.
