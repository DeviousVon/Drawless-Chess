[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [AllowEmptyCollection()]
    [string[]] $SelfPlayArguments = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'headless-selfplay.ps1 requires 64-bit PowerShell 7 (pwsh), not Windows PowerShell 5.1.'
}

$distro = 'Ubuntu-24.04'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$wsl = Join-Path $env:WINDIR 'System32\wsl.exe'
if (-not (Test-Path -LiteralPath $wsl -PathType Leaf)) {
    throw "Trusted WSL executable was not found at '$wsl'."
}

# Probe the exact named distribution without parsing localized `wsl --list` output.
& $wsl '--distribution' $distro '--exec' '/usr/bin/true'
if ($LASTEXITCODE -ne 0) {
    throw "WSL distribution '$distro' is not ready. Finish its first-launch setup, then retry."
}

# PowerShell 7 passes each array element as one native argument. Do not flatten this array into
# a command string: config/output paths may contain spaces and must never be re-evaluated by a shell.
$wslArguments = @(
    '--distribution', $distro,
    '--cd', $repositoryRoot,
    '--exec', 'bash', 'scripts/headless-selfplay.sh'
) + $SelfPlayArguments

& $wsl @wslArguments
$exitCode = $LASTEXITCODE
if ($null -eq $exitCode) {
    throw 'wsl.exe returned without an exit code.'
}
exit $exitCode
