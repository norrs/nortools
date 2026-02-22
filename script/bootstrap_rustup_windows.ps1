param()

$ErrorActionPreference = "Stop"

Write-Host "[1/5] Downloading rustup installer..."
$installer = Join-Path $env:TEMP "rustup-init.exe"
Invoke-WebRequest https://win.rustup.rs -OutFile $installer

Write-Host "[2/5] Installing rustup stable minimal profile..."
& $installer -y --default-toolchain stable --profile minimal
if ($LASTEXITCODE -ne 0) { throw "rustup installer failed with exit code $LASTEXITCODE" }

Write-Host "[3/5] Setting MSVC toolchain as default..."
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"
& rustup default stable-x86_64-pc-windows-msvc
if ($LASTEXITCODE -ne 0) { throw "failed to set default toolchain" }

Write-Host "[4/5] Installing rustfmt + clippy..."
& rustup component add rustfmt clippy
if ($LASTEXITCODE -ne 0) { throw "failed to install rust components" }

Write-Host "[5/5] Verifying cargo and rustc..."
& cargo --version
if ($LASTEXITCODE -ne 0) { throw "cargo verification failed" }
& rustc --version
if ($LASTEXITCODE -ne 0) { throw "rustc verification failed" }

Write-Host "Rust bootstrap complete."
