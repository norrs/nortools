param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("generate", "check", "build")]
    [string] $Mode,

    [Parameter(Mandatory = $true)]
    [string] $Output,

    [Parameter(Mandatory = $true)]
    [string] $Bindir,

    [Parameter(Mandatory = $true)]
    [string] $NodeLocations,

    [string] $HugoWrapper = ""
)

$ErrorActionPreference = "Stop"

$root = (Get-Location).Path
$node = $NodeLocations -split "\s+" |
    Where-Object { $_ -and (Split-Path $_ -Leaf) -ieq "node.exe" } |
    Select-Object -First 1

if (-not $node) {
    throw "Unable to locate node.exe from: $NodeLocations"
}

$node = Join-Path $root $node
$env:BAZEL_BINDIR = Join-Path $root $Bindir

Set-Location (Join-Path $root "docs-site")

& $node "scripts\generate-tool-reference.mjs"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($Mode -eq "check" -or $Mode -eq "build") {
    & $node "scripts\check-docs.mjs"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if ($Mode -eq "build") {
    $wrapper = Join-Path $root $HugoWrapper
    $execBin = Split-Path (Split-Path (Split-Path $wrapper -Parent) -Parent) -Parent
    $hugo = Join-Path $execBin "node_modules\.aspect_rules_js\hugo-bin@0.149.2\node_modules\hugo-bin\vendor\hugo.exe"
    if (-not (Test-Path $hugo)) {
        throw "Unable to locate hugo.exe at $hugo"
    }
    & $hugo "--minify"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Set-Location $root
$outputPath = Join-Path $root $Output
New-Item -ItemType Directory -Force -Path (Split-Path $outputPath -Parent) | Out-Null
Set-Content -Path $outputPath -Value "$Mode`n" -NoNewline
