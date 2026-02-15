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
            $guiLauncher = Join-Path (Get-Location) "nortools-gui.exe"
            if (Test-Path $guiLauncher) {
                # Prefer GUI launcher to avoid spawning a console window.
                Start-Process -FilePath $guiLauncher -WorkingDirectory (Get-Location) | Out-Null
            } else {
                # Fallback for older zips without launcher.
                Start-Process -FilePath $exe -WorkingDirectory (Get-Location) -WindowStyle Hidden | Out-Null
            }
            $code = 0
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
