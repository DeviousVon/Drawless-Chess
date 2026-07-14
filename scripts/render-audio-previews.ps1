[CmdletBinding()]
param(
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repositoryRoot 'android\app\build\audio-previews'
}
$compiler = Join-Path $repositoryRoot 'node_modules\kotlin-compiler\bin\kotlinc.bat'
$runner = Join-Path $repositoryRoot 'node_modules\kotlin-compiler\bin\kotlin.bat'
if (-not (Test-Path -LiteralPath $compiler) -or -not (Test-Path -LiteralPath $runner)) {
    throw 'Run npm ci before rendering audio previews; the pinned Kotlin compiler is missing.'
}

if (-not $env:JAVA_HOME) {
    $androidStudioJbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (-not (Test-Path -LiteralPath $androidStudioJbr)) {
        throw 'JAVA_HOME is unset and the Android Studio JBR was not found.'
    }
    $env:JAVA_HOME = $androidStudioJbr
}

$classes = Join-Path $OutputDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(
    (Join-Path $repositoryRoot 'android\app\src\main\kotlin\com\drawlesschess\ui\CompletionEffectTimeline.kt'),
    (Join-Path $repositoryRoot 'android\app\src\main\kotlin\com\drawlesschess\ui\ProceduralGameAudio.kt'),
    (Join-Path $repositoryRoot 'scripts\audio-preview\AudioPreviewMain.kt')
)

& $compiler @sources -d $classes
if ($LASTEXITCODE -ne 0) { throw "Audio preview compilation failed with exit code $LASTEXITCODE." }
& $runner -classpath $classes com.drawlesschess.ui.AudioPreviewMainKt $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw "Audio preview rendering failed with exit code $LASTEXITCODE." }
