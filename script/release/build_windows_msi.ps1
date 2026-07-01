param(
    [Parameter(Mandatory = $true)]
    [string]$ZipFile,
    [Parameter(Mandatory = $true)]
    [string]$Output,
    [Parameter(Mandatory = $true)]
    [string]$ProductVersion
)

$ErrorActionPreference = "Stop"

function Assert-MsiVersion {
    param([string]$Version)

    if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
        throw "MSI ProductVersion must be major.minor.patch. Got '$Version'."
    }
    foreach ($part in $Version.Split(".")) {
        $n = [int]$part
        if ($n -lt 0 -or $n -gt 65535) {
            throw "MSI ProductVersion field '$part' is outside 0..65535."
        }
    }
}

function New-WixId {
    param([string]$Value)
    $id = $Value -replace '[^A-Za-z0-9_]', '_'
    if ($id -notmatch '^[A-Za-z_]') {
        $id = "x_$id"
    }
    return $id
}

Assert-MsiVersion -Version $ProductVersion

$zipPath = (Resolve-Path $ZipFile).Path
$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path (Get-Location) $Output }
$outputDir = Split-Path $outputPath -Parent
if ($outputDir) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}

$workdir = Join-Path $env:TEMP ("nortools-msi-" + [guid]::NewGuid().ToString())
$payloadDir = Join-Path $workdir "payload"
New-Item -ItemType Directory -Path $payloadDir -Force | Out-Null

try {
    Expand-Archive -Path $zipPath -DestinationPath $payloadDir -Force

    $required = @("nortools.exe", "nortools-gui.exe", "nortools-updater.exe")
    foreach ($name in $required) {
        if (-not (Test-Path (Join-Path $payloadDir $name))) {
            throw "Required MSI payload file is missing from ZIP: $name"
        }
    }

    $files = Get-ChildItem -Path $payloadDir -File | Sort-Object Name
    $components = New-Object System.Text.StringBuilder
    foreach ($file in $files) {
        $base = New-WixId $file.BaseName
        $componentId = "cmp_$base"
        $fileId = "fil_$base"
        $source = $file.FullName.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;").Replace('"', "&quot;")
        [void]$components.AppendLine("      <Component Id=`"$componentId`" Guid=`"*`">")
        [void]$components.AppendLine("        <File Id=`"$fileId`" Source=`"$source`" KeyPath=`"yes`" />")
        [void]$components.AppendLine("      </Component>")
    }

    $wxs = Join-Path $workdir "nortools.wxs"
    @"
<Wix xmlns="http://wixtoolset.org/schemas/v4/wxs">
  <Package
      Name="NorTools"
      Manufacturer="norrs"
      Version="$ProductVersion"
      UpgradeCode="8F3AC956-56EC-4C35-AC4E-A49769C77B91"
      Scope="perUser">
    <MajorUpgrade DowngradeErrorMessage="A newer version of NorTools is already installed." />
    <MediaTemplate EmbedCab="yes" />

    <StandardDirectory Id="LocalAppDataFolder">
      <Directory Id="LocalProgramsFolder" Name="Programs">
        <Directory Id="INSTALLFOLDER" Name="NorTools" />
      </Directory>
    </StandardDirectory>

    <StandardDirectory Id="ProgramMenuFolder">
      <Directory Id="ApplicationProgramsFolder" Name="NorTools">
        <Component Id="StartMenuShortcut" Guid="*">
          <Shortcut Id="NorToolsShortcut"
                    Name="NorTools"
                    Target="[INSTALLFOLDER]nortools-gui.exe"
                    WorkingDirectory="INSTALLFOLDER" />
          <RemoveFolder Id="ApplicationProgramsFolder" On="uninstall" />
          <RegistryValue Root="HKCU"
                         Key="Software\NorTools"
                         Name="installed"
                         Type="integer"
                         Value="1"
                         KeyPath="yes" />
        </Component>
      </Directory>
    </StandardDirectory>

    <ComponentGroup Id="AppFiles" Directory="INSTALLFOLDER">
$components
    </ComponentGroup>

    <Feature Id="MainFeature" Title="NorTools" Level="1">
      <ComponentGroupRef Id="AppFiles" />
      <ComponentRef Id="StartMenuShortcut" />
    </Feature>
  </Package>
</Wix>
"@ | Set-Content -Path $wxs -Encoding utf8

    $wix = Get-Command wix -ErrorAction SilentlyContinue
    if (-not $wix) {
        throw "wix command not found. Install with: dotnet tool install --global wix"
    }

    & $wix.Source build $wxs -o $outputPath
    if ($LASTEXITCODE -ne 0) {
        throw "wix build failed with exit code $LASTEXITCODE"
    }
} finally {
    Remove-Item $workdir -Recurse -Force -ErrorAction SilentlyContinue
}
