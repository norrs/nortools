$ErrorActionPreference = "Stop"

function Get-GitValue {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args,
        [string]$Fallback = "unknown"
    )

    try {
        $value = (& git @Args 2>$null | Select-Object -First 1)
        if ($null -eq $value) { return $Fallback }
        $trimmed = $value.ToString().Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed)) { return $Fallback }
        return $trimmed
    } catch {
        return $Fallback
    }
}

$gitCommit = Get-GitValue -Args @("rev-parse", "HEAD")
$gitBranch = Get-GitValue -Args @("rev-parse", "--abbrev-ref", "HEAD")
$gitDescribe = Get-GitValue -Args @("describe", "--tags", "--always")

$gitDirty = "false"
$scmStatus = "Clean"
try {
    $porcelain = (& git status --porcelain 2>$null)
    if ($porcelain -and $porcelain.Count -gt 0) {
        $gitDirty = "true"
        $scmStatus = "Modified"
    }
} catch {
    # Keep defaults when git status cannot be evaluated.
}

# Conventional keys consumed by Java build info stamping.
Write-Output "BUILD_SCM_REVISION $gitCommit"
Write-Output "BUILD_SCM_STATUS $scmStatus"
Write-Output "BUILD_SCM_BRANCH $gitBranch"

# Project-specific keys for direct status access.
Write-Output "STABLE_GIT_COMMIT $gitCommit"
Write-Output "STABLE_GIT_BRANCH $gitBranch"
Write-Output "STABLE_GIT_DIRTY $gitDirty"
Write-Output "STABLE_GIT_DESCRIBE $gitDescribe"
