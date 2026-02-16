param(
    [Parameter(Mandatory = $true)]
    [string]$DeployJar,
    [Parameter(Mandatory = $true)]
    [string]$Output,
    [Parameter(Mandatory = $true)]
    [string]$GraalConfigs,
    [string]$GuiLauncher = ""
)

$ErrorActionPreference = "Stop"

if (-not $env:ProgramFiles) { $env:ProgramFiles = "C:\Program Files" }
if (-not ${env:ProgramFiles(x86)}) { ${env:ProgramFiles(x86)} = "C:\Program Files (x86)" }

$vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
if (Test-Path $vswhere) {
    $vsdir = Split-Path $vswhere -Parent
    $env:PATH = "$vsdir;$env:PATH"
}

$deployJarPath = (Resolve-Path $DeployJar).Path
$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path (Get-Location) $Output }

$nativeImage = $null
if ($env:GRAALVM_HOME) {
    $cand1 = Join-Path $env:GRAALVM_HOME "bin\native-image.cmd"
    $cand2 = Join-Path $env:GRAALVM_HOME "bin\native-image.exe"
    if (Test-Path $cand1) {
        $nativeImage = $cand1
    } elseif (Test-Path $cand2) {
        $nativeImage = $cand2
    }
}
if (-not $nativeImage) {
    $cmd = Get-Command native-image -ErrorAction SilentlyContinue
    if ($cmd) { $nativeImage = $cmd.Source }
}
if (-not $nativeImage) {
    $userHome = $env:USERPROFILE
    if (-not $userHome) { $userHome = [Environment]::GetFolderPath("UserProfile") }
    if ($userHome) {
        $miseRoot = Join-Path $userHome ".local\share\mise\installs\java"
        if (Test-Path $miseRoot) {
            $cands = Get-ChildItem -Path (Join-Path $miseRoot "oracle-graalvm-*\bin\native-image.cmd") -ErrorAction SilentlyContinue
            if (-not $cands) {
                $cands = Get-ChildItem -Path (Join-Path $miseRoot "oracle-graalvm-*\bin\native-image.exe") -ErrorAction SilentlyContinue
            }
            if ($cands) { $nativeImage = $cands[-1].FullName }
        }
    }
    if (-not $nativeImage -and $userHome) {
        $scoopRoot = Join-Path $userHome "scoop\apps\graalvm25"
        $scoopCurrent = Join-Path $scoopRoot "current\bin\native-image.cmd"
        if (Test-Path $scoopCurrent) {
            $nativeImage = $scoopCurrent
        } else {
            $scoopCands = Get-ChildItem -Path (Join-Path $scoopRoot "*\bin\native-image.cmd") -ErrorAction SilentlyContinue
            if ($scoopCands) { $nativeImage = $scoopCands[-1].FullName }
        }
    }
}
if (-not $nativeImage) {
    throw "native-image not found. Install GraalVM (e.g. mise install java oracle-graalvm-25.0.1) or set GRAALVM_HOME."
}

# Prefer explicitly loading VS 2022 x64 build env so native-image does not auto-select unsupported VS major versions.
$vs17Install = $null
if (Test-Path $vswhere) {
    $vs17Install = & $vswhere -latest -version "[17.0,18.0)" -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
}
if ($vs17Install) {
    $vcvars = Join-Path $vs17Install "VC\Auxiliary\Build\vcvars64.bat"
    if (Test-Path $vcvars) {
        cmd.exe /d /s /c ('call "' + $vcvars + '" >nul && set') | ForEach-Object {
            $idx = $_.IndexOf("=")
            if ($idx -gt 0) {
                $name = $_.Substring(0, $idx)
                $value = $_.Substring($idx + 1)
                Set-Item -Path ("Env:" + $name) -Value $value
            }
        }
    }
}

function Find-VcRuntimeDir {
    $dirs = @()

    if ($env:VCToolsRedistDir) {
        $dirs += (Join-Path $env:VCToolsRedistDir "x64\Microsoft.VC143.CRT")
        $dirs += (Join-Path $env:VCToolsRedistDir "x64\Microsoft.VC142.CRT")
    }

    if ($vs17Install) {
        $dirs += (Get-ChildItem -Path (Join-Path $vs17Install "VC\Redist\MSVC\*\x64\Microsoft.VC*.CRT") -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    }

    foreach ($dir in $dirs) {
        if ((Test-Path $dir) -and (Test-Path (Join-Path $dir "vcruntime140_1.dll"))) {
            return $dir
        }
    }

    foreach ($dir in $dirs) {
        if ((Test-Path $dir) -and (Test-Path (Join-Path $dir "vcruntime140.dll"))) {
            return $dir
        }
    }

    return $null
}

$workdir = Join-Path $env:TEMP ("nortools-native-" + [guid]::NewGuid().ToString())
$configDir = Join-Path $workdir "config"
New-Item -ItemType Directory -Path $configDir -Force | Out-Null

foreach ($f in $GraalConfigs.Split(" ")) {
    if ($f) {
        Copy-Item -Path $f -Destination $configDir -Force
    }
}

$nativeArgs = @(
    "-jar", $deployJarPath,
    "-o", (Join-Path $workdir "nortools"),
    "--enable-url-protocols=https",
    "--enable-native-access=ALL-UNNAMED",
    "-H:+UnlockExperimentalVMOptions",
    "-H:-CheckToolchain",
    "-Djava.awt.headless=true",
    "-H:+AddAllCharsets",
    "-H:ConfigurationFileDirectories=$configDir",
    "--initialize-at-build-time=org.slf4j",
    "--initialize-at-run-time=org.xbill.DNS.ResolverConfig,org.xbill.DNS.ExtendedResolver,org.xbill.DNS.SimpleResolver,org.xbill.DNS.Lookup,org.xbill.DNS.Address,org.xbill.DNS.Options,no.norrs.nortools.lib.dns.DnsResolver",
    "-H:IncludeResourceBundles=jakarta.servlet.LocalStrings,jakarta.servlet.http.LocalStrings",
    "--no-fallback"
)
& $nativeImage @nativeArgs
if ($LASTEXITCODE -ne 0) {
    throw ("native-image failed with exit code " + $LASTEXITCODE)
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($deployJarPath)
foreach ($entryName in @("native/windows/x86_64/webview.dll", "native/windows/x86_64/libwinpthread-1.dll")) {
    $entry = $zip.GetEntry($entryName)
    if ($entry) {
        $dest = Join-Path $workdir ([System.IO.Path]::GetFileName($entryName))
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
    }
}
$zip.Dispose()

$zipInputs = @(Join-Path $workdir "nortools.exe")
foreach ($extra in @("webview.dll", "libwinpthread-1.dll")) {
    $p = Join-Path $workdir $extra
    if (Test-Path $p) { $zipInputs += $p }
}

# Bundle MSVC runtime DLLs for machines without VC++ Redistributable installed.
$vcRuntimeDir = Find-VcRuntimeDir
if ($vcRuntimeDir) {
    foreach ($dll in @("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "concrt140.dll")) {
        $src = Join-Path $vcRuntimeDir $dll
        if (Test-Path $src) {
            $dest = Join-Path $workdir $dll
            Copy-Item -Path $src -Destination $dest -Force
            $zipInputs += $dest
        }
    }
} else {
    Write-Warning "MSVC runtime directory not found. Output zip may require VC++ Redistributable on target machines."
}

if ($GuiLauncher -and (Test-Path $GuiLauncher)) {
    $launcherDest = Join-Path $workdir "nortools-gui.exe"
    Copy-Item -Path $GuiLauncher -Destination $launcherDest -Force
    $zipInputs += $launcherDest
}

if (Test-Path $outputPath) { Remove-Item $outputPath -Force }
Compress-Archive -Path $zipInputs -DestinationPath $outputPath -Force
Remove-Item $workdir -Recurse -Force
