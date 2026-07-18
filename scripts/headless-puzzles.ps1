[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [AllowEmptyCollection()]
    [string[]] $PuzzleArguments = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'headless-puzzles.ps1 requires 64-bit PowerShell 7 (pwsh).'
}

$distro = 'Ubuntu-24.04'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$wsl = Join-Path $env:WINDIR 'System32\wsl.exe'
if (-not (Test-Path -LiteralPath $wsl -PathType Leaf)) {
    throw "Trusted WSL executable was not found at '$wsl'."
}

& $wsl '--distribution' $distro '--exec' '/usr/bin/true'
if ($LASTEXITCODE -ne 0) {
    throw "WSL distribution '$distro' is not ready."
}

$wslArguments = @(
    '--distribution', $distro,
    '--cd', $repositoryRoot,
    '--exec', 'bash', 'scripts/headless-puzzles.sh'
) + $PuzzleArguments

& $wsl @wslArguments
$exitCode = $LASTEXITCODE
if ($null -eq $exitCode) {
    throw 'wsl.exe returned without an exit code.'
}
exit $exitCode
