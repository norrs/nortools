param(
    [Parameter(Mandatory = $true)]
    [string]$Output
)

$ErrorActionPreference = "Stop"

# Bazel actions on Windows may not inherit user PATH entries (including cargo bin).
$homeCandidates = @()
if ($env:USERPROFILE) { $homeCandidates += $env:USERPROFILE }
if ($env:HOME) { $homeCandidates += $env:HOME }
if ($env:HOMEDRIVE -and $env:HOMEPATH) { $homeCandidates += "$($env:HOMEDRIVE)$($env:HOMEPATH)" }
if ($env:USERNAME) { $homeCandidates += (Join-Path "C:\Users" $env:USERNAME) }
$dotnetProfile = [Environment]::GetFolderPath("UserProfile")
if ($dotnetProfile) { $homeCandidates += $dotnetProfile }
$homeCandidates = $homeCandidates | Where-Object { $_ } | Select-Object -Unique

$cargoExe = $null
$cargoCmd = Get-Command cargo -ErrorAction SilentlyContinue
if ($cargoCmd) {
    $cargoExe = $cargoCmd.Source
}
$routinatorExe = $null
$routinatorCmd = Get-Command routinator -ErrorAction SilentlyContinue
if ($routinatorCmd) {
    $routinatorExe = $routinatorCmd.Source
}

foreach ($homeDir in $homeCandidates) {
    $cargoBin = Join-Path $homeDir ".cargo\bin"
    $cargoCandidate = Join-Path $cargoBin "cargo.exe"
    $routinatorCandidate = Join-Path $cargoBin "routinator.exe"
    if ((-not $cargoExe) -and (Test-Path $cargoCandidate)) {
        $cargoExe = $cargoCandidate
    }
    if ((-not $routinatorExe) -and (Test-Path $routinatorCandidate)) {
        $routinatorExe = $routinatorCandidate
    }
    if (Test-Path $cargoCandidate) {
        $pathParts = @($env:PATH -split ";")
        if (-not ($pathParts -contains $cargoBin)) {
            $env:PATH = "$cargoBin;$env:PATH"
        }
    }
}

$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path (Get-Location) $Output }
$outputDir = Split-Path $outputPath -Parent
if ($outputDir) { New-Item -ItemType Directory -Path $outputDir -Force | Out-Null }

# Fast path: if routinator is already installed, reuse it.
if ($routinatorExe -and (Test-Path $routinatorExe)) {
    & $routinatorExe --version | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Copy-Item -Path $routinatorExe -Destination $outputPath -Force
        Write-Host "Using existing Routinator -> $outputPath"
        exit 0
    }
}

if (-not $cargoExe) {
    throw "cargo is required when routinator is not already installed"
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

$tmpRoot = "C:\tmp"
if (-not (Test-Path $tmpRoot)) {
    $tmpRoot = [System.IO.Path]::GetTempPath()
}
$workdir = Join-Path $tmpRoot ("rtb-" + ([guid]::NewGuid().ToString("N").Substring(0, 10)))
New-Item -ItemType Directory -Path $workdir -Force | Out-Null
try {
    $archive = Join-Path $workdir "routinator.zip"
    Invoke-WebRequest -Uri "https://github.com/NLnetLabs/routinator/archive/refs/tags/$tag.zip" -OutFile $archive

    Expand-Archive -Path $archive -DestinationPath $workdir -Force

    $srcDir = Get-ChildItem -Path $workdir -Directory | Where-Object { $_.Name -like "routinator-*" } | Select-Object -First 1
    if (-not $srcDir) { throw "Could not locate extracted Routinator source directory" }

    Push-Location $srcDir.FullName
    try {
        & $cargoExe build --release --locked --bin routinator
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
    } finally {
        Pop-Location
    }

    $binaryPath = Join-Path $srcDir.FullName "target\release\routinator.exe"
    if (-not (Test-Path $binaryPath)) {
        throw "Routinator build succeeded but binary is missing: $binaryPath"
    }

    Copy-Item -Path $binaryPath -Destination $outputPath -Force

    Write-Host "Built Routinator $tag -> $outputPath"
} finally {
    Remove-Item -Path $workdir -Recurse -Force -ErrorAction SilentlyContinue
}
