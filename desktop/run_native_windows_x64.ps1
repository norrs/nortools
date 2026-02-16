param(
    [Parameter(Mandatory = $true)]
    [string]$ZipFile,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$AppArgs
)

$ErrorActionPreference = "Stop"
$zipPath = (Resolve-Path $ZipFile).Path
$tmpDir = Join-Path $env:TEMP ("nortools-run-" + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null

try {
    Expand-Archive -Path $zipPath -DestinationPath $tmpDir -Force

    $exe = Join-Path $tmpDir "nortools.exe"
    if (-not (Test-Path $exe)) {
        $found = Get-ChildItem -Path $tmpDir -Recurse -Filter "nortools.exe" -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            $exe = $found.FullName
        } else {
            throw "nortools.exe not found after extracting $zipPath"
        }
    }

    Push-Location (Split-Path $exe -Parent)
    try {
        if ($AppArgs.Count -eq 0) {
            # Bazel/IDE runner mode: start desktop UI explicitly.
            # Launching nortools-gui.exe from this path can exit immediately in some hosts.
            & $exe --ui
            $code = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
        } else {
            # CLI mode: run in foreground so stdout/stderr still works.
            & $exe @AppArgs
            $code = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
        }
    } finally {
        Pop-Location
    }

    exit $code
} finally {
    try {
        Remove-Item $tmpDir -Recurse -Force -ErrorAction Stop
    } catch {
        # best-effort cleanup
    }
}
