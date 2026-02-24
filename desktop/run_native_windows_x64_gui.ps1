param(
    [Parameter(Mandatory = $true)]
    [string]$ZipFile,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$AppArgs
)

$ErrorActionPreference = "Stop"
$zipPath = (Resolve-Path $ZipFile).Path
$tmpDir = Join-Path $env:TEMP ("nortools-run-gui-" + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null

try {
    Expand-Archive -Path $zipPath -DestinationPath $tmpDir -Force

    $guiExe = Join-Path $tmpDir "nortools-gui.exe"
    if (-not (Test-Path $guiExe)) {
        $foundGui = Get-ChildItem -Path $tmpDir -Recurse -Filter "nortools-gui.exe" -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($foundGui) {
            $guiExe = $foundGui.FullName
        } else {
            throw "nortools-gui.exe not found after extracting $zipPath"
        }
    }

    Push-Location (Split-Path $guiExe -Parent)
    try {
        if ($AppArgs.Count -eq 0) {
            & $guiExe
        } else {
            & $guiExe @AppArgs
        }
        $code = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
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
