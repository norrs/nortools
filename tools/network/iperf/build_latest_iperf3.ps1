param(
    [string]$Output = "",
    [string]$RuntimeOutput = "",
    [string]$Msys2Root = ""
)

$ErrorActionPreference = "Stop"
$script:Iperf3RuntimeSearchDirs = @()
$script:Iperf3Objdump = ""

if (-not $Output -and -not $RuntimeOutput) {
    throw "Either -Output or -RuntimeOutput is required."
}

$outputPath = ""
if ($Output) {
    $outputPath = if ([System.IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path (Get-Location) $Output }
    $outputDir = Split-Path $outputPath -Parent
    if ($outputDir) { New-Item -ItemType Directory -Path $outputDir -Force | Out-Null }
}

$runtimeOutputPath = ""
if ($RuntimeOutput) {
    $runtimeOutputPath = if ([System.IO.Path]::IsPathRooted($RuntimeOutput)) { $RuntimeOutput } else { Join-Path (Get-Location) $RuntimeOutput }
    $runtimeOutputDir = Split-Path $runtimeOutputPath -Parent
    if ($runtimeOutputDir) { New-Item -ItemType Directory -Path $runtimeOutputDir -Force | Out-Null }
}

function Resolve-Iperf3BinaryPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $resolvedPath = (Resolve-Path $Path).Path
    $shimPath = [System.IO.Path]::ChangeExtension($resolvedPath, ".shim")
    if (Test-Path $shimPath) {
        $shimContent = Get-Content -Path $shimPath -Raw
        if ($shimContent -match '(?m)^\s*path\s*=\s*"([^"]+)"\s*$') {
            $shimTarget = $Matches[1]
            if (Test-Path $shimTarget) {
                return (Resolve-Path $shimTarget).Path
            }
        }
    }

    return $resolvedPath
}

function Write-Iperf3Outputs {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BinaryPath,
        [Parameter(Mandatory = $true)]
        [string]$SourceLabel
    )

    $versionOutput = (& $BinaryPath --version 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "$SourceLabel is not an executable iperf3 binary: $BinaryPath"
    }
    if ($versionOutput -match "CYGWIN_NT") {
        throw "$SourceLabel is a Cygwin iperf3 build, which is not suitable for the NorTools Windows package: $BinaryPath"
    }

    if ($outputPath) {
        Copy-Item -Path $BinaryPath -Destination $outputPath -Force
        Write-Host "Using $SourceLabel ($BinaryPath) -> $outputPath"
    }

    if ($runtimeOutputPath) {
        $runtimeDir = Join-Path ([System.IO.Path]::GetTempPath()) ("nortools-iperf3-runtime-" + [Guid]::NewGuid().ToString())
        New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
        try {
            Copy-Item -Path $BinaryPath -Destination (Join-Path $runtimeDir "iperf3.exe") -Force
            Get-Iperf3RuntimeFiles -BinaryPath $BinaryPath | ForEach-Object {
                Copy-Item -Path $_.FullName -Destination (Join-Path $runtimeDir $_.Name) -Force
            }
            if (Test-Path $runtimeOutputPath) { Remove-Item $runtimeOutputPath -Force }
            Compress-Archive -Path (Join-Path $runtimeDir "*") -DestinationPath $runtimeOutputPath -Force
            Write-Host "Packed $SourceLabel runtime -> $runtimeOutputPath"
        } finally {
            Remove-Item $runtimeDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Write-Iperf3UnavailableOutputs {
    $message = @"
No vetted native Windows iperf3 binary is configured.

Upstream iperf3 does not currently build as a native MinGW/UCRT Windows executable without a Windows porting layer.
The known public Windows zip distributions tested here are Cygwin builds, and that class of binary hangs during the
local client/server handshake on this machine.

Set NORTOOLS_IPERF3_BIN to an explicitly tested Windows iperf3.exe to package it with NorTools.
"@

    if ($outputPath) {
        Set-Content -Path $outputPath -Value $message -Encoding ASCII
        Write-Host "No vetted Windows iperf3.exe configured; wrote marker -> $outputPath"
    }

    if ($runtimeOutputPath) {
        $runtimeDir = Join-Path ([System.IO.Path]::GetTempPath()) ("nortools-iperf3-unavailable-" + [Guid]::NewGuid().ToString())
        New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
        try {
            Set-Content -Path (Join-Path $runtimeDir "iperf3-unavailable.txt") -Value $message -Encoding ASCII
            if (Test-Path $runtimeOutputPath) { Remove-Item $runtimeOutputPath -Force }
            Compress-Archive -Path (Join-Path $runtimeDir "*") -DestinationPath $runtimeOutputPath -Force
            Write-Host "No vetted Windows iperf3.exe configured; wrote marker runtime -> $runtimeOutputPath"
        } finally {
            Remove-Item $runtimeDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-Iperf3RuntimeFiles {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BinaryPath
    )

    $binaryDir = Split-Path $BinaryPath -Parent
    $searchDirs = @($binaryDir) + $script:Iperf3RuntimeSearchDirs
    $searchDirs = $searchDirs | Where-Object { $_ -and (Test-Path $_) } | ForEach-Object { (Resolve-Path $_).Path } | Select-Object -Unique
    $objdump = if ($script:Iperf3Objdump -and (Test-Path $script:Iperf3Objdump)) {
        $script:Iperf3Objdump
    } else {
        $inferred = Join-Path (Split-Path (Split-Path $binaryDir -Parent) -Parent) "usr\bin\objdump.exe"
        if (Test-Path $inferred) {
            $inferred
        } else {
            $objdumpCommand = Get-Command objdump.exe -ErrorAction SilentlyContinue
            if ($objdumpCommand) { $objdumpCommand.Source } else { "" }
        }
    }
    $seen = New-Object "System.Collections.Generic.HashSet[string]"
    $files = New-Object "System.Collections.Generic.List[System.IO.FileInfo]"
    $queue = New-Object "System.Collections.Generic.Queue[string]"
    $queue.Enqueue((Resolve-Path $BinaryPath).Path)

    while ($queue.Count -gt 0) {
        $current = $queue.Dequeue()
        if (-not $seen.Add($current.ToLowerInvariant())) {
            continue
        }
        if ($current -ne (Resolve-Path $BinaryPath).Path) {
            $files.Add((Get-Item $current))
        }
        if (-not (Test-Path $objdump)) {
            continue
        }

        $imports = (& $objdump -p $current 2>$null) |
            ForEach-Object {
                if ($_ -match "DLL Name:\s*(\S+)") {
                    $Matches[1]
                }
            }
        foreach ($import in $imports) {
            foreach ($dir in $searchDirs) {
                $candidate = Join-Path $dir $import
                if (Test-Path $candidate) {
                    $queue.Enqueue((Resolve-Path $candidate).Path)
                    break
                }
            }
        }
    }

    if ($files.Count -eq 0) {
        $fallback = @(
            "msys-2.0.dll",
            "msys-crypto-3.dll",
            "msys-gcc_s-seh-1.dll",
            "msys-ssl-3.dll",
            "msys-z.dll",
            "libiperf-0.dll",
            "libgcc_s_seh-1.dll",
            "libiconv-2.dll",
            "libintl-8.dll",
            "libssp-0.dll",
            "libwinpthread-1.dll",
            "libzstd.dll",
            "zlib1.dll"
        )
        foreach ($name in $fallback) {
            foreach ($dir in $searchDirs) {
                $candidate = Join-Path $dir $name
                if (Test-Path $candidate) {
                    $files.Add((Get-Item $candidate))
                    break
                }
            }
        }
    }

    return $files
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(Mandatory = $true)]
        [string[]]$ArgumentList
    )

    & $FilePath @ArgumentList | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $FilePath $($ArgumentList -join ' ')"
    }
}

function Resolve-Msys2Root {
    if ($Msys2Root) {
        return (Resolve-Path $Msys2Root).Path
    }
    if ($env:NORTOOLS_MSYS2_ROOT) {
        return (Resolve-Path $env:NORTOOLS_MSYS2_ROOT).Path
    }
    $toolRoot = Join-Path ([System.IO.Path]::GetTempPath()) "nortools-msys2-ucrt64"
    $pacman = Join-Path $toolRoot "msys64\usr\bin\pacman.exe"
    if (Test-Path $pacman) {
        return (Join-Path $toolRoot "msys64")
    }

    New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
    $archive = Join-Path $toolRoot "msys2-base-x86_64-latest.tar.xz"
    $url = if ($env:NORTOOLS_MSYS2_BASE_URL) {
        $env:NORTOOLS_MSYS2_BASE_URL
    } else {
        "https://github.com/msys2/msys2-installer/releases/download/nightly-x86_64/msys2-base-x86_64-latest.tar.xz"
    }

    Write-Host "Downloading MSYS2 base runtime from $url"
    Invoke-WebRequest -Uri $url -OutFile $archive
    $windowsTar = Join-Path $env:SystemRoot "System32\tar.exe"
    if (-not (Test-Path $windowsTar)) {
        $windowsTar = "tar.exe"
    }
    & $windowsTar -xf $archive -C $toolRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to extract MSYS2 archive: $archive"
    }

    return (Join-Path $toolRoot "msys64")
}

function Build-Msys2Iperf3 {
    $mutex = New-Object System.Threading.Mutex($false, "NorToolsIperf3Msys2Build")
    $hasMutex = $false
    try {
        $hasMutex = $mutex.WaitOne([TimeSpan]::FromMinutes(15))
        if (-not $hasMutex) {
            throw "Timed out waiting for NorTools iperf3 MSYS2 build lock."
        }
        return Build-Msys2Iperf3Locked
    } finally {
        if ($hasMutex) {
            $mutex.ReleaseMutex()
        }
        $mutex.Dispose()
    }
}

function Build-Msys2Iperf3Locked {
    $root = Resolve-Msys2Root
    $cacheDir = Join-Path ([System.IO.Path]::GetTempPath()) "nortools-iperf3-msys2-build-cache"
    $cachedBinary = Join-Path $cacheDir "iperf3.exe"
    if (Test-Path $cachedBinary) {
        $script:Iperf3RuntimeSearchDirs = @($cacheDir, (Join-Path $root "usr\bin"))
        $script:Iperf3Objdump = Join-Path $root "usr\bin\objdump.exe"
        Write-Host "Using cached NorTools MSYS2 iperf3 runtime from $cacheDir"
        return $cachedBinary
    }

    $pacman = Join-Path $root "usr\bin\pacman.exe"
    $bash = Join-Path $root "usr\bin\bash.exe"
    if (-not (Test-Path $pacman)) {
        throw "MSYS2 pacman.exe not found under $root"
    }
    if (-not (Test-Path $bash)) {
        throw "MSYS2 bash.exe not found under $root"
    }

    Write-Host "Building controlled MSYS2 iperf3 runtime from $root"
    $pacmanLock = Join-Path $root "var\lib\pacman\db.lck"
    if (Test-Path $pacmanLock) {
        Remove-Item $pacmanLock -Force
    }
    Invoke-Checked -FilePath $bash -ArgumentList @("-lc", "pacman-key --init >/dev/null 2>&1 || true; pacman-key --populate msys2 >/dev/null 2>&1 || true")
    Invoke-Checked -FilePath $pacman -ArgumentList @(
        "-Sy",
        "--noconfirm",
        "--needed",
        "autoconf",
        "automake",
        "curl",
        "gcc",
        "libtool",
        "make",
        "openssl-devel",
        "tar",
        "zlib-devel"
    )
    $script:Iperf3RuntimeSearchDirs = @((Join-Path $root "usr\bin"))
    $script:Iperf3Objdump = Join-Path $root "usr\bin\objdump.exe"

    $workDir = Join-Path ([System.IO.Path]::GetTempPath()) ("nortools-iperf3-build-" + [Guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $workDir -Force | Out-Null
    try {
        $workDirMsys = ConvertTo-MsysPath $workDir
        $buildScript = @"
set -euo pipefail
export MSYSTEM=MSYS
export PATH=/usr/bin:`$PATH
cd "$workDirMsys"
api_url="https://api.github.com/repos/esnet/iperf/releases/latest"
tag=`$(curl -fsSL "`$api_url" | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)
if [ -z "`$tag" ]; then
  echo "Could not resolve latest iperf tag from GitHub API" >&2
  exit 1
fi
curl -fsSL "https://github.com/esnet/iperf/archive/refs/tags/`${tag}.tar.gz" -o iperf.tar.gz
tar -xzf iperf.tar.gz
src_dir=`$(find . -maxdepth 1 -type d -name 'iperf-*' | head -n1)
cd "`$src_dir"
if [ ! -x ./configure ]; then
  ./bootstrap.sh
fi
./configure --disable-shared --enable-static --without-sctp
make -j`$(nproc 2>/dev/null || echo 2)
mkdir -p "$workDirMsys/out"
cp src/iperf3.exe "$workDirMsys/out/iperf3.exe"
"@
        $scriptPath = Join-Path $workDir "build-iperf3.sh"
        Set-Content -Path $scriptPath -Value $buildScript -Encoding ASCII
        Invoke-Checked -FilePath $bash -ArgumentList @("-lc", "bash '$workDirMsys/build-iperf3.sh'")
        $binary = Join-Path $workDir "out\iperf3.exe"
        if (-not (Test-Path $binary)) {
            throw "MSYS2 iperf3 build completed but binary is missing: $binary"
        }
        Get-Iperf3RuntimeFiles -BinaryPath $binary | ForEach-Object {
            Copy-Item -Path $_.FullName -Destination (Join-Path (Split-Path $binary -Parent) $_.Name) -Force
        }
        if (Test-Path $cacheDir) { Remove-Item $cacheDir -Recurse -Force }
        New-Item -ItemType Directory -Path $cacheDir -Force | Out-Null
        Get-ChildItem -Path (Split-Path $binary -Parent) -File | ForEach-Object {
            Copy-Item -Path $_.FullName -Destination (Join-Path $cacheDir $_.Name) -Force
        }
        $script:Iperf3RuntimeSearchDirs = @($cacheDir, (Join-Path $root "usr\bin"))
        return (Join-Path $cacheDir "iperf3.exe")
    } catch {
        throw
    }
}

function ConvertTo-MsysPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $full = [System.IO.Path]::GetFullPath($Path)
    $drive = $full.Substring(0, 1).ToLowerInvariant()
    $rest = $full.Substring(2).Replace("\", "/")
    return "/$drive$rest"
}

$envIperf3 = $env:NORTOOLS_IPERF3_BIN
if ($envIperf3) {
    if (-not (Test-Path $envIperf3)) {
        throw "NORTOOLS_IPERF3_BIN points to a missing file: $envIperf3"
    }
    $resolvedIperf3 = Resolve-Iperf3BinaryPath $envIperf3
    Write-Iperf3Outputs -BinaryPath $resolvedIperf3 -SourceLabel "NORTOOLS_IPERF3_BIN"
    exit 0
}

$msys2Iperf3 = Build-Msys2Iperf3
Write-Iperf3Outputs -BinaryPath $msys2Iperf3 -SourceLabel "NorTools MSYS2 source-built iperf3"
