param(
    [Parameter(Mandatory = $true)]
    [string]$ZipFile,
    [Parameter(Mandatory = $true)]
    [string]$Output,
    [Parameter(Mandatory = $true)]
    [string]$ProductVersion
)

$ErrorActionPreference = "Stop"

function Assert-InstallerVersion {
    param([string]$Version)

    if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
        throw "ProductVersion must be major.minor.patch. Got '$Version'."
    }
}

function Escape-NsisString {
    param([string]$Value)
    return $Value.Replace('$', '$$').Replace('"', '$\"').Replace('`', '$`')
}

Assert-InstallerVersion -Version $ProductVersion

$zipPath = (Resolve-Path $ZipFile).Path
$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path (Get-Location) $Output }
$outputDir = Split-Path $outputPath -Parent
if ($outputDir) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}

$workdir = Join-Path $env:TEMP ("nortools-nsis-" + [guid]::NewGuid().ToString())
$payloadDir = Join-Path $workdir "payload"
New-Item -ItemType Directory -Path $payloadDir -Force | Out-Null

try {
    Expand-Archive -Path $zipPath -DestinationPath $payloadDir -Force

    $required = @("nortools.exe", "nortools-gui.exe", "nortools-updater.exe")
    foreach ($name in $required) {
        if (-not (Test-Path (Join-Path $payloadDir $name))) {
            throw "Required NSIS payload file is missing from ZIP: $name"
        }
    }

    $files = Get-ChildItem -Path $payloadDir -File | Sort-Object Name
    $installFiles = New-Object System.Text.StringBuilder
    $deleteFiles = New-Object System.Text.StringBuilder
    foreach ($file in $files) {
        $source = Escape-NsisString $file.FullName
        $name = Escape-NsisString $file.Name
        [void]$installFiles.AppendLine("  File `"$source`"")
        [void]$deleteFiles.AppendLine("  Delete `"`$INSTDIR\$name`"")
    }

    $nsi = Join-Path $workdir "nortools.nsi"
    $escapedOutput = Escape-NsisString $outputPath
    @"
Unicode true
ManifestSupportedOS all
RequestExecutionLevel user
!include FileFunc.nsh

Name "NorTools"
OutFile "$escapedOutput"
InstallDir "`$LOCALAPPDATA\Programs\NorTools"
InstallDirRegKey HKCU "Software\NorTools" "InstallDir"

VIProductVersion "$ProductVersion.0"
VIAddVersionKey "ProductName" "NorTools"
VIAddVersionKey "CompanyName" "norrs"
VIAddVersionKey "FileDescription" "NorTools Setup"
VIAddVersionKey "FileVersion" "$ProductVersion"
VIAddVersionKey "ProductVersion" "$ProductVersion"
VIAddVersionKey "LegalCopyright" "norrs"

SetCompressor /SOLID lzma
ShowInstDetails nevershow
ShowUninstDetails nevershow

Function .onInit
  `${GetParameters} `$R0
  `${GetOptions} `$R0 "/SILENT" `$R1
  IfErrors +2 0
  SetSilent silent
FunctionEnd

Section "NorTools" SEC01
  SetShellVarContext current
  SetOutPath "`$INSTDIR"
  SetOverwrite on
$installFiles
  CreateDirectory "`$SMPROGRAMS\NorTools"
  CreateShortCut "`$SMPROGRAMS\NorTools\NorTools.lnk" "`$INSTDIR\nortools-gui.exe"
  WriteUninstaller "`$INSTDIR\uninstall.exe"
  WriteRegStr HKCU "Software\NorTools" "InstallDir" "`$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\NorTools" "DisplayName" "NorTools"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\NorTools" "DisplayVersion" "$ProductVersion"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\NorTools" "Publisher" "norrs"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\NorTools" "InstallLocation" "`$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\NorTools" "DisplayIcon" "`$INSTDIR\nortools-gui.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\NorTools" "UninstallString" "`$\"`$INSTDIR\uninstall.exe`$\""
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\NorTools" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\NorTools" "NoRepair" 1
SectionEnd

Section "Uninstall"
  SetShellVarContext current
  Delete "`$SMPROGRAMS\NorTools\NorTools.lnk"
  RMDir "`$SMPROGRAMS\NorTools"
$deleteFiles
  Delete "`$INSTDIR\uninstall.exe"
  RMDir "`$INSTDIR"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\NorTools"
  DeleteRegKey /ifempty HKCU "Software\NorTools"
SectionEnd
"@ | Set-Content -Path $nsi -Encoding utf8

    $makensis = Get-Command makensis -ErrorAction SilentlyContinue
    if (-not $makensis) {
        throw "makensis command not found. Install NSIS and ensure makensis is on PATH."
    }

    & $makensis.Source /V2 $nsi
    if ($LASTEXITCODE -ne 0) {
        throw "makensis failed with exit code $LASTEXITCODE"
    }
} finally {
    Remove-Item $workdir -Recurse -Force -ErrorAction SilentlyContinue
}
