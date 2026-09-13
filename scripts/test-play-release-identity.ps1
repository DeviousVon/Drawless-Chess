[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Load only the pure identity-check functions, never the signing/build workflow.
$verifierPath = Join-Path $PSScriptRoot 'verify-play-aab.ps1'
$parseErrors = $null
$tokens = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    $verifierPath, [ref]$tokens, [ref]$parseErrors
)
if ($parseErrors.Count -ne 0) { throw 'The AAB verifier did not parse.' }
$functionNames = @('Fail', 'Assert-Equal', 'Get-ZipEntrySha256', 'Assert-PublicReleaseIdentity')
foreach ($functionName in $functionNames) {
    $definitions = @($ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq $functionName
    }, $true))
    if ($definitions.Count -ne 1) { throw "Expected one $functionName function." }
    . ([scriptblock]::Create($definitions[0].Extent.Text))
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$utf8 = [Text.UTF8Encoding]::new($false)
function Hash-Text([string]$Text) {
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($utf8.GetBytes($Text))
    ).ToLowerInvariant()
}

$identity = "schemaVersion=1`nplatform=Android`nversion=1.0.3`nbuild=7`n"
$digest = ('a' * 64) + "`n"
$identityAsset = 'base/assets/release/SOURCE-IDENTITY'
$digestAsset = 'base/assets/release/SOURCE-MANIFEST.sha256.digest'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'drawless-public-identity-test-' + [guid]::NewGuid().ToString('N')
)
[void][IO.Directory]::CreateDirectory($temporaryRoot)
$passed = 0
try {
    $cases = @(
        @{ Name = 'valid exact public identity'; Entries = @{ $identityAsset = $identity; $digestAsset = $digest }; Error = $null },
        @{ Name = 'missing identity'; Entries = @{ $digestAsset = $digest }; Error = 'missing its public release identity' },
        @{ Name = 'missing manifest digest'; Entries = @{ $identityAsset = $identity }; Error = 'missing its public release identity' },
        @{ Name = 'stale version'; Entries = @{ $identityAsset = $identity.Replace('build=7', 'build=6'); $digestAsset = $digest }; Error = 'packaged public identity' },
        @{ Name = 'tampered manifest digest'; Entries = @{ $identityAsset = $identity; $digestAsset = ('b' * 64) + "`n" }; Error = 'packaged public identity' },
        @{ Name = 'private commit'; Entries = @{ $identityAsset = $identity; $digestAsset = $digest; 'base/assets/release/SOURCE-COMMIT' = 'private identity' }; Error = 'private source-commit asset' },
        @{ Name = 'case changed private commit'; Entries = @{ $identityAsset = $identity; $digestAsset = $digest; 'base/assets/source-commit' = 'private identity' }; Error = 'private source-commit asset' },
        @{ Name = 'case changed identity path'; Entries = @{ 'base/assets/release/source-identity' = $identity; $digestAsset = $digest }; Error = 'unrecognized release identity asset' },
        @{ Name = 'case changed release directory'; Entries = @{ $identityAsset = $identity; $digestAsset = $digest; 'base/assets/Release/BUILD-HOST' = 'unapproved metadata' }; Error = 'unrecognized release identity asset' },
        @{ Name = 'unrecognized release metadata'; Entries = @{ $identityAsset = $identity; $digestAsset = $digest; 'base/assets/release/BUILD-HOST' = 'unapproved metadata' }; Error = 'unrecognized release identity asset' }
    )
    foreach ($case in $cases) {
        $zipPath = Join-Path $temporaryRoot ([guid]::NewGuid().ToString('N') + '.zip')
        $zip = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($path in $case.Entries.Keys) {
                $entry = $zip.CreateEntry($path)
                $stream = $entry.Open()
                try {
                    $bytes = $utf8.GetBytes($case.Entries[$path])
                    $stream.Write($bytes, 0, $bytes.Length)
                } finally { $stream.Dispose() }
            }
        } finally { $zip.Dispose() }
        $zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
        $failure = $null
        try {
            $evidence = @(Assert-PublicReleaseIdentity -Archive $zip `
                -IdentitySha256 (Hash-Text $identity) -ManifestDigestSha256 (Hash-Text $digest))
            if ($evidence.Count -ne 2) { throw 'Expected two verified public identity assets.' }
        } catch {
            $failure = $_.Exception.Message
        } finally { $zip.Dispose() }
        if ($null -eq $case.Error -and $null -ne $failure) {
            throw "$($case.Name) unexpectedly failed: $failure"
        }
        if ($null -ne $case.Error -and
            ($null -eq $failure -or -not $failure.Contains($case.Error))) {
            throw "$($case.Name) did not fail for its intended reason: $failure"
        }
        $passed += 1
    }
} finally {
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
}
Write-Output "Public release identity checks PASS: $passed cases"
