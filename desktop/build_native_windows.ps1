param(
    [Parameter(Mandatory = $true)]
    [string]$DeployJar,
    [Parameter(Mandatory = $true)]
    [string]$Output,
    [Parameter(Mandatory = $true)]
    [string]$GraalConfigs,
    [string]$GuiLauncher = "",
    [string]$UpdaterHelper = "",
    [string]$TitleBarIconHelper = "",
    [string]$RoutinatorBinary = "",
    [string]$IconFile = "",
    [string]$Rcedit = ""
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
            $patterns = @("graalvm-community-*")
            $cands = $null
            foreach ($pattern in $patterns) {
                $cands = Get-ChildItem -Path (Join-Path $miseRoot "$pattern\bin\native-image.cmd") -ErrorAction SilentlyContinue
                if (-not $cands) {
                    $cands = Get-ChildItem -Path (Join-Path $miseRoot "$pattern\bin\native-image.exe") -ErrorAction SilentlyContinue
                }
                if ($cands) { break }
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
    throw "native-image not found. Install GraalVM (e.g. mise install java graalvm-community-25.0.2) or set GRAALVM_HOME."
}

# Explicitly load the x64 MSVC build env. Native-image can find cl.exe from PATH,
# but cl.exe also needs INCLUDE/LIB from vcvars64.bat to compile helper probes.
$vsInstall = $null
if (Test-Path $vswhere) {
    $vsInstall = (& $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath).Trim()
}
if ($vsInstall) {
    $vcvars = Join-Path $vsInstall "VC\Auxiliary\Build\vcvars64.bat"
    if (Test-Path $vcvars) {
        cmd.exe /d /s /c ('call "' + $vcvars + '" >nul && set') | ForEach-Object {
            $idx = $_.IndexOf("=")
            if ($idx -gt 0) {
                $name = $_.Substring(0, $idx)
                $value = $_.Substring($idx + 1)
                Set-Item -Path ("Env:" + $name) -Value $value
            }
        }
    } else {
        throw "vcvars64.bat not found at '$vcvars'"
    }
} else {
    throw "Visual Studio C++ x64 toolchain not found. Install VC.Tools.x86.x64."
}

if ([string]::IsNullOrWhiteSpace($env:INCLUDE) -or [string]::IsNullOrWhiteSpace($env:LIB)) {
    throw "MSVC build environment is incomplete after vcvars64.bat: INCLUDE and/or LIB is empty."
}

function Find-Rcedit {
    if ($Rcedit) {
        if (Test-Path $Rcedit) {
            return (Resolve-Path $Rcedit).Path
        }
        throw "Rcedit parameter points to a missing file: $Rcedit"
    }

    $command = Get-Command RCEDIT64.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    if ($env:RCEDIT64) {
        if (Test-Path $env:RCEDIT64) {
            return (Resolve-Path $env:RCEDIT64).Path
        }
        throw "RCEDIT64 environment variable points to a missing file: $env:RCEDIT64"
    }

    return $null
}

function Set-ExecutableIcon {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ExePath,
        [Parameter(Mandatory = $true)]
        [string]$IconPath,
        [bool]$Required = $true
    )

    if ((Test-Path $ExePath) -and (Test-Path $IconPath)) {
        $rcedit = Find-Rcedit
        if (-not $rcedit) {
            throw "RCEDIT64.exe not found. Download winrun4J-0.4.5.zip from https://github.com/poidasmith/winrun4j/releases and put RCEDIT64.exe on PATH, or set RCEDIT64 to its full path."
        }

        & $rcedit /I (Resolve-Path $ExePath).Path (Resolve-Path $IconPath).Path
        if ($LASTEXITCODE -ne 0) {
            if (-not $Required) {
                Write-Warning "RCEDIT64.exe could not set icon on optional executable: $ExePath"
                return
            }
            throw "RCEDIT64.exe failed with exit code $LASTEXITCODE while setting icon on $ExePath"
        }
    }
}

function Set-WindowsSubsystem {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ExePath
    )

    $editbin = Get-Command editbin.exe -ErrorAction SilentlyContinue
    if (-not $editbin) {
        throw "editbin.exe not found after loading the Visual Studio C++ environment."
    }

    & $editbin.Source /NOLOGO /SUBSYSTEM:WINDOWS (Resolve-Path $ExePath).Path
    if ($LASTEXITCODE -ne 0) {
        throw "editbin.exe failed with exit code $LASTEXITCODE while setting Windows subsystem on $ExePath"
    }
}

function Find-VcRuntimeDir {
    $dirs = @()

    if ($env:VCToolsRedistDir) {
        $dirs += (Join-Path $env:VCToolsRedistDir "x64\Microsoft.VC143.CRT")
        $dirs += (Join-Path $env:VCToolsRedistDir "x64\Microsoft.VC142.CRT")
    }

    if ($vsInstall) {
        $dirs += (Get-ChildItem -Path (Join-Path $vsInstall "VC\Redist\MSVC\*\x64\Microsoft.VC*.CRT") -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
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

$nativeDeployJarPath = $deployJarPath
if ($TitleBarIconHelper -and (Test-Path $TitleBarIconHelper)) {
    $helperHash = (Get-FileHash -Path (Resolve-Path $TitleBarIconHelper).Path -Algorithm SHA256).Hash.ToLowerInvariant()
    $runtimeResourceDir = Join-Path $workdir "runtime-resources"
    New-Item -ItemType Directory -Path $runtimeResourceDir -Force | Out-Null
    $runtimePropertiesPath = Join-Path $runtimeResourceDir "nortools-runtime.properties"
    Set-Content -Path $runtimePropertiesPath -Value "nortools.titlebarIconHelper.sha256=$helperHash" -Encoding ascii

    $nativeDeployJarPath = Join-Path $workdir "desktop_jar_with_runtime_deploy.jar"
    Copy-Item -Path $deployJarPath -Destination $nativeDeployJarPath -Force
    Set-ItemProperty -Path $nativeDeployJarPath -Name IsReadOnly -Value $false

    $jarCommand = Get-Command jar -ErrorAction SilentlyContinue
    if (-not $jarCommand) {
        throw "jar command not found; required to embed nortools-runtime.properties in the native image input."
    }
    & $jarCommand.Source uf $nativeDeployJarPath -C $runtimeResourceDir "nortools-runtime.properties"
    if ($LASTEXITCODE -ne 0) {
        throw ("jar failed while embedding nortools-runtime.properties with exit code " + $LASTEXITCODE)
    }
}

$nativeArgs = @(
    "-jar", $nativeDeployJarPath,
    "-o", (Join-Path $workdir "nortools"),
    "--enable-url-protocols=http,https",
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

if ($IconFile) {
    Set-ExecutableIcon -ExePath (Join-Path $workdir "nortools.exe") -IconPath $IconFile
    $iconDest = Join-Path $workdir "nortools.ico"
    Copy-Item -Path $IconFile -Destination $iconDest -Force
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
if (Test-Path (Join-Path $workdir "nortools.ico")) {
    $zipInputs += (Join-Path $workdir "nortools.ico")
}
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

$launcherDest = Join-Path $workdir "nortools-gui.exe"
Copy-Item -Path (Join-Path $workdir "nortools.exe") -Destination $launcherDest -Force
Set-WindowsSubsystem -ExePath $launcherDest
if ($IconFile) {
    Set-ExecutableIcon -ExePath $launcherDest -IconPath $IconFile
}
$zipInputs += $launcherDest

if ($UpdaterHelper -and (Test-Path $UpdaterHelper)) {
    $helperDest = Join-Path $workdir "nortools-updater.exe"
    Copy-Item -Path $UpdaterHelper -Destination $helperDest -Force
    if ($IconFile) {
        Set-ExecutableIcon -ExePath $helperDest -IconPath $IconFile -Required $false
    }
    $zipInputs += $helperDest
}

if ($TitleBarIconHelper -and (Test-Path $TitleBarIconHelper)) {
    $titleBarHelperDest = Join-Path $workdir "nortools-titlebar-icon.exe"
    Copy-Item -Path $TitleBarIconHelper -Destination $titleBarHelperDest -Force
    if ($IconFile) {
        Set-ExecutableIcon -ExePath $titleBarHelperDest -IconPath $IconFile -Required $false
    }
    $zipInputs += $titleBarHelperDest
}

if ($RoutinatorBinary -and (Test-Path $RoutinatorBinary)) {
    $routinatorDest = Join-Path $workdir "routinator.exe"
    Copy-Item -Path $RoutinatorBinary -Destination $routinatorDest -Force
    $zipInputs += $routinatorDest
}

if (Test-Path $outputPath) { Remove-Item $outputPath -Force }
Compress-Archive -Path $zipInputs -DestinationPath $outputPath -Force
Remove-Item $workdir -Recurse -Force
