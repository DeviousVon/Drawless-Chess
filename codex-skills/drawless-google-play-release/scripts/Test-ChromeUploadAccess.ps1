[CmdletBinding()]
param(
    [string]$ProfileDirectory,
    [string]$ExtensionId = 'hehggadaopoacecdllhhajmbjkdcmajg'
)

$ErrorActionPreference = 'Stop'

function Convert-ChromeTime {
    param([object]$Value)

    if ($null -eq $Value) {
        return $null
    }

    $microseconds = [int64]$Value
    $epoch = [DateTime]::SpecifyKind([datetime]'1601-01-01', [DateTimeKind]::Utc)
    return $epoch.AddTicks($microseconds * 10).ToString('o')
}

try {
    $userDataRoot = Join-Path $env:LOCALAPPDATA 'Google\Chrome\User Data'

    if ([string]::IsNullOrWhiteSpace($ProfileDirectory)) {
        $localStatePath = Join-Path $userDataRoot 'Local State'
        if (-not (Test-Path -LiteralPath $localStatePath -PathType Leaf)) {
            throw 'Chrome Local State was not found.'
        }
        $localState = Get-Content -LiteralPath $localStatePath -Raw | ConvertFrom-Json -AsHashtable
        $ProfileDirectory = [string]$localState['profile']['last_used']
    }

    if ([string]::IsNullOrWhiteSpace($ProfileDirectory)) {
        throw 'The active Chrome profile could not be determined.'
    }

    $profileRoot = Join-Path $userDataRoot $ProfileDirectory
    $preferencesPath = Join-Path $profileRoot 'Secure Preferences'

    if (-not (Test-Path -LiteralPath $preferencesPath -PathType Leaf)) {
        throw "Chrome profile preferences were not found for $ProfileDirectory."
    }

    $preferences = Get-Content -LiteralPath $preferencesPath -Raw | ConvertFrom-Json -AsHashtable
    $extension = $preferences['extensions']['settings'][$ExtensionId]

    if ($null -eq $extension) {
        [pscustomobject]@{
            browser = 'chrome'
            profile = $ProfileDirectory
            extensionId = $ExtensionId
            extensionInstalled = $false
            extensionEnabled = $false
            fileUrlAccess = $false
            ready = $false
            reason = 'ChatGPT Chrome extension is not installed in the selected profile.'
        } | ConvertTo-Json -Depth 4
        exit 1
    }

    $disableReasons = @($extension['disable_reasons'])
    $enabled = $disableReasons.Count -eq 0
    $fileUrlAccess = $extension.ContainsKey('allowed_file_scheme_access') -and
        [bool]$extension['allowed_file_scheme_access']
    $ready = $enabled -and $fileUrlAccess

    [pscustomobject]@{
        browser = 'chrome'
        profile = $ProfileDirectory
        extensionId = $ExtensionId
        extensionInstalled = $true
        extensionEnabled = $enabled
        extensionVersion = $extension['manifest']['version']
        extensionInstalledAtUtc = Convert-ChromeTime $extension['first_install_time']
        fileUrlAccess = $fileUrlAccess
        ready = $ready
        reason = if ($ready) {
            'Chrome upload access is ready.'
        } elseif (-not $enabled) {
            'ChatGPT Chrome extension is disabled in the selected profile.'
        } else {
            'Allow access to file URLs is disabled in the selected profile.'
        }
    } | ConvertTo-Json -Depth 4

    if (-not $ready) {
        exit 1
    }
} catch {
    [pscustomobject]@{
        browser = 'chrome'
        profile = $ProfileDirectory
        extensionId = $ExtensionId
        ready = $false
        reason = $_.Exception.Message
    } | ConvertTo-Json -Depth 4
    exit 2
}
