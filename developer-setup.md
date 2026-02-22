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
cargo install --locked routinator
bazelisk run //desktop:run-native-macos-arm64
```

## Windows (PowerShell)

```powershell
winget install jdx.mise bazelbuild.bazelisk
mise trust
mise install
# Required for Routinator builds:
./script/bootstrap_rustup_windows.ps1
cargo install --locked routinator
bazelisk run //desktop:run-native-windows-x64
```

## Notes

- Repo pins Java to `graalvm-community-25.0.2` in `mise.toml`.
- Repo also pins Rust `stable` in `mise.toml` for Cargo-based Routinator setup.
- `mise install` installs the pinned toolchain(s) required by this repo.
