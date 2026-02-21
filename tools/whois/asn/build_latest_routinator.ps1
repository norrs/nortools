param(
    [Parameter(Mandatory = $true)]
    [string]$Output
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "cargo is required to build Routinator"
}

$headers = @{}
if ($env:GITHUB_TOKEN) {
    $headers["Authorization"] = "Bearer $($env:GITHUB_TOKEN)"
}

$latest = Invoke-RestMethod -Uri "https://api.github.com/repos/NLnetLabs/routinator/releases/latest" -Headers $headers
$tag = [string]$latest.tag_name
if (-not $tag) {
    throw "Could not resolve latest routinator tag from GitHub API"
}

$workdir = Join-Path $env:TEMP ("routinator-build-" + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $workdir -Force | Out-Null
try {
    $archive = Join-Path $workdir "routinator.tar.gz"
    Invoke-WebRequest -Uri "https://github.com/NLnetLabs/routinator/archive/refs/tags/$tag.tar.gz" -OutFile $archive

    tar -xzf $archive -C $workdir
    if ($LASTEXITCODE -ne 0) { throw "Failed to extract Routinator source archive" }

    $srcDir = Get-ChildItem -Path $workdir -Directory | Where-Object { $_.Name -like "routinator-*" } | Select-Object -First 1
    if (-not $srcDir) { throw "Could not locate extracted Routinator source directory" }

    Push-Location $srcDir.FullName
    try {
        cargo build --release --locked --bin routinator
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
    } finally {
        Pop-Location
    }

    $binaryPath = Join-Path $srcDir.FullName "target\release\routinator.exe"
    if (-not (Test-Path $binaryPath)) {
        throw "Routinator build succeeded but binary is missing: $binaryPath"
    }

    $outputPath = if ([System.IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path (Get-Location) $Output }
    $outputDir = Split-Path $outputPath -Parent
    if ($outputDir) { New-Item -ItemType Directory -Path $outputDir -Force | Out-Null }
    Copy-Item -Path $binaryPath -Destination $outputPath -Force

    Write-Host "Built Routinator $tag -> $outputPath"
} finally {
    Remove-Item -Path $workdir -Recurse -Force -ErrorAction SilentlyContinue
}
