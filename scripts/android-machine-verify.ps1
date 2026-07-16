#requires -Version 7.0
<#
.SYNOPSIS
Runs the fail-closed Android build, package, and native-runtime gate on Windows.

.DESCRIPTION
Uses PowerShell 7, gradlew.bat, Git, .NET ZIP APIs, and the pinned Windows NDK tools.
It never accepts SDK licenses, installs packages, publishes artifacts, or retries a failed
native test. Full runtime coverage requires separate x86_64-emulator and ARM64-device runs.

.PARAMETER Sdk
Android SDK root. Environment variables and the normal Android Studio SDK location are used
when this parameter is omitted.

.PARAMETER JavaHome
Complete build JDK 17 or 21 home. JAVA_HOME, PATH, and Android Studio's bundled JBR are
probed otherwise. Project Java/Kotlin source and bytecode compatibility remain locked to 17.

.PARAMETER PreflightOnly
Verifies the host, toolchain, locked source, and wrapper without building or selecting a device.
#>
[CmdletBinding()]
param(
    [string]$Sdk,
    [string]$JavaHome,
    [string]$Serial,
    [ValidateSet('arm64-v8a', 'x86_64')]
    [string]$RequireAbi,
    [string]$Output,
    [ValidateRange(1, 8)]
    [int]$Workers = 2,
    [switch]$AllowPhysicalDevice,
    [switch]$PreflightOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$ProjectJavaCompatibility = 17
$SupportedJavaMajors = @(17, 21)
$ExpectedGradleVersion = '9.4.1'
$ExpectedAgpVersion = '9.2.1'
$ExpectedComposePluginVersion = '2.3.10'
$ExpectedPlatform = 36
$ExpectedBuildTools = '36.0.0'
$ExpectedMinApi = 26
$InstrumentationClass = 'com.drawlesschess.engine.AndroidFairyEngineInstrumentedTest'
$InstrumentationTimeoutSeconds = 240
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)

$ScriptDirectory = $PSScriptRoot
$RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $ScriptDirectory '..'))
$AndroidRoot = Join-Path $RepositoryRoot 'android'
$NativeLockPath = Join-Path $RepositoryRoot 'engine/native/upstream.properties'
$EvidenceBase = Join-Path $RepositoryRoot 'build/android-machine-verification'

$script:State = [ordered]@{
    StartedAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    Status = 'failed'
    FailedPhase = 'preflight'
    ExitCode = 1
    Mode = $(if ($PreflightOnly) { 'preflight-only' } else { 'full' })
    OutputPath = $null
    LockPath = $null
    LockStream = $null
    TempRoot = $null
    ActiveLog = $null
    Native = @{}
    ProjectGitCommit = ''
    ProjectGitDirty = ''
    JavaVersion = ''
    JavacVersion = ''
    JavaMajor = $null
    JavaVendor = ''
    JavaRuntimeName = ''
    JavaVmName = ''
    JavaReportedHome = ''
    JavaHome = ''
    JavaPath = ''
    JavacPath = ''
    GradleVersion = ''
    GradleLauncherJvm = ''
    GradleLauncherJavaVersion = ''
    GradleLauncherJavaMajor = $null
    GradleDaemonJvm = ''
    GradleDaemonJavaHome = ''
    GradleJavaHomeForced = $false
    GitVersion = ''
    GitPath = ''
    AdbVersion = ''
    SdkRoot = ''
    NdkRoot = ''
    CmakePackageRevision = ''
    CmakePackagePath = ''
    CmakeExecutableVersion = ''
    ReadElf = ''
    Strings = ''
    ZipVerifierType = $null
    Adb = ''
    DeviceSerial = ''
    DeviceSerialHash = ''
    DeviceApi = ''
    DeviceAbiList = ''
    SelectedAbi = ''
    DeviceModel = ''
    DeviceManufacturer = ''
    DeviceBuild = ''
    DeviceSecurityPatch = ''
    DeviceKind = ''
    RuntimeVerifiedAbi = ''
    AppDebugApk = ''
    AppReleaseApk = ''
    EngineTestApk = ''
    EngineDebugAar = ''
    EngineReleaseAar = ''
    VerifiedArtifactSetSha = ''
}

function Write-PhaseMessage {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host $Message
    if ($script:State.ActiveLog) {
        [System.IO.File]::AppendAllText($script:State.ActiveLog, "$Message`r`n", $Utf8NoBom)
    }
}

function Fail-Gate {
    param([Parameter(Mandatory)][string]$Message)
    throw [System.InvalidOperationException]::new($Message)
}

function Get-Sha256File {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Sha256Bytes {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function Get-Sha256Text {
    param([AllowEmptyString()][string]$Text)
    return Get-Sha256Bytes -Bytes $Utf8NoBom.GetBytes($Text)
}

function Read-PropertyFile {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Fail-Gate "missing property file: $Path"
    }
    $result = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ($line -match '^([^#!][^=]*)=(.*)$') {
            $key = $Matches[1].Trim()
            if ($result.ContainsKey($key)) {
                Fail-Gate "duplicate property '$key' in $Path"
            }
            $result[$key] = $Matches[2].TrimEnd("`r")
        }
    }
    return $result
}

function Get-NativeProperty {
    param([Parameter(Mandatory)][string]$Name)
    if (-not $script:State.Native.ContainsKey($Name)) {
        Fail-Gate "native lock is missing property: $Name"
    }
    return [string]$script:State.Native[$Name]
}

function Require-File {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Fail-Gate "missing required file: $Path"
    }
}

function Require-Text {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Text
    )
    $content = [System.IO.File]::ReadAllText($Path)
    if (-not $content.Contains($Text, [StringComparison]::Ordinal)) {
        Fail-Gate "$Path does not contain required text: $Text"
    }
}

function Reject-Text {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Text
    )
    $content = [System.IO.File]::ReadAllText($Path)
    if ($content.Contains($Text, [StringComparison]::Ordinal)) {
        Fail-Gate "$Path contains forbidden text: $Text"
    }
}

function Require-TextCount {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][int]$Expected
    )
    $content = [System.IO.File]::ReadAllText($Path)
    $count = 0
    $offset = 0
    while ($true) {
        $found = $content.IndexOf($Text, $offset, [StringComparison]::Ordinal)
        if ($found -lt 0) { break }
        $count++
        $offset = $found + $Text.Length
    }
    if ($count -ne $Expected) {
        Fail-Gate "$Path must contain '$Text' exactly $Expected time(s); found $count"
    }
}

function Reject-Regex {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Pattern
    )
    if ([regex]::IsMatch([System.IO.File]::ReadAllText($Path), $Pattern)) {
        Fail-Gate "$Path contains forbidden pattern: $Pattern"
    }
}

function Require-RegexValue {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Pattern,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Label
    )
    $matches = [regex]::Matches(
        [System.IO.File]::ReadAllText($Path),
        $Pattern,
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if ($matches.Count -ne 1 -or $matches[0].Groups[1].Value -ne $Expected) {
        $actual = if ($matches.Count -eq 0) { 'none' } else {
            (($matches | ForEach-Object { $_.Groups[1].Value }) -join ', ')
        }
        Fail-Gate "$Path must assign $Label to $Expected exactly once; found: $actual"
    }
}

function Invoke-ProcessCapture {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = $RepositoryRoot,
        [hashtable]$Environment = @{},
        [int]$TimeoutSeconds = 0,
        [string]$LogPath,
        [switch]$Echo
    )
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    foreach ($argument in $Arguments) {
        [void]$startInfo.ArgumentList.Add([string]$argument)
    }
    foreach ($entry in $Environment.GetEnumerator()) {
        $startInfo.Environment[[string]$entry.Key] = [string]$entry.Value
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            Fail-Gate "could not start process: $FilePath"
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $timedOut = $false
        if ($TimeoutSeconds -gt 0) {
            if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
                $timedOut = $true
                try { $process.Kill($true) } catch { }
            }
        }
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $combined = ($stdout + $stderr)
        if ($LogPath) {
            [System.IO.File]::WriteAllText($LogPath, $combined, $Utf8NoBom)
        }
        if ($Echo -and $combined.Length -gt 0) {
            Write-Host $combined -NoNewline
        }
        return [pscustomobject]@{
            ExitCode = $(if ($timedOut) { -1 } else { $process.ExitCode })
            TimedOut = $timedOut
            Stdout = $stdout
            Stderr = $stderr
            Combined = $combined
        }
    } finally {
        $process.Dispose()
    }
}

function Invoke-CheckedProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = $RepositoryRoot,
        [hashtable]$Environment = @{},
        [int]$TimeoutSeconds = 0,
        [string]$LogPath,
        [switch]$Echo,
        [string]$FailureMessage = 'external command failed'
    )
    $captureArguments = @{
        FilePath = $FilePath
        Arguments = $Arguments
        WorkingDirectory = $WorkingDirectory
        Environment = $Environment
        TimeoutSeconds = $TimeoutSeconds
        Echo = $Echo
    }
    if ($LogPath) { $captureArguments.LogPath = $LogPath }
    $result = Invoke-ProcessCapture @captureArguments
    if ($result.ExitCode -ne 0) {
        $suffix = if ($result.TimedOut) { ' (timed out)' } else { " (exit $($result.ExitCode))" }
        Fail-Gate ($FailureMessage + $suffix)
    }
    return $result
}

function Assert-CmdArgumentSafe {
    param([Parameter(Mandatory)][string]$Value)
    if ($Value.IndexOfAny([char[]]@(
        '"', '%', '&', '|', '<', '>', '^', '(', ')', "`r", "`n", [char]0
    )) -ge 0) {
        Fail-Gate 'the repository path or a Gradle argument contains a character unsafe for cmd.exe'
    }
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [string]$LogPath,
        [int]$TimeoutSeconds = 0,
        [switch]$Echo
    )
    $wrapper = Join-Path $AndroidRoot 'gradlew.bat'
    Require-File $wrapper
    if (-not $script:State.JavaHome) { Fail-Gate 'build JDK must be selected before Gradle starts' }
    # Keep the /C payload as separate ArgumentList entries. Combining pre-quoted tokens
    # into one entry makes .NET serialize the embedded quotes as literal \" for cmd.exe.
    $cmdArguments = [System.Collections.Generic.List[string]]::new()
    foreach ($cmdOption in @('/D', '/V:OFF', '/S', '/C', 'call')) {
        [void]$cmdArguments.Add($cmdOption)
    }
    $gradleJavaHomeArgument = "-Dorg.gradle.java.home=$($script:State.JavaHome)"
    foreach ($cmdArgument in @($wrapper, $gradleJavaHomeArgument) + $Arguments) {
        Assert-CmdArgumentSafe $cmdArgument
        [void]$cmdArguments.Add([string]$cmdArgument)
    }
    $script:State.GradleJavaHomeForced = $true
    $commandProcessor = if ($env:ComSpec) { $env:ComSpec } else { 'cmd.exe' }
    $environment = @{
        ANDROID_SDK_ROOT = $script:State.SdkRoot
        ANDROID_HOME = $script:State.SdkRoot
        JAVA_HOME = $script:State.JavaHome
        GIT_CONFIG_COUNT = '1'
        GIT_CONFIG_KEY_0 = 'core.filemode'
        GIT_CONFIG_VALUE_0 = 'false'
        PATH = "$(Join-Path $script:State.JavaHome 'bin');$env:PATH"
    }
    if ($script:State.DeviceSerial) {
        $environment.ANDROID_SERIAL = $script:State.DeviceSerial
    }
    return Invoke-ProcessCapture -FilePath $commandProcessor `
        -Arguments ([string[]]$cmdArguments) `
        -WorkingDirectory $AndroidRoot -Environment $environment `
        -TimeoutSeconds $TimeoutSeconds -LogPath $LogPath -Echo:$Echo
}

function Test-AndroidStructure {
    $rootBuild = Join-Path $AndroidRoot 'build.gradle.kts'
    $settings = Join-Path $AndroidRoot 'settings.gradle.kts'
    $gradleProperties = Join-Path $AndroidRoot 'gradle.properties'
    $appBuild = Join-Path $AndroidRoot 'app/build.gradle.kts'
    $coreBuild = Join-Path $AndroidRoot 'core/build.gradle.kts'
    $engineBuild = Join-Path $AndroidRoot 'engine/build.gradle.kts'
    $wrapperPropertiesPath = Join-Path $AndroidRoot 'gradle/wrapper/gradle-wrapper.properties'
    $wrapperJar = Join-Path $AndroidRoot 'gradle/wrapper/gradle-wrapper.jar'
    $wrapperChecksum = Join-Path $AndroidRoot 'gradle/wrapper/gradle-wrapper.jar.sha256'
    $shellGate = Join-Path $ScriptDirectory 'android-machine-verify.sh'
    $powerShellGate = Join-Path $ScriptDirectory 'android-machine-verify.ps1'
    $apkGate = Join-Path $ScriptDirectory 'native-verify-apk.sh'
    $instrumentedTest = Join-Path $AndroidRoot 'engine/src/androidTest/kotlin/com/drawlesschess/engine/AndroidFairyEngineInstrumentedTest.kt'
    $packageJson = Join-Path $RepositoryRoot 'package.json'
    $gitAttributes = Join-Path $RepositoryRoot '.gitattributes'

    foreach ($required in @(
        $rootBuild, $settings, $gradleProperties, $appBuild, $coreBuild, $engineBuild,
        $NativeLockPath, (Join-Path $AndroidRoot 'gradlew'),
        (Join-Path $AndroidRoot 'gradlew.bat'), $wrapperPropertiesPath, $wrapperJar,
        $wrapperChecksum, $shellGate, $powerShellGate, $apkGate, $instrumentedTest,
        $packageJson, $gitAttributes
    )) { Require-File $required }

    if (($SupportedJavaMajors -join ',') -ne '17,21') {
        Fail-Gate 'Windows build-JDK policy must allow exactly Java 17 and 21'
    }

    if ((Get-Item -LiteralPath $wrapperJar).Length -le 0) {
        Fail-Gate 'Gradle wrapper JAR is empty'
    }
    Require-Text (Join-Path $AndroidRoot 'gradlew') 'gradle/wrapper/gradle-wrapper.jar'
    Require-Text (Join-Path $AndroidRoot 'gradlew.bat') 'gradle\wrapper\gradle-wrapper.jar'

    $expectedWrapperJarSha = '55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c'
    $actualWrapperJarSha = Get-Sha256File $wrapperJar
    if ($actualWrapperJarSha -ne $expectedWrapperJarSha) {
        Fail-Gate "Gradle wrapper JAR checksum drifted: $actualWrapperJarSha"
    }
    $checksumFields = ([System.IO.File]::ReadAllText($wrapperChecksum).Trim() -split '\s+')
    if ($checksumFields.Count -ne 2 -or
        $checksumFields[0] -ne $expectedWrapperJarSha -or
        $checksumFields[1] -ne 'gradle-wrapper.jar') {
        Fail-Gate 'Gradle wrapper JAR checksum sidecar is invalid'
    }

    $wrapperProperties = Read-PropertyFile $wrapperPropertiesPath
    $requiredWrapperProperties = [ordered]@{
        distributionBase = 'GRADLE_USER_HOME'
        distributionPath = 'wrapper/dists'
        distributionUrl = "https\://services.gradle.org/distributions/gradle-$ExpectedGradleVersion-bin.zip"
        distributionSha256Sum = '2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb'
        validateDistributionUrl = 'true'
        zipStoreBase = 'GRADLE_USER_HOME'
        zipStorePath = 'wrapper/dists'
    }
    foreach ($entry in $requiredWrapperProperties.GetEnumerator()) {
        if (-not $wrapperProperties.ContainsKey($entry.Key) -or
            $wrapperProperties[$entry.Key] -ne $entry.Value) {
            Fail-Gate "$wrapperPropertiesPath must set $($entry.Key)=$($entry.Value)"
        }
    }
    $networkTimeout = 0
    if (-not $wrapperProperties.ContainsKey('networkTimeout') -or
        -not [int]::TryParse($wrapperProperties.networkTimeout, [ref]$networkTimeout) -or
        $networkTimeout -lt 10000) {
        Fail-Gate 'Gradle wrapper networkTimeout must be at least 10000 milliseconds'
    }

    Require-TextCount $rootBuild "id(`"com.android.application`") version `"$ExpectedAgpVersion`" apply false" 1
    Require-TextCount $rootBuild "id(`"com.android.library`") version `"$ExpectedAgpVersion`" apply false" 1
    Require-TextCount $rootBuild "id(`"org.jetbrains.kotlin.plugin.compose`") version `"$ExpectedComposePluginVersion`" apply false" 1
    Reject-Regex $rootBuild 'buildscript\s*\{'
    Reject-Text $rootBuild 'org.jetbrains.kotlin:kotlin-gradle-plugin'
    Reject-Text $gradleProperties 'android.builtInKotlin=false'
    Reject-Text $gradleProperties 'android.newDsl=false'
    Require-Text $gradleProperties 'android.useAndroidX=true'

    foreach ($moduleBuild in @($appBuild, $coreBuild, $engineBuild)) {
        Reject-Text $moduleBuild 'org.jetbrains.kotlin.android'
        Reject-Text $moduleBuild 'kotlin-android'
        Reject-Text $moduleBuild 'kotlin("android")'
        Reject-Text $moduleBuild 'compileSdkPreview'
        Reject-Text $moduleBuild 'targetSdkPreview'
        Require-RegexValue $moduleBuild '^\s*compileSdk\s*=\s*(\d+)\s*$' "$ExpectedPlatform" 'compileSdk'
        Require-RegexValue $moduleBuild '^\s*buildToolsVersion\s*=\s*"([^"]+)"\s*$' $ExpectedBuildTools 'buildToolsVersion'
        Require-Text $moduleBuild 'sourceCompatibility = JavaVersion.VERSION_17'
        Require-Text $moduleBuild 'targetCompatibility = JavaVersion.VERSION_17'
    }
    Require-RegexValue $appBuild '^\s*minSdk\s*=\s*(\d+)\s*$' "$ExpectedMinApi" 'minSdk'
    Require-RegexValue $appBuild '^\s*targetSdk\s*=\s*(\d+)\s*$' "$ExpectedPlatform" 'targetSdk'
    Require-Text $appBuild 'abiFilters += listOf("arm64-v8a", "x86_64")'
    Require-Text $appBuild 'ndkVersion = nativePin("androidNdkVersion")'
    Require-RegexValue $coreBuild '^\s*minSdk\s*=\s*(\d+)\s*$' "$ExpectedMinApi" 'minSdk'
    Require-Text $engineBuild 'minSdk = nativePin("androidMinSdk").toInt()'
    if ((Get-NativeProperty 'androidMinSdk') -ne "$ExpectedMinApi") {
        Fail-Gate "native minimum SDK drifted from $ExpectedMinApi"
    }
    if ((Get-NativeProperty 'androidNdkVersion') -ne '29.0.14206865') {
        Fail-Gate 'native NDK pin drifted from 29.0.14206865'
    }
    if ((Get-NativeProperty 'cmakeVersion') -ne '3.22.1') {
        Fail-Gate 'native CMake pin drifted from 3.22.1'
    }
    if ((Get-NativeProperty 'cmakeExecutableVersion') -cne '3.22.1-g37088a8-dirty') {
        Fail-Gate 'native CMake executable pin drifted from 3.22.1-g37088a8-dirty'
    }
    Require-Text $engineBuild 'ndkVersion = nativePin("androidNdkVersion")'
    Require-Text $engineBuild 'version = nativePin("cmakeVersion")'
    Require-Text $engineBuild 'abstract class GenerateFairyLegalAssetsTask : Sync()'
    Require-Text $engineBuild 'abstract val outputDirectory: DirectoryProperty'
    Require-Text $engineBuild 'outputDirectory.set(legalAssetsDirectory)'
    Require-Text $engineBuild 'androidComponents {'
    Require-Text $engineBuild 'assets.addGeneratedSourceDirectory('
    Require-Text $engineBuild 'GenerateFairyLegalAssetsTask::outputDirectory'
    Require-Text $engineBuild 'third_party/android-runtime'
    Require-Text $engineBuild 'release/reports/release-sbom.cdx.json'
    Require-Text $engineBuild 'generated/release-identity/SOURCE-COMMIT'
    Reject-Text $engineBuild 'sourceSets.getByName('
    Reject-Text $engineBuild 'sourceSets.named('
    Reject-Text $engineBuild 'assets.srcDir(legalAssetsDirectory)'
    Reject-Text $engineBuild 'tasks.named("preBuild")'
    Reject-Text $gradleProperties 'android.sourceset.disallowProvider=false'
    Require-Text $settings 'google()'
    Require-Text $settings 'mavenCentral()'
    Require-Text $settings 'include(":app", ":core", ":engine")'
    Require-Text $appBuild 'id("org.jetbrains.kotlin.plugin.compose")'
    Require-Text $appBuild 'compose = true'

    Require-Text $engineBuild 'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"'
    Require-Text $engineBuild 'androidTestImplementation("androidx.test:core:1.7.0")'
    Require-Text $engineBuild 'androidTestImplementation("androidx.test:runner:1.7.0")'
    Require-Text $engineBuild 'androidTestImplementation("androidx.test.ext:junit:1.3.0")'
    foreach ($requiredText in @(
        '@RunWith(AndroidJUnit4::class)', '@Test',
        'fun forcedRepetitionSearchClosesAndRestartsSequentially()',
        'val first = factory.create()', 'val second = factory.create()', 'h8g8'
    )) { Require-Text $instrumentedTest $requiredText }

    foreach ($requiredText in @(
        "platforms;android-$ExpectedPlatform", "build-tools;$ExpectedBuildTools",
        ':engine:connectedDebugAndroidTest', ':engine:assembleRelease',
        ':app:assembleDebug', 'native-verify-aar.sh', 'native-verify-apk.sh',
        'engine-debug.aar', 'engine-release.aar'
    )) { Require-Text $shellGate $requiredText }
    Require-Text $apkGate 'libandroidx.graphics.path.so'
    Require-TextCount $powerShellGate 'libandroidx.graphics.path.so' 3
    foreach ($requiredText in @(
        ':engine:connectedDebugAndroidTest', ':engine:assembleRelease',
        ':app:assembleDebug', 'engine-debug.aar', 'engine-release.aar',
        'gradlew.bat', 'llvm-readelf', 'llvm-strings', 'SHA256SUMS'
    )) { Require-Text $powerShellGate $requiredText }
    Require-Text $packageJson '"test:android-structure": "bash scripts/android-validate-structure.sh"'
    Require-Text $packageJson '"test:android-machine": "bash scripts/android-machine-verify.sh"'
    Require-Text $packageJson '"test:android-machine:windows": "pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/android-machine-verify.ps1"'
    Require-Text $packageJson 'npm run test:android-structure'

    foreach ($attributeLine in @(
        '/engine/patches/** text eol=lf',
        '/engine/variants.ini text eol=lf',
        '/engine/native/*.properties text eol=lf',
        '/engine/native/*.txt text eol=lf',
        '/scripts/*.sh text eol=lf',
        '/scripts/*.ps1 text eol=lf',
        '/android/gradlew text eol=lf',
        '/android/gradlew.bat text eol=crlf'
    )) { Require-TextCount $gitAttributes $attributeLine 1 }
    Test-CheckoutLineEndings

    Write-PhaseMessage 'Android structure PASS (native Windows companion contract included).'
    Write-PhaseMessage "  AGP / Gradle / Compose plugin: $ExpectedAgpVersion / $ExpectedGradleVersion / $ExpectedComposePluginVersion"
    Write-PhaseMessage "  SDK / Build Tools / minimum API: $ExpectedPlatform / $ExpectedBuildTools / $ExpectedMinApi"
}

function Test-CheckoutLineEndings {
    $lfFiles = [System.Collections.Generic.List[string]]::new()
    foreach ($path in @(
        (Join-Path $RepositoryRoot 'engine/variants.ini'),
        (Join-Path $AndroidRoot 'gradlew')
    )) { [void]$lfFiles.Add($path) }
    foreach ($path in Get-ChildItem -LiteralPath (Join-Path $RepositoryRoot 'engine/patches') -File -Recurse) {
        [void]$lfFiles.Add($path.FullName)
    }
    foreach ($path in Get-ChildItem -LiteralPath (Join-Path $RepositoryRoot 'engine/native') -File) {
        if ($path.Extension -in @('.properties', '.txt')) { [void]$lfFiles.Add($path.FullName) }
    }
    foreach ($path in Get-ChildItem -LiteralPath $ScriptDirectory -File) {
        if ($path.Extension -in @('.sh', '.ps1')) { [void]$lfFiles.Add($path.FullName) }
    }
    foreach ($path in $lfFiles) {
        $bytes = [System.IO.File]::ReadAllBytes($path)
        if ([Array]::IndexOf($bytes, [byte]13) -ge 0) {
            Fail-Gate "LF-locked file contains a carriage return byte: $path"
        }
    }

    $batchPath = Join-Path $AndroidRoot 'gradlew.bat'
    $batchBytes = [System.IO.File]::ReadAllBytes($batchPath)
    for ($index = 0; $index -lt $batchBytes.Length; $index++) {
        if ($batchBytes[$index] -eq 10 -and ($index -eq 0 -or $batchBytes[$index - 1] -ne 13)) {
            Fail-Gate 'android/gradlew.bat contains a lone LF byte'
        }
        if ($batchBytes[$index] -eq 13 -and ($index + 1 -ge $batchBytes.Length -or $batchBytes[$index + 1] -ne 10)) {
            Fail-Gate 'android/gradlew.bat contains a lone CR byte'
        }
    }
}

function Get-OrderedPatchHash {
    param(
        [Parameter(Mandatory)][byte[]]$SeriesBytes,
        [Parameter(Mandatory)][scriptblock]$ReadPatchBytes
    )
    $incremental = [System.Security.Cryptography.IncrementalHash]::CreateHash(
        [System.Security.Cryptography.HashAlgorithmName]::SHA256
    )
    try {
        $incremental.AppendData($Utf8NoBom.GetBytes("series`0"))
        $incremental.AppendData($SeriesBytes)
        $entries = [System.Collections.Generic.List[string]]::new()
        $seriesText = $Utf8NoBom.GetString($SeriesBytes)
        foreach ($rawLine in ($seriesText -split "`n")) {
            $entry = $rawLine.TrimEnd("`r")
            if (-not $entry -or $entry.StartsWith('#', [StringComparison]::Ordinal)) { continue }
            if ([System.IO.Path]::IsPathRooted($entry) -or $entry.Contains(':') -or
                $entry -match '(^/)|(^|/)\.\.(/|$)' -or $entry.Contains('\')) {
                Fail-Gate "unsafe patch path in series: $entry"
            }
            [void]$entries.Add($entry)
            $incremental.AppendData($Utf8NoBom.GetBytes("`0patch`0$entry`0"))
            $patchBytes = & $ReadPatchBytes $entry
            if ($null -eq $patchBytes) { Fail-Gate "series references missing patch: $entry" }
            $incremental.AppendData([byte[]]$patchBytes)
        }
        $hash = ([BitConverter]::ToString($incremental.GetHashAndReset())).Replace('-', '').ToLowerInvariant()
        return [pscustomobject]@{ Hash = $hash; Entries = @($entries) }
    } finally {
        $incremental.Dispose()
    }
}

function Get-ZipNames {
    param([Parameter(Mandatory)][string]$ArchivePath)
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        return @($archive.Entries | ForEach-Object { $_.FullName })
    } finally {
        $archive.Dispose()
    }
}

function Test-ZipIntegrity {
    param(
        [Parameter(Mandatory)][string]$ArchivePath,
        [Parameter(Mandatory)][string]$Label
    )
    try {
        $verifier = Get-ZipVerifierType
        $verifier::Verify($ArchivePath)
    } catch {
        $detail = if ($_.Exception.InnerException) { $_.Exception.InnerException.Message } else { $_.Exception.Message }
        Fail-Gate "$Label is not an intact ZIP archive: $ArchivePath ($detail)"
    }
}

function Get-ZipVerifierType {
    if ($script:State.ZipVerifierType) { return $script:State.ZipVerifierType }
    $source = @'
using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;

namespace DrawlessMachineGate {
    public static class ZipIntegrity {
        private static readonly uint[] Table = CreateTable();

        private static uint[] CreateTable() {
            uint[] table = new uint[256];
            for (uint value = 0; value < table.Length; value++) {
                uint current = value;
                for (int bit = 0; bit < 8; bit++)
                    current = (current & 1) != 0 ? 0xedb88320U ^ (current >> 1) : current >> 1;
                table[value] = current;
            }
            return table;
        }

        private static uint Compute(Stream stream) {
            uint crc = 0xffffffffU;
            byte[] buffer = new byte[131072];
            int count;
            while ((count = stream.Read(buffer, 0, buffer.Length)) > 0) {
                for (int index = 0; index < count; index++)
                    crc = Table[(crc ^ buffer[index]) & 0xff] ^ (crc >> 8);
            }
            return ~crc;
        }

        private static ushort UInt16(byte[] bytes, int offset) {
            return (ushort)(bytes[offset] | (bytes[offset + 1] << 8));
        }

        private static uint UInt32(byte[] bytes, int offset) {
            return (uint)(bytes[offset] | (bytes[offset + 1] << 8) |
                (bytes[offset + 2] << 16) | (bytes[offset + 3] << 24));
        }

        private static List<uint> ReadCentralCrcs(string path) {
            using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read)) {
                if (stream.Length < 22) throw new InvalidDataException("ZIP is shorter than EOCD");
                int tailLength = (int)Math.Min(stream.Length, 65557);
                byte[] tail = new byte[tailLength];
                stream.Position = stream.Length - tailLength;
                if (stream.Read(tail, 0, tail.Length) != tail.Length)
                    throw new EndOfStreamException("could not read ZIP tail");
                int eocd = -1;
                for (int index = tail.Length - 22; index >= 0; index--) {
                    if (UInt32(tail, index) == 0x06054b50U &&
                        index + 22 + UInt16(tail, index + 20) == tail.Length) {
                        eocd = index;
                        break;
                    }
                }
                if (eocd < 0) throw new InvalidDataException("ZIP EOCD is missing");
                if (UInt16(tail, eocd + 4) != 0 || UInt16(tail, eocd + 6) != 0)
                    throw new InvalidDataException("multi-disk ZIP is unsupported");
                ushort diskEntries = UInt16(tail, eocd + 8);
                ushort totalEntries = UInt16(tail, eocd + 10);
                uint centralSize = UInt32(tail, eocd + 12);
                uint centralOffset = UInt32(tail, eocd + 16);
                if (diskEntries != totalEntries || totalEntries == 0xffff ||
                    centralSize == 0xffffffffU || centralOffset == 0xffffffffU)
                    throw new InvalidDataException("ZIP64 or inconsistent central directory is unsupported");
                if ((long)centralOffset + centralSize > stream.Length)
                    throw new InvalidDataException("central directory is out of bounds");

                stream.Position = centralOffset;
                BinaryReader reader = new BinaryReader(stream);
                List<uint> values = new List<uint>(totalEntries);
                for (int entry = 0; entry < totalEntries; entry++) {
                    if (reader.ReadUInt32() != 0x02014b50U)
                        throw new InvalidDataException("central directory entry signature is invalid");
                    reader.ReadBytes(12);
                    uint crc = reader.ReadUInt32();
                    reader.ReadBytes(8);
                    ushort nameLength = reader.ReadUInt16();
                    ushort extraLength = reader.ReadUInt16();
                    ushort commentLength = reader.ReadUInt16();
                    reader.ReadBytes(12);
                    if (reader.ReadBytes(nameLength + extraLength + commentLength).Length !=
                        nameLength + extraLength + commentLength)
                        throw new EndOfStreamException("central directory entry is truncated");
                    values.Add(crc);
                }
                if (stream.Position != (long)centralOffset + centralSize)
                    throw new InvalidDataException("central directory size does not match its entries");
                return values;
            }
        }

        public static void Verify(string path) {
            List<uint> expected = ReadCentralCrcs(path);
            using (ZipArchive archive = ZipFile.OpenRead(path)) {
                if (archive.Entries.Count != expected.Count)
                    throw new InvalidDataException("ZIP entry count differs from central directory");
                for (int index = 0; index < archive.Entries.Count; index++) {
                    using (Stream stream = archive.Entries[index].Open()) {
                        uint actual = Compute(stream);
                        if (actual != expected[index])
                            throw new InvalidDataException("CRC mismatch for " + archive.Entries[index].FullName);
                    }
                }
            }
        }
    }
}
'@
    $types = @(Add-Type -TypeDefinition $source -PassThru)
    $selected = @($types | Where-Object { $_.FullName -eq 'DrawlessMachineGate.ZipIntegrity' })
    if ($selected.Count -ne 1) { Fail-Gate 'could not initialize the native ZIP integrity verifier' }
    $script:State.ZipVerifierType = $selected[0]
    return $script:State.ZipVerifierType
}

function Get-ZipMemberBytes {
    param(
        [Parameter(Mandatory)][string]$ArchivePath,
        [Parameter(Mandatory)][string]$MemberPath,
        [string]$Label = 'archive'
    )
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $matches = @($archive.Entries | Where-Object {
            $_.FullName.Equals($MemberPath, [StringComparison]::Ordinal)
        })
        if ($matches.Count -ne 1) {
            Fail-Gate "$Label must contain exactly one $MemberPath (found $($matches.Count))"
        }
        $source = $matches[0].Open()
        $memory = [System.IO.MemoryStream]::new()
        try {
            $source.CopyTo($memory)
            return ,$memory.ToArray()
        } finally {
            $memory.Dispose()
            $source.Dispose()
        }
    } finally {
        $archive.Dispose()
    }
}

function Require-ZipPath {
    param(
        [Parameter(Mandatory)][string[]]$Names,
        [Parameter(Mandatory)][string]$MemberPath,
        [Parameter(Mandatory)][string]$Label
    )
    $count = @($Names | Where-Object { $_ -ceq $MemberPath }).Count
    if ($count -ne 1) {
        Fail-Gate "$Label must contain exactly one $MemberPath (found $count)"
    }
}

function Get-ArchiveAbis {
    param(
        [Parameter(Mandatory)][string[]]$Names,
        [Parameter(Mandatory)][string]$Prefix
    )
    $abis = foreach ($name in $Names) {
        $parts = $name -split '/'
        if ($parts.Count -ge 3 -and $parts[0] -ceq $Prefix -and $parts[1]) { $parts[1] }
    }
    return @($abis | Sort-Object -Unique)
}

function Assert-ExactAbis {
    param(
        [Parameter(Mandatory)][string[]]$Names,
        [Parameter(Mandatory)][string]$Prefix,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string[]]$Expected
    )
    $actual = @(Get-ArchiveAbis -Names $Names -Prefix $Prefix)
    $expectedSorted = @($Expected | Sort-Object -Unique)
    if (($actual -join "`n") -cne ($expectedSorted -join "`n")) {
        Fail-Gate "$Label ABI set mismatch; expected $($expectedSorted -join ', '), found $($actual -join ', ')"
    }
}

function Assert-OnlyNativeLibraries {
    param(
        [Parameter(Mandatory)][string[]]$Names,
        [Parameter(Mandatory)][string]$Prefix,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string[]]$Abis,
        [Parameter(Mandatory)][string[]]$LibraryNames
    )
    $expected = @(
        foreach ($abi in $Abis) {
            foreach ($libraryName in $LibraryNames) { "$Prefix/$abi/$libraryName" }
        }
    ) | Sort-Object
    $actual = @($Names | Where-Object {
        $_.StartsWith("$Prefix/", [StringComparison]::Ordinal) -and
        $_.EndsWith('.so', [StringComparison]::Ordinal)
    } | Sort-Object)
    if (($actual -join "`n") -cne ($expected -join "`n")) {
        Fail-Gate "$Label contains an unexpected native library; expected only $($expected -join ', ')"
    }
}

function Write-ZipMemberToFile {
    param(
        [Parameter(Mandatory)][string]$ArchivePath,
        [Parameter(Mandatory)][string]$MemberPath,
        [Parameter(Mandatory)][string]$Destination,
        [string]$Label = 'archive'
    )
    $bytes = Get-ZipMemberBytes -ArchivePath $ArchivePath -MemberPath $MemberPath -Label $Label
    if ($bytes.Length -le 0) { Fail-Gate "archive member is empty: $MemberPath in $ArchivePath" }
    [System.IO.File]::WriteAllBytes($Destination, $bytes)
}

function Assert-ByteIdentical {
    param(
        [Parameter(Mandatory)][byte[]]$Actual,
        [Parameter(Mandatory)][byte[]]$Expected,
        [Parameter(Mandatory)][string]$Message
    )
    if ($Actual.Length -ne $Expected.Length -or
        (Get-Sha256Bytes $Actual) -cne (Get-Sha256Bytes $Expected)) {
        Fail-Gate $Message
    }
}

function Get-NativeToolOutput {
    param(
        [Parameter(Mandatory)][string]$Tool,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$FailureMessage
    )
    $result = Invoke-CheckedProcess -FilePath $Tool -Arguments $Arguments -FailureMessage $FailureMessage
    return $result.Stdout
}

function Test-ElfAndIdentity {
    param(
        [Parameter(Mandatory)][string]$LibraryPath,
        [Parameter(Mandatory)][string]$Abi,
        [Parameter(Mandatory)][string]$Label,
        [switch]$RequireFullJniContract
    )
    $header = Get-NativeToolOutput -Tool $script:State.ReadElf -Arguments @('-h', $LibraryPath) -FailureMessage "could not inspect ELF header for $Label"
    if ($header -notmatch 'Class:\s+ELF64') { Fail-Gate "$Label is not ELF64" }
    if ($Abi -eq 'arm64-v8a') {
        if ($header -notmatch '(?im)^\s*Machine:.*(AArch64|ARM.*64)') { Fail-Gate "$Label is not an AArch64 ELF" }
    } elseif ($Abi -eq 'x86_64') {
        if ($header -notmatch '(?im)^\s*Machine:.*(X86-64|x86_64|AMD.*64|Advanced Micro Devices)') {
            Fail-Gate "$Label is not an x86-64 ELF"
        }
    } else {
        Fail-Gate "internal unsupported ABI: $Abi"
    }

    $stringOutput = Get-NativeToolOutput -Tool $script:State.Strings -Arguments @($LibraryPath) -FailureMessage "could not inspect compiled strings for $Label"
    $stringLines = @($stringOutput -split "`r?`n")
    $requiredIdentities = @(
        (Get-NativeProperty 'revision'), (Get-NativeProperty 'tree'),
        (Get-NativeProperty 'patchedTree'), (Get-NativeProperty 'patchSeriesSha256'),
        'Drawless Patch Version', 'com/drawlesschess/engine/FairyNativeBindings'
    )
    foreach ($identity in $requiredIdentities) {
        if (@($stringLines | Where-Object { $_ -ceq $identity }).Count -eq 0) {
            Fail-Gate "$Label is missing compiled identity: $identity"
        }
    }

    if ($RequireFullJniContract) {
        $symbols = Get-NativeToolOutput -Tool $script:State.ReadElf -Arguments @('--wide', '--dyn-syms', $LibraryPath) -FailureMessage "could not inspect dynamic symbols for $Label"
        $symbolLines = @($symbols -split "`r?`n")
        $requiredSymbols = @(
            'JNI_OnLoad', 'drawless_fairy_upstream_revision', 'drawless_fairy_upstream_tree',
            'drawless_fairy_patched_tree', 'drawless_fairy_patch_series_sha256',
            'drawless_fairy_patch_version', 'drawless_fairy_bridge_abi_version',
            'drawless_fairy_android_abi'
        )
        foreach ($symbol in $requiredSymbols) {
            if (@($symbolLines | Where-Object { ($_ -split '\s+') -ccontains $symbol }).Count -eq 0) {
                Fail-Gate "$Label is missing required native export $symbol"
            }
        }
        $jniDefinitions = @($symbolLines | Where-Object {
            $tokens = @($_.Trim() -split '\s+')
            $tokens -ccontains 'JNI_OnLoad' -and $tokens -ccontains 'GLOBAL' -and
            -not ($tokens -ccontains 'UND')
        })
        if ($jniDefinitions.Count -ne 1) { Fail-Gate "$Label must export exactly one defined global JNI_OnLoad" }
        $javaExports = @($symbolLines | Where-Object {
            $tokens = @($_.Trim() -split '\s+')
            @($tokens | Where-Object { $_.StartsWith('Java_', [StringComparison]::Ordinal) }).Count -gt 0
        })
        if ($javaExports.Count -gt 0) { Fail-Gate "$Label exports Java_* symbols instead of the locked RegisterNatives ABI" }
        foreach ($binding in @(
            'nativeCreate', 'nativeStart', 'nativeWrite', 'nativeRead', 'nativeReadError',
            'nativeClose', '(Ljava/lang/String;)J', '(J[BII)I', '(J)V'
        )) {
            if (@($stringLines | Where-Object { $_ -ceq $binding }).Count -eq 0) {
                Fail-Gate "$Label is missing registered JNI binding evidence: $binding"
            }
        }
    }
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string]$Path
    )
    $parent = Split-Path -Parent $Path
    if ($parent) { [void][System.IO.Directory]::CreateDirectory($parent) }
    $json = $Value | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($Path, $json + "`r`n", $Utf8NoBom)
}

function Test-NativeAar {
    param(
        [Parameter(Mandatory)][string]$AarPath,
        [Parameter(Mandatory)][string]$ManifestPath,
        [Parameter(Mandatory)][string]$Label
    )
    Test-ZipIntegrity -ArchivePath $AarPath -Label $Label
    $names = @(Get-ZipNames $AarPath)
    $libraryName = "lib$(Get-NativeProperty 'nativeLibraryName').so"
    $supportedAbis = @('arm64-v8a', 'x86_64')
    Assert-ExactAbis -Names $names -Prefix 'jni' -Label $Label -Expected $supportedAbis
    Assert-OnlyNativeLibraries -Names $names -Prefix 'jni' -Label $Label -Abis $supportedAbis -LibraryName $libraryName

    foreach ($required in @('AndroidManifest.xml', 'classes.jar', 'proguard.txt')) {
        Require-ZipPath -Names $names -MemberPath $required -Label $Label
    }
    $legalPaths = @(
        'assets/legal/drawless-chess/LICENSE',
        'assets/legal/drawless-chess/NOTICE',
        'assets/legal/drawless-chess/THIRD_PARTY_NOTICES.md',
        'assets/third_party/android-runtime/APACHE-2.0.txt',
        'assets/third_party/android-runtime/release-sbom.cdx.json',
        'assets/release/SOURCE-COMMIT',
        'assets/third_party/fairy-stockfish/Copying.txt',
        'assets/third_party/fairy-stockfish/AUTHORS',
        'assets/third_party/fairy-stockfish/SOURCE_NOTICE.txt',
        'assets/third_party/fairy-stockfish/upstream.properties',
        'assets/third_party/fairy-stockfish/patches/series',
        'assets/engine/drawless-variants.ini'
    )
    foreach ($required in $legalPaths) { Require-ZipPath -Names $names -MemberPath $required -Label $Label }

    $classesBytes = Get-ZipMemberBytes $AarPath 'classes.jar' $Label
    if ($classesBytes.Length -eq 0) { Fail-Gate "$Label classes.jar is empty" }
    $classStream = [System.IO.MemoryStream]::new($classesBytes, $false)
    $classArchive = [System.IO.Compression.ZipArchive]::new(
        $classStream, [System.IO.Compression.ZipArchiveMode]::Read, $false
    )
    try {
        $classNames = @($classArchive.Entries | ForEach-Object { $_.FullName })
        foreach ($requiredClass in @(
            'com/drawlesschess/engine/BuildConfig.class',
            'com/drawlesschess/engine/FairyNativeBindings.class',
            'com/drawlesschess/engine/JniFairyEnginePort.class',
            'com/drawlesschess/engine/AndroidFairyEngineFactory.class',
            'com/drawlesschess/engine/AndroidFairyEngineSession.class',
            'com/drawlesschess/engine/AndroidUciTimeoutScheduler.class',
            'com/drawlesschess/engine/VariantConfigInstaller.class'
        )) {
            if (@($classNames | Where-Object { $_ -ceq $requiredClass }).Count -ne 1) {
                Fail-Gate "$Label classes.jar is missing JNI runtime class: $requiredClass"
            }
        }
    } finally {
        $classArchive.Dispose()
        $classStream.Dispose()
    }

    $proguardText = $Utf8NoBom.GetString((Get-ZipMemberBytes $AarPath 'proguard.txt' $Label))
    if (-not $proguardText.Contains('-keep class com.drawlesschess.engine.FairyNativeBindings {', [StringComparison]::Ordinal) -or
        -not $proguardText.Contains('public static native <methods>;', [StringComparison]::Ordinal)) {
        Fail-Gate "$Label consumer rules do not preserve the registered JNI binding contract"
    }
    $variantBytes = Get-ZipMemberBytes $AarPath 'assets/engine/drawless-variants.ini' $Label
    if ((Get-Sha256Bytes $variantBytes) -ne (Get-NativeProperty 'variantConfigSha256')) {
        Fail-Gate "$Label Drawless variant configuration does not match the native lock"
    }
    $packagedLock = Get-ZipMemberBytes $AarPath 'assets/third_party/fairy-stockfish/upstream.properties' $Label
    Assert-ByteIdentical $packagedLock ([System.IO.File]::ReadAllBytes($NativeLockPath)) "$Label native source lock differs from the repository lock"

    $patchRoot = 'assets/third_party/fairy-stockfish/patches'
    $packagedSeries = Get-ZipMemberBytes $AarPath "$patchRoot/series" $Label
    $packagedPatchResult = Get-OrderedPatchHash -SeriesBytes $packagedSeries -ReadPatchBytes {
        param($entry)
        return ,(Get-ZipMemberBytes $AarPath "$patchRoot/$entry" $Label)
    }
    if ($packagedPatchResult.Hash -ne (Get-NativeProperty 'patchSeriesSha256')) {
        Fail-Gate "$Label Drawless patch series differs from the native lock"
    }

    $artifacts = [System.Collections.Generic.List[object]]::new()
    foreach ($abi in $supportedAbis) {
        $member = "jni/$abi/$libraryName"
        Require-ZipPath -Names $names -MemberPath $member -Label $Label
        $extracted = Join-Path $script:State.TempRoot ("$([Guid]::NewGuid().ToString('N'))-$abi-$libraryName")
        Write-ZipMemberToFile $AarPath $member $extracted $Label
        Test-ElfAndIdentity -LibraryPath $extracted -Abi $abi -Label "$Label $member" -RequireFullJniContract
        [void]$artifacts.Add([ordered]@{
            abi = $abi
            libraryFileName = $libraryName
            uncompressedSizeBytes = (Get-Item -LiteralPath $extracted).Length
            sha256 = Get-Sha256File $extracted
        })
    }
    $manifest = [ordered]@{
        schemaVersion = 1
        verifier = 'powershell-native'
        engineId = "fairy-stockfish@$(Get-NativeProperty 'revision')"
        buildId = "aar-sha256:$(Get-Sha256File $AarPath)"
        drawlessPatchVersion = [int](Get-NativeProperty 'drawlessPatchVersion')
        nativeBridgeAbiVersion = [int](Get-NativeProperty 'nativeBridgeAbiVersion')
        minimumAndroidApi = [int](Get-NativeProperty 'androidMinSdk')
        artifacts = @($artifacts)
    }
    if (Test-Path -LiteralPath $ManifestPath) { Fail-Gate "manifest output already exists: $ManifestPath" }
    Write-JsonFile $manifest $ManifestPath
    Write-PhaseMessage "Native AAR PASS ($Label); manifest written to $ManifestPath"
    return $manifest
}

function Compare-ArchiveMember {
    param(
        [Parameter(Mandatory)][string]$ActualArchive,
        [Parameter(Mandatory)][string]$ActualMember,
        [Parameter(Mandatory)][string]$BaselineArchive,
        [Parameter(Mandatory)][string]$BaselineMember,
        [Parameter(Mandatory)][string]$Message
    )
    $actual = Get-ZipMemberBytes $ActualArchive $ActualMember 'actual archive'
    $baseline = Get-ZipMemberBytes $BaselineArchive $BaselineMember 'baseline archive'
    Assert-ByteIdentical $actual $baseline $Message
}

function Test-ApkNativeLibrary {
    param(
        [Parameter(Mandatory)][string]$Apk,
        [Parameter(Mandatory)][string]$ApkMember,
        [Parameter(Mandatory)][string]$Aar,
        [Parameter(Mandatory)][string]$AarMember,
        [Parameter(Mandatory)][string]$Abi,
        [Parameter(Mandatory)][string]$Label
    )
    Compare-ArchiveMember $Apk $ApkMember $Aar $AarMember "$Label JNI library is not byte-identical to its verified AAR"
    $extracted = Join-Path $script:State.TempRoot ("$([Guid]::NewGuid().ToString('N'))-$Abi-apk.so")
    Write-ZipMemberToFile $Apk $ApkMember $extracted $Label
    Test-ElfAndIdentity -LibraryPath $extracted -Abi $Abi -Label $Label
}

function Test-NativeApkSet {
    param(
        [Parameter(Mandatory)][string]$AppDebug,
        [Parameter(Mandatory)][string]$AppRelease,
        [Parameter(Mandatory)][string]$EngineTest,
        [Parameter(Mandatory)][string]$EngineDebugAar,
        [Parameter(Mandatory)][string]$EngineReleaseAar,
        [Parameter(Mandatory)][string]$TestAbi,
        [Parameter(Mandatory)][string]$ManifestPath
    )
    if ($TestAbi -notin @('arm64-v8a', 'x86_64')) { Fail-Gate "unsupported test ABI: $TestAbi" }
    $inputPaths = @($AppDebug, $AppRelease, $EngineTest, $EngineDebugAar, $EngineReleaseAar)
    $inputLabels = @('app debug APK', 'app release APK', 'engine test APK', 'engine debug AAR', 'engine release AAR')
    for ($index = 0; $index -lt $inputPaths.Count; $index++) {
        Require-File $inputPaths[$index]
        if ((Get-Item -LiteralPath $inputPaths[$index]).Length -le 0) {
            Fail-Gate "$($inputLabels[$index]) is empty: $($inputPaths[$index])"
        }
        Test-ZipIntegrity $inputPaths[$index] $inputLabels[$index]
    }
    $canonicalPaths = @($inputPaths | ForEach-Object { [System.IO.Path]::GetFullPath($_) })
    if (@($canonicalPaths | Sort-Object -Unique).Count -ne $canonicalPaths.Count) {
        Fail-Gate 'artifact inputs must be distinct'
    }

    $null = Test-NativeAar $EngineDebugAar (Join-Path $script:State.OutputPath 'manifests/native-debug.json') 'engine debug AAR'
    $null = Test-NativeAar $EngineReleaseAar (Join-Path $script:State.OutputPath 'manifests/native-release.json') 'engine release AAR'

    $lists = @{
        AppDebug = @(Get-ZipNames $AppDebug)
        AppRelease = @(Get-ZipNames $AppRelease)
        EngineTest = @(Get-ZipNames $EngineTest)
        EngineDebugAar = @(Get-ZipNames $EngineDebugAar)
        EngineReleaseAar = @(Get-ZipNames $EngineReleaseAar)
    }
    $libraryName = "lib$(Get-NativeProperty 'nativeLibraryName').so"
    $supportedAbis = @('arm64-v8a', 'x86_64')
    Assert-ExactAbis $lists.AppDebug 'lib' 'app debug APK' $supportedAbis
    Assert-ExactAbis $lists.AppRelease 'lib' 'app release APK' $supportedAbis
    Assert-ExactAbis $lists.EngineDebugAar 'jni' 'engine debug AAR' $supportedAbis
    Assert-ExactAbis $lists.EngineReleaseAar 'jni' 'engine release AAR' $supportedAbis

    $testAbis = @(Get-ArchiveAbis $lists.EngineTest 'lib')
    if ($testAbis.Count -eq 0) { Fail-Gate 'engine test APK contains no native ABI' }
    foreach ($abi in $testAbis) {
        if ($abi -notin $supportedAbis) { Fail-Gate "engine test APK contains unsupported ABI: $abi" }
    }
    if ($TestAbi -notin $testAbis) { Fail-Gate "engine test APK does not contain selected test ABI $TestAbi" }

    $appNativeLibraries = @($libraryName, 'libandroidx.graphics.path.so')
    Assert-OnlyNativeLibraries $lists.AppDebug 'lib' 'app debug APK' $supportedAbis $appNativeLibraries
    Assert-OnlyNativeLibraries $lists.AppRelease 'lib' 'app release APK' $supportedAbis $appNativeLibraries
    Assert-OnlyNativeLibraries $lists.EngineDebugAar 'jni' 'engine debug AAR' $supportedAbis $libraryName
    Assert-OnlyNativeLibraries $lists.EngineReleaseAar 'jni' 'engine release AAR' $supportedAbis $libraryName
    Assert-OnlyNativeLibraries $lists.EngineTest 'lib' 'engine test APK' $testAbis $libraryName

    foreach ($abi in $supportedAbis) {
        $apkMember = "lib/$abi/$libraryName"
        $aarMember = "jni/$abi/$libraryName"
        Require-ZipPath $lists.AppDebug $apkMember 'app debug APK'
        Require-ZipPath $lists.AppRelease $apkMember 'app release APK'
        Require-ZipPath $lists.EngineDebugAar $aarMember 'engine debug AAR'
        Require-ZipPath $lists.EngineReleaseAar $aarMember 'engine release AAR'
        Test-ApkNativeLibrary $AppDebug $apkMember $EngineDebugAar $aarMember $abi "app debug $abi"
        Test-ApkNativeLibrary $AppRelease $apkMember $EngineReleaseAar $aarMember $abi "app release $abi"
    }
    $testApkMember = "lib/$TestAbi/$libraryName"
    $testAarMember = "jni/$TestAbi/$libraryName"
    Require-ZipPath $lists.EngineTest $testApkMember 'engine test APK'
    Test-ApkNativeLibrary $EngineTest $testApkMember $EngineDebugAar $testAarMember $TestAbi "engine test $TestAbi"

    $assetPaths = [System.Collections.Generic.List[string]]::new()
    foreach ($asset in @(
        'assets/engine/drawless-variants.ini',
        'assets/legal/drawless-chess/LICENSE',
        'assets/legal/drawless-chess/NOTICE',
        'assets/legal/drawless-chess/THIRD_PARTY_NOTICES.md',
        'assets/third_party/android-runtime/APACHE-2.0.txt',
        'assets/third_party/android-runtime/release-sbom.cdx.json',
        'assets/release/SOURCE-COMMIT',
        'assets/third_party/fairy-stockfish/Copying.txt',
        'assets/third_party/fairy-stockfish/AUTHORS',
        'assets/third_party/fairy-stockfish/SOURCE_NOTICE.txt',
        'assets/third_party/fairy-stockfish/upstream.properties',
        'assets/third_party/fairy-stockfish/wasm-poc.properties',
        'assets/third_party/fairy-stockfish/patches/series',
        'assets/third_party/fairy-stockfish/patches/manifest.json',
        'assets/third_party/fairy-stockfish/patches/checksums.sha256',
        'assets/third_party/fairy-stockfish/patches/README.md'
    )) { [void]$assetPaths.Add($asset) }
    $seriesPath = 'assets/third_party/fairy-stockfish/patches/series'
    $seriesBytes = Get-ZipMemberBytes $EngineDebugAar $seriesPath 'engine debug AAR'
    $seriesText = $Utf8NoBom.GetString($seriesBytes)
    foreach ($rawLine in ($seriesText -split "`n")) {
        $entry = $rawLine.TrimEnd("`r")
        if (-not $entry -or $entry.StartsWith('#', [StringComparison]::Ordinal)) { continue }
        if ([System.IO.Path]::IsPathRooted($entry) -or $entry.Contains(':') -or
            $entry -match '(^/)|(^|/)\.\.(/|$)' -or $entry.Contains('\')) {
            Fail-Gate "unsafe patch path in packaged series: $entry"
        }
        [void]$assetPaths.Add("assets/third_party/fairy-stockfish/patches/$entry")
    }

    foreach ($assetPath in $assetPaths) {
        Require-ZipPath $lists.EngineDebugAar $assetPath 'engine debug AAR'
        Require-ZipPath $lists.EngineReleaseAar $assetPath 'engine release AAR'
        Require-ZipPath $lists.AppDebug $assetPath 'app debug APK'
        Require-ZipPath $lists.AppRelease $assetPath 'app release APK'
        Require-ZipPath $lists.EngineTest $assetPath 'engine test APK'
        Compare-ArchiveMember $EngineReleaseAar $assetPath $EngineDebugAar $assetPath "engine release AAR asset differs: $assetPath"
        Compare-ArchiveMember $AppDebug $assetPath $EngineDebugAar $assetPath "app debug APK asset differs: $assetPath"
        Compare-ArchiveMember $AppRelease $assetPath $EngineReleaseAar $assetPath "app release APK asset differs: $assetPath"
        Compare-ArchiveMember $EngineTest $assetPath $EngineDebugAar $assetPath "engine test APK asset differs: $assetPath"
    }
    $packagedSourceCommit = $Utf8NoBom.GetString((
        Get-ZipMemberBytes $AppRelease 'assets/release/SOURCE-COMMIT' 'app release APK'
    )).Trim()
    if ($packagedSourceCommit -cne $script:State.ProjectGitCommit) {
        Fail-Gate 'packaged release source commit differs from repository HEAD'
    }
    $variantHash = Get-Sha256Bytes (Get-ZipMemberBytes $AppDebug 'assets/engine/drawless-variants.ini' 'app debug APK')
    if ($variantHash -ne (Get-NativeProperty 'variantConfigSha256')) {
        Fail-Gate 'packaged Drawless variant configuration does not match the native lock'
    }

    $nativeLibraries = [System.Collections.Generic.List[object]]::new()
    foreach ($variant in @('debug', 'release')) {
        $aar = if ($variant -eq 'debug') { $EngineDebugAar } else { $EngineReleaseAar }
        foreach ($abi in $supportedAbis) {
            $member = "jni/$abi/$libraryName"
            $bytes = Get-ZipMemberBytes $aar $member "engine $variant AAR"
            [void]$nativeLibraries.Add([ordered]@{
                variant = $variant; abi = $abi; fileName = $libraryName
                sizeBytes = $bytes.LongLength; sha256 = Get-Sha256Bytes $bytes
            })
        }
    }
    $artifactDefinitions = @(
        [pscustomobject]@{ Kind = 'app-debug-apk'; Path = $AppDebug; Abis = $supportedAbis },
        [pscustomobject]@{ Kind = 'app-release-apk'; Path = $AppRelease; Abis = $supportedAbis },
        [pscustomobject]@{ Kind = 'engine-test-apk'; Path = $EngineTest; Abis = $testAbis },
        [pscustomobject]@{ Kind = 'engine-debug-aar'; Path = $EngineDebugAar; Abis = $supportedAbis },
        [pscustomobject]@{ Kind = 'engine-release-aar'; Path = $EngineReleaseAar; Abis = $supportedAbis }
    )
    $artifacts = [System.Collections.Generic.List[object]]::new()
    foreach ($definition in $artifactDefinitions) {
        [void]$artifacts.Add([ordered]@{
            kind = $definition.Kind
            file = $definition.Path
            sizeBytes = (Get-Item -LiteralPath $definition.Path).Length
            sha256 = Get-Sha256File $definition.Path
            abis = @($definition.Abis)
        })
    }
    $manifest = [ordered]@{
        schemaVersion = 1
        verifier = 'powershell-native'
        engineId = "fairy-stockfish@$(Get-NativeProperty 'revision')"
        drawlessPatchVersion = [int](Get-NativeProperty 'drawlessPatchVersion')
        nativeBridgeAbiVersion = [int](Get-NativeProperty 'nativeBridgeAbiVersion')
        variantConfigSha256 = $variantHash
        testAbi = $TestAbi
        artifacts = @($artifacts)
        nativeLibraries = @($nativeLibraries)
    }
    if (Test-Path -LiteralPath $ManifestPath) { Fail-Gate "manifest output already exists: $ManifestPath" }
    Write-JsonFile $manifest $ManifestPath
    Write-PhaseMessage "Native APK PASS; manifest written to $ManifestPath"
}

function Get-NormalizedPath {
    param([Parameter(Mandatory)][string]$Path)
    return [System.IO.Path]::TrimEndingDirectorySeparator(
        [System.IO.Path]::GetFullPath($Path)
    )
}

function Get-JavaProperty {
    param(
        [Parameter(Mandatory)][string]$Output,
        [Parameter(Mandatory)][string]$Name
    )
    $pattern = '(?m)^\s*' + [regex]::Escape($Name) + '\s*=\s*(.*?)\s*$'
    $match = [regex]::Match($Output, $pattern)
    if (-not $match.Success) { return '' }
    return $match.Groups[1].Value
}

function Get-JavaMajor {
    param([Parameter(Mandatory)][string]$Version)
    $match = [regex]::Match($Version, '^(\d+)(?:\.|$)')
    if (-not $match.Success) { return $null }
    return [int]$match.Groups[1].Value
}

function Test-JdkCandidate {
    param([Parameter(Mandatory)][string]$Candidate)
    try {
        $jdkHome = Get-NormalizedPath $Candidate
        $java = Join-Path $jdkHome 'bin/java.exe'
        $javac = Join-Path $jdkHome 'bin/javac.exe'
        if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
            -not (Test-Path -LiteralPath $javac -PathType Leaf)) { return $null }
        $javaResult = Invoke-ProcessCapture -FilePath $java -Arguments @('-XshowSettings:properties', '-version')
        $javacResult = Invoke-ProcessCapture -FilePath $javac -Arguments @('-version')
        if ($javaResult.ExitCode -ne 0 -or $javacResult.ExitCode -ne 0) { return $null }
        $javaMatch = [regex]::Match($javaResult.Combined, 'version\s+"([^"]+)"')
        $javacMatch = [regex]::Match($javacResult.Combined, '(?m)^\s*javac\s+([^\s]+)')
        if (-not $javaMatch.Success -or -not $javacMatch.Success) { return $null }
        $javaVersion = Get-JavaProperty $javaResult.Combined 'java.version'
        if (-not $javaVersion) { $javaVersion = $javaMatch.Groups[1].Value }
        $javacVersion = $javacMatch.Groups[1].Value
        if ($javaVersion -cne $javaMatch.Groups[1].Value -or
            $javaVersion -cne $javacVersion -or $javaVersion -notmatch '^\d+(?:\.\d+)*$') {
            return $null
        }
        $javaMajor = Get-JavaMajor $javaVersion
        $javacMajor = Get-JavaMajor $javacVersion
        if ($null -eq $javaMajor -or $null -eq $javacMajor -or $javaMajor -ne $javacMajor -or
            $javaMajor -notin $SupportedJavaMajors) { return $null }
        $vendor = Get-JavaProperty $javaResult.Combined 'java.vendor'
        $runtimeName = Get-JavaProperty $javaResult.Combined 'java.runtime.name'
        $vmName = Get-JavaProperty $javaResult.Combined 'java.vm.name'
        $reportedHome = Get-JavaProperty $javaResult.Combined 'java.home'
        if (-not $vendor -or -not $runtimeName -or -not $vmName -or -not $reportedHome) {
            return $null
        }
        try { $reportedHome = Get-NormalizedPath $reportedHome } catch { return $null }
        if (-not $reportedHome.Equals($jdkHome, [StringComparison]::OrdinalIgnoreCase)) {
            return $null
        }
        return [pscustomobject]@{
            Home = $jdkHome; Java = $java; Javac = $javac
            JavaVersion = $javaVersion
            JavacVersion = $javacVersion
            JavaMajor = $javaMajor
            JavaVendor = $vendor
            JavaRuntimeName = $runtimeName
            JavaVmName = $vmName
            JavaReportedHome = $reportedHome
        }
    } catch {
        return $null
    }
}

function Resolve-Jdk {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($JavaHome) {
        $explicit = Test-JdkCandidate $JavaHome
        if ($null -eq $explicit) {
            Fail-Gate "-JavaHome must name a complete build JDK with matching java.exe/javac.exe and major 17 or 21"
        }
        $selected = $explicit
    } else {
        if ($env:JAVA_HOME) { [void]$candidates.Add($env:JAVA_HOME) }
        foreach ($commandName in @('java.exe', 'javac.exe')) {
            $command = Get-Command $commandName -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($command) {
                $bin = Split-Path -Parent $command.Source
                [void]$candidates.Add((Split-Path -Parent $bin))
            }
        }
        if (${env:ProgramFiles}) {
            [void]$candidates.Add((Join-Path ${env:ProgramFiles} 'Android/Android Studio/jbr'))
        }
        $selected = $null
        $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($candidate in $candidates) {
            if (-not $candidate) { continue }
            try { $normalized = Get-NormalizedPath $candidate } catch { continue }
            if (-not $seen.Add($normalized)) { continue }
            $probe = Test-JdkCandidate $normalized
            if ($probe) { $selected = $probe; break }
        }
        if ($null -eq $selected) {
            Fail-Gate 'a complete build JDK 17 or 21 is required; use -JavaHome or set JAVA_HOME'
        }
    }
    $script:State.JavaHome = $selected.Home
    $script:State.JavaPath = $selected.Java
    $script:State.JavacPath = $selected.Javac
    $script:State.JavaVersion = $selected.JavaVersion
    $script:State.JavacVersion = $selected.JavacVersion
    $script:State.JavaMajor = $selected.JavaMajor
    $script:State.JavaVendor = $selected.JavaVendor
    $script:State.JavaRuntimeName = $selected.JavaRuntimeName
    $script:State.JavaVmName = $selected.JavaVmName
    $script:State.JavaReportedHome = $selected.JavaReportedHome
}

function Resolve-Git {
    $command = Get-Command 'git.exe' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) {
        $command = Get-Command 'git' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if (-not $command) { Fail-Gate 'Git for Windows is required' }
    $result = Invoke-CheckedProcess -FilePath $command.Source -Arguments @('--version') -FailureMessage 'Git could not start'
    $script:State.GitPath = $command.Source
    $script:State.GitVersion = $result.Stdout.Trim()
}

function Resolve-SdkRoot {
    if ($Sdk) {
        $selected = $Sdk
    } else {
        if ($env:ANDROID_SDK_ROOT -and $env:ANDROID_HOME) {
            $rootOne = Get-NormalizedPath $env:ANDROID_SDK_ROOT
            $rootTwo = Get-NormalizedPath $env:ANDROID_HOME
            if (-not $rootOne.Equals($rootTwo, [StringComparison]::OrdinalIgnoreCase)) {
                Fail-Gate 'ANDROID_SDK_ROOT and ANDROID_HOME disagree; use -Sdk to choose explicitly'
            }
        }
        if ($env:ANDROID_SDK_ROOT) { $selected = $env:ANDROID_SDK_ROOT }
        elseif ($env:ANDROID_HOME) { $selected = $env:ANDROID_HOME }
        elseif ($env:LOCALAPPDATA) { $selected = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
        else { $selected = '' }
    }
    if (-not $selected -or -not (Test-Path -LiteralPath $selected -PathType Container)) {
        Fail-Gate "Android SDK not found. Required packages: platform-tools, platforms;android-$ExpectedPlatform, build-tools;$ExpectedBuildTools, ndk;$(Get-NativeProperty 'androidNdkVersion'), cmake;$(Get-NativeProperty 'cmakeVersion')"
    }
    $script:State.SdkRoot = Get-NormalizedPath $selected
}

function Test-AndroidToolchain {
    $sdkRoot = $script:State.SdkRoot
    $ndkVersion = Get-NativeProperty 'androidNdkVersion'
    $cmakeVersion = Get-NativeProperty 'cmakeVersion'
    $expectedCmakeExecutableVersion = Get-NativeProperty 'cmakeExecutableVersion'
    $adb = Join-Path $sdkRoot 'platform-tools/adb.exe'
    $androidJar = Join-Path $sdkRoot "platforms/android-$ExpectedPlatform/android.jar"
    $aapt2 = Join-Path $sdkRoot "build-tools/$ExpectedBuildTools/aapt2.exe"
    $buildToolsProperties = Join-Path $sdkRoot "build-tools/$ExpectedBuildTools/source.properties"
    $ndkRoot = Join-Path $sdkRoot "ndk/$ndkVersion"
    $ndkProperties = Join-Path $ndkRoot 'source.properties'
    $cmakeRoot = Join-Path $sdkRoot "cmake/$cmakeVersion"
    $cmakeProperties = Join-Path $cmakeRoot 'source.properties'
    $cmake = Join-Path $cmakeRoot 'bin/cmake.exe'
    foreach ($required in @(
        $adb, $androidJar, $aapt2, $buildToolsProperties, $ndkProperties,
        $cmakeProperties, $cmake
    )) {
        Require-File $required
    }
    if ((Get-Item -LiteralPath $androidJar).Length -le 0) { Fail-Gate 'the Android platform android.jar is empty' }
    $buildToolsPropertiesTable = Read-PropertyFile $buildToolsProperties
    $ndkPropertiesTable = Read-PropertyFile $ndkProperties
    $cmakePropertiesTable = Read-PropertyFile $cmakeProperties
    if (-not $buildToolsPropertiesTable.ContainsKey('Pkg.Revision') -or
        $buildToolsPropertiesTable['Pkg.Revision'].Replace(' ', '') -ne $ExpectedBuildTools) {
        Fail-Gate 'Build Tools package identity mismatch'
    }
    if (-not $ndkPropertiesTable.ContainsKey('Pkg.Revision') -or
        $ndkPropertiesTable['Pkg.Revision'].Replace(' ', '') -ne $ndkVersion) {
        Fail-Gate 'NDK package identity mismatch'
    }
    $cmakePackageRevision = if ($cmakePropertiesTable.ContainsKey('Pkg.Revision')) {
        ([string]$cmakePropertiesTable['Pkg.Revision']).Trim()
    } else { '' }
    $cmakePackagePath = if ($cmakePropertiesTable.ContainsKey('Pkg.Path')) {
        ([string]$cmakePropertiesTable['Pkg.Path']).Trim()
    } else { '' }
    $script:State.CmakePackageRevision = $cmakePackageRevision
    $script:State.CmakePackagePath = $cmakePackagePath
    if ($cmakePackageRevision -cne $cmakeVersion) {
        Fail-Gate 'CMake package revision mismatch'
    }
    if ($cmakePackagePath -cne "cmake;$cmakeVersion") {
        Fail-Gate 'CMake package path mismatch'
    }
    $cmakeResult = Invoke-CheckedProcess -FilePath $cmake -Arguments @('--version') -FailureMessage 'pinned CMake could not start'
    $cmakeMatches = [regex]::Matches(
        $cmakeResult.Stdout, '(?m)^cmake version[ \t]+([^ \t\r\n]+)[ \t]*\r?$'
    )
    $cmakeExecutableVersion = if ($cmakeMatches.Count -eq 1) {
        $cmakeMatches[0].Groups[1].Value
    } else { '' }
    $script:State.CmakeExecutableVersion = $cmakeExecutableVersion
    if ($cmakeMatches.Count -ne 1 -or
        $cmakeExecutableVersion -cne $expectedCmakeExecutableVersion) {
        Fail-Gate 'CMake executable version mismatch'
    }

    $prebuiltRoot = Join-Path $ndkRoot 'toolchains/llvm/prebuilt'
    $hostDirectories = @(Get-ChildItem -LiteralPath $prebuiltRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq 'windows-x86_64' })
    if ($hostDirectories.Count -ne 1) { Fail-Gate 'pinned NDK windows-x86_64 LLVM toolchain is missing or ambiguous' }
    $readElf = Join-Path $hostDirectories[0].FullName 'bin/llvm-readelf.exe'
    $strings = Join-Path $hostDirectories[0].FullName 'bin/llvm-strings.exe'
    Require-File $readElf
    Require-File $strings

    $adbResult = Invoke-CheckedProcess -FilePath $adb -Arguments @('version') -FailureMessage 'adb could not start'
    $adbFirstLine = @($adbResult.Stdout -split "`r?`n")[0]
    $script:State.Adb = $adb
    $script:State.AdbVersion = $adbFirstLine
    $script:State.NdkRoot = $ndkRoot
    $script:State.ReadElf = $readElf
    $script:State.Strings = $strings
}

function Initialize-Evidence {
    [void][System.IO.Directory]::CreateDirectory($EvidenceBase)
    $lockPath = Join-Path $EvidenceBase '.machine-gate.lock'
    try {
        $lockStream = [System.IO.File]::Open(
            $lockPath, [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::None
        )
    } catch {
        Fail-Gate "another machine gate owns $lockPath"
    }
    $script:State.LockPath = $lockPath
    $script:State.LockStream = $lockStream

    if ($Output) {
        if ($Output.IndexOfAny([char[]]@("`r", "`n", [char]0)) -ge 0) {
            Fail-Gate '-Output contains an invalid control character'
        }
        $outputPath = if ([System.IO.Path]::IsPathRooted($Output)) {
            Get-NormalizedPath $Output
        } else {
            Get-NormalizedPath (Join-Path (Get-Location).Path $Output)
        }
    } else {
        $runId = "{0}-{1}" -f [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'), $PID
        $outputPath = Join-Path $EvidenceBase $runId
    }
    if (Test-Path -LiteralPath $outputPath) { Fail-Gate "evidence path already exists: $outputPath" }
    [void][System.IO.Directory]::CreateDirectory((Join-Path $outputPath 'logs'))
    [void][System.IO.Directory]::CreateDirectory((Join-Path $outputPath 'manifests'))
    [void][System.IO.Directory]::CreateDirectory((Join-Path $outputPath 'reports'))
    $script:State.OutputPath = $outputPath

    $temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("drawless-android-machine-$PID-$([Guid]::NewGuid().ToString('N'))")
    [void][System.IO.Directory]::CreateDirectory($temporary)
    $script:State.TempRoot = $temporary
}

function Get-NativeOrEmpty {
    param([Parameter(Mandatory)][string]$Name)
    if ($script:State.Native -and $script:State.Native.ContainsKey($Name)) {
        return [string]$script:State.Native[$Name]
    }
    return ''
}

function Get-MachineManifest {
    $artifactPaths = @(
        [pscustomobject]@{ Kind = 'app-debug-apk'; Path = $script:State.AppDebugApk },
        [pscustomobject]@{ Kind = 'app-release-apk'; Path = $script:State.AppReleaseApk },
        [pscustomobject]@{ Kind = 'engine-test-apk'; Path = $script:State.EngineTestApk },
        [pscustomobject]@{ Kind = 'engine-debug-aar'; Path = $script:State.EngineDebugAar },
        [pscustomobject]@{ Kind = 'engine-release-aar'; Path = $script:State.EngineReleaseAar }
    )
    $artifacts = [System.Collections.Generic.List[object]]::new()
    $allArtifactsExist = $true
    foreach ($definition in $artifactPaths) {
        if (-not $definition.Path -or -not (Test-Path -LiteralPath $definition.Path -PathType Leaf)) {
            $allArtifactsExist = $false
            break
        }
    }
    if ($allArtifactsExist) {
        foreach ($definition in $artifactPaths) {
            [void]$artifacts.Add([ordered]@{
                kind = $definition.Kind
                path = $definition.Path
                sizeBytes = (Get-Item -LiteralPath $definition.Path).Length
                sha256 = Get-Sha256File $definition.Path
            })
        }
    }
    $artifactAbis = [object[]]@()
    if ($allArtifactsExist) { $artifactAbis = [object[]]@('arm64-v8a', 'x86_64') }
    $runtimeVerifiedAbis = [object[]]@()
    if ($script:State.RuntimeVerifiedAbi) {
        $runtimeVerifiedAbis = [object[]]@($script:State.RuntimeVerifiedAbi)
    }
    $bridgeAbi = Get-NativeOrEmpty 'nativeBridgeAbiVersion'
    return [ordered]@{
        schemaVersion = 1
        result = $script:State.Status
        exitCode = $script:State.ExitCode
        mode = $script:State.Mode
        failedPhase = $script:State.FailedPhase
        startedAt = $script:State.StartedAt
        finishedAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        privateTestOnly = $true
        distributionAuthorized = $false
        verifier = [ordered]@{
            implementation = 'powershell-native'
            requiresUnixShell = $false
            checks = @(
                'locked Android and native source structure',
                'exact pinned source Git trees and ordered patch hash',
                '.NET full ZIP reads and duplicate exact-path checks',
                'AAR/APK ABI, JNI export, compiled identity, legal asset, and byte-parity checks',
                'single bounded native instrumentation test and XML assertion',
                'verified artifact immutability across instrumentation'
            )
        }
        source = [ordered]@{
            revision = Get-NativeOrEmpty 'revision'
            tree = Get-NativeOrEmpty 'tree'
            patchedTree = Get-NativeOrEmpty 'patchedTree'
            patchSeriesSha256 = Get-NativeOrEmpty 'patchSeriesSha256'
            variantConfigSha256 = Get-NativeOrEmpty 'variantConfigSha256'
            bridgeAbiVersion = $(if ($bridgeAbi -match '^\d+$') { [int]$bridgeAbi } else { $null })
        }
        project = [ordered]@{
            gitCommit = $script:State.ProjectGitCommit
            gitDirty = $script:State.ProjectGitDirty
        }
        toolchain = [ordered]@{
            java = $script:State.JavaVersion
            javac = $script:State.JavacVersion
            javaMajor = $script:State.JavaMajor
            supportedJavaMajors = [object[]]$SupportedJavaMajors
            javaVendor = $script:State.JavaVendor
            javaRuntimeName = $script:State.JavaRuntimeName
            javaVmName = $script:State.JavaVmName
            javaHome = $script:State.JavaHome
            javaReportedHome = $script:State.JavaReportedHome
            javaExecutable = $script:State.JavaPath
            javacExecutable = $script:State.JavacPath
            androidSourceCompatibility = $ProjectJavaCompatibility
            androidTargetCompatibility = $ProjectJavaCompatibility
            gradle = $script:State.GradleVersion
            gradleLauncherJvm = $script:State.GradleLauncherJvm
            gradleLauncherJavaVersion = $script:State.GradleLauncherJavaVersion
            gradleLauncherJavaMajor = $script:State.GradleLauncherJavaMajor
            gradleDaemonJvm = $script:State.GradleDaemonJvm
            gradleDaemonJavaHome = $script:State.GradleDaemonJavaHome
            gradleJavaHome = $script:State.JavaHome
            gradleJavaHomeForced = $script:State.GradleJavaHomeForced
            agp = $ExpectedAgpVersion
            composePlugin = $ExpectedComposePluginVersion
            compileSdk = $ExpectedPlatform
            buildTools = $ExpectedBuildTools
            ndk = Get-NativeOrEmpty 'androidNdkVersion'
            cmake = Get-NativeOrEmpty 'cmakeVersion'
            cmakePackageRevision = $script:State.CmakePackageRevision
            cmakePackagePath = $script:State.CmakePackagePath
            cmakeExecutableVersion = $script:State.CmakeExecutableVersion
            git = $script:State.GitVersion
            adb = $script:State.AdbVersion
            sdkRoot = $script:State.SdkRoot
        }
        device = [ordered]@{
            serialSha256 = $script:State.DeviceSerialHash
            kind = $script:State.DeviceKind
            manufacturer = $script:State.DeviceManufacturer
            model = $script:State.DeviceModel
            build = $script:State.DeviceBuild
            securityPatch = $script:State.DeviceSecurityPatch
            api = $script:State.DeviceApi
            abiList = $script:State.DeviceAbiList
            selectedAbi = $script:State.SelectedAbi
        }
        artifactAbis = $artifactAbis
        artifacts = @($artifacts)
        runtimeVerifiedAbis = $runtimeVerifiedAbis
    }
}

function Write-EvidenceChecksums {
    $checksumPath = Join-Path $script:State.OutputPath 'SHA256SUMS'
    $files = @(Get-ChildItem -LiteralPath $script:State.OutputPath -File -Recurse |
        Where-Object { $_.FullName -ne $checksumPath } |
        ForEach-Object { $_.FullName })
    [Array]::Sort($files, [StringComparer]::Ordinal)
    $lines = foreach ($file in $files) {
        $relative = [System.IO.Path]::GetRelativePath($script:State.OutputPath, $file).Replace('\', '/')
        "$(Get-Sha256File $file)  ./$relative"
    }
    $text = if ($lines.Count -gt 0) { ($lines -join "`n") + "`n" } else { '' }
    [System.IO.File]::WriteAllText($checksumPath, $text, $Utf8NoBom)
}

function Finalize-Evidence {
    try {
        if ($script:State.OutputPath -and (Test-Path -LiteralPath $script:State.OutputPath -PathType Container)) {
            Write-JsonFile (Get-MachineManifest) (Join-Path $script:State.OutputPath 'manifest.json')
            Write-EvidenceChecksums
        }
    } catch {
        $script:State.ExitCode = 1
        [Console]::Error.WriteLine("android-machine-verify: could not finalize evidence: $($_.Exception.Message)")
    } finally {
        if ($script:State.TempRoot -and (Test-Path -LiteralPath $script:State.TempRoot)) {
            Remove-Item -LiteralPath $script:State.TempRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
        if ($script:State.LockStream) {
            $script:State.LockStream.Dispose()
            $script:State.LockStream = $null
        }
        if ($script:State.LockPath -and (Test-Path -LiteralPath $script:State.LockPath)) {
            Remove-Item -LiteralPath $script:State.LockPath -Force -ErrorAction SilentlyContinue
        }
        if ($script:State.OutputPath) { Write-Host "Android machine evidence: $($script:State.OutputPath)" }
    }
}

function Test-ProjectGitState {
    $git = $script:State.GitPath
    $inside = Invoke-ProcessCapture -FilePath $git -Arguments @('-C', $RepositoryRoot, 'rev-parse', '--is-inside-work-tree')
    if ($inside.ExitCode -ne 0 -or $inside.Stdout.Trim() -ne 'true') { return }
    $commit = Invoke-CheckedProcess -FilePath $git -Arguments @('-C', $RepositoryRoot, 'rev-parse', 'HEAD') -FailureMessage 'could not read project Git commit'
    $status = Invoke-CheckedProcess -FilePath $git -Arguments @('-C', $RepositoryRoot, 'status', '--porcelain') -FailureMessage 'could not read project Git status'
    $script:State.ProjectGitCommit = $commit.Stdout.Trim()
    $script:State.ProjectGitDirty = $(if ($status.Stdout.Length -gt 0) { 'true' } else { 'false' })
}

function Set-GradleJvmIdentity {
    param([Parameter(Mandatory)][string]$GradleOutput)
    $launcherMatch = [regex]::Match($GradleOutput, '(?m)^Launcher JVM:\s+(.+?)\s*$')
    if (-not $launcherMatch.Success) { Fail-Gate 'could not parse the Gradle Launcher JVM identity' }
    $launcherJvm = $launcherMatch.Groups[1].Value.Trim()
    $launcherVersionMatch = [regex]::Match($launcherJvm, '^(\d+(?:\.\d+)*)\b')
    if (-not $launcherVersionMatch.Success) { Fail-Gate 'could not parse the Gradle Launcher JVM version' }
    $launcherVersion = $launcherVersionMatch.Groups[1].Value
    $launcherMajor = Get-JavaMajor $launcherVersion
    if ($launcherVersion -cne $script:State.JavaVersion) {
        Fail-Gate "Gradle Launcher JVM version $launcherVersion does not match selected build JDK version $($script:State.JavaVersion)"
    }
    if ($null -eq $launcherMajor -or $launcherMajor -ne $script:State.JavaMajor) {
        Fail-Gate "Gradle Launcher JVM major $launcherMajor does not match selected build JDK major $($script:State.JavaMajor)"
    }
    $daemonMatch = [regex]::Match($GradleOutput, '(?m)^Daemon JVM:\s+(.+?)\s*$')
    $daemonJvm = $(if ($daemonMatch.Success) {
        $daemonMatch.Groups[1].Value.Trim()
    } else { '' })
    $daemonJavaHome = ''
    if ($daemonJvm) {
        $daemonCriteria = [regex]::Match(
            $daemonJvm,
            '(?i)^Compatible with Java\s+(\d+)\b'
        )
        if ($daemonCriteria.Success) {
            $criteriaMajor = [int]$daemonCriteria.Groups[1].Value
            if ($criteriaMajor -ne $script:State.JavaMajor) {
                Fail-Gate "Gradle Daemon JVM criteria requires Java $criteriaMajor, selected build JDK major is $($script:State.JavaMajor)"
            }
            $daemonProperties = Join-Path $AndroidRoot 'gradle\gradle-daemon-jvm.properties'
            Require-RegexValue `
                $daemonProperties `
                '^toolchainVersion=(\d+)$' `
                "$criteriaMajor" `
                'Gradle daemon toolchain version'
        } else {
            $daemonHomeText = [regex]::Replace(
                $daemonJvm, '(?i)\s+\((?:from|no JDK\b).*?\)\s*$', ''
            )
            try { $daemonJavaHome = Get-NormalizedPath $daemonHomeText } catch {
                Fail-Gate "could not normalize the Gradle Daemon JVM home: $daemonJvm"
            }
            if (-not $daemonJavaHome.Equals(
                $script:State.JavaHome, [StringComparison]::OrdinalIgnoreCase
            )) {
                Fail-Gate "Gradle Daemon JVM does not use the selected build JDK home: $daemonJvm"
            }
        }
    }
    $script:State.GradleLauncherJvm = $launcherJvm
    $script:State.GradleLauncherJavaVersion = $launcherVersion
    $script:State.GradleLauncherJavaMajor = $launcherMajor
    $script:State.GradleDaemonJvm = $daemonJvm
    $script:State.GradleDaemonJavaHome = $daemonJavaHome
}

function Invoke-Preflight {
    if (-not [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::Windows
    )) { Fail-Gate 'this companion gate requires Windows and PowerShell 7'
    }
    $script:State.Native = Read-PropertyFile $NativeLockPath
    Resolve-Jdk
    Resolve-Git
    Resolve-SdkRoot
    Test-AndroidToolchain
    Test-ProjectGitState

    $script:State.ActiveLog = Join-Path $script:State.OutputPath 'logs/android-structure.log'
    Test-AndroidStructure
    $script:State.ActiveLog = Join-Path $script:State.OutputPath 'logs/native-source.log'
    Test-NativeStructure -RequireSource
    $script:State.ActiveLog = $null

    $gradleLog = Join-Path $script:State.OutputPath 'logs/gradle-version.log'
    $gradle = Invoke-Gradle -Arguments @('--version') -LogPath $gradleLog
    if ($gradle.ExitCode -ne 0) { Fail-Gate 'the pinned Gradle wrapper could not start' }
    $gradleMatch = [regex]::Match($gradle.Combined, '(?m)^Gradle\s+([^\s]+)\s*$')
    if (-not $gradleMatch.Success) { Fail-Gate 'could not parse the pinned Gradle wrapper version' }
    $script:State.GradleVersion = $gradleMatch.Groups[1].Value
    if ($script:State.GradleVersion -ne $ExpectedGradleVersion) {
        Fail-Gate "wrapper resolved Gradle $($script:State.GradleVersion), expected $ExpectedGradleVersion"
    }
    Set-GradleJvmIdentity $gradle.Combined
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [int]$TimeoutSeconds = 0
    )
    return Invoke-ProcessCapture -FilePath $script:State.Adb -Arguments $Arguments -TimeoutSeconds $TimeoutSeconds
}

function Get-DeviceProperty {
    param([Parameter(Mandatory)][string]$Name)
    $result = Invoke-Adb @('-s', $script:State.DeviceSerial, 'shell', 'getprop', $Name)
    if ($result.ExitCode -ne 0) { return '' }
    return $result.Stdout.Trim()
}

function Select-AndroidDevice {
    $devicesResult = Invoke-Adb @('devices')
    if ($devicesResult.ExitCode -ne 0) { Fail-Gate 'adb devices failed' }
    $readyDevices = [System.Collections.Generic.List[string]]::new()
    foreach ($line in ($devicesResult.Stdout -split "`r?`n")) {
        if ($line -match '^([^\s]+)\s+device(?:\s|$)') { [void]$readyDevices.Add($Matches[1]) }
    }
    if ($Serial) {
        $stateResult = Invoke-Adb @('-s', $Serial, 'get-state')
        if ($stateResult.ExitCode -ne 0 -or $stateResult.Stdout.Trim() -ne 'device') {
            Fail-Gate 'the device selected by -Serial is not ready'
        }
        if (-not $readyDevices.Contains($Serial)) {
            Fail-Gate 'the device selected by -Serial is not in the ready-device list'
        }
        $selectedSerial = $Serial
    } else {
        if ($readyDevices.Count -eq 0) { Fail-Gate 'no authorized adb device is connected' }
        if ($readyDevices.Count -ne 1) { Fail-Gate 'multiple adb devices are connected; choose one with -Serial' }
        $selectedSerial = $readyDevices[0]
    }
    $script:State.DeviceSerial = $selectedSerial
    $script:State.DeviceSerialHash = Get-Sha256Text $selectedSerial
    if ((Get-DeviceProperty 'sys.boot_completed') -ne '1') { Fail-Gate 'selected device has not completed boot' }

    $deviceApi = Get-DeviceProperty 'ro.build.version.sdk'
    $apiNumber = 0
    if (-not [int]::TryParse($deviceApi, [ref]$apiNumber) -or $apiNumber -lt $ExpectedMinApi) {
        Fail-Gate "device API must be at least $ExpectedMinApi"
    }
    $abiList = Get-DeviceProperty 'ro.product.cpu.abilist'
    if (-not $abiList) { $abiList = Get-DeviceProperty 'ro.product.cpu.abi' }
    $selectedAbi = ''
    foreach ($abi in ($abiList -split ',')) {
        if ($abi -in @('arm64-v8a', 'x86_64')) { $selectedAbi = $abi; break }
    }
    if (-not $selectedAbi) { Fail-Gate "device has no supported ABI: $abiList" }
    if ($RequireAbi -and $selectedAbi -ne $RequireAbi) {
        Fail-Gate "device selected ABI $selectedAbi, required $RequireAbi"
    }

    $script:State.DeviceApi = $deviceApi
    $script:State.DeviceAbiList = $abiList
    $script:State.SelectedAbi = $selectedAbi
    $script:State.DeviceModel = Get-DeviceProperty 'ro.product.model'
    $script:State.DeviceManufacturer = Get-DeviceProperty 'ro.product.manufacturer'
    $script:State.DeviceBuild = Get-DeviceProperty 'ro.build.id'
    $script:State.DeviceSecurityPatch = Get-DeviceProperty 'ro.build.version.security_patch'
    $isEmulator = (Get-DeviceProperty 'ro.kernel.qemu') -eq '1' -or
        $selectedSerial.StartsWith('emulator-', [StringComparison]::Ordinal)
    if ($isEmulator) {
        $script:State.DeviceKind = 'emulator'
    } else {
        $script:State.DeviceKind = 'physical'
        if (-not $AllowPhysicalDevice) { Fail-Gate 'physical-device execution requires -AllowPhysicalDevice' }
    }
}

function Get-SingleArtifact {
    param(
        [Parameter(Mandatory)][string]$Directory,
        [Parameter(Mandatory)][string]$Filter,
        [Parameter(Mandatory)][string]$Label
    )
    $matches = @(Get-ChildItem -LiteralPath $Directory -File -Recurse -Filter $Filter -ErrorAction SilentlyContinue)
    if ($matches.Count -ne 1) { Fail-Gate "expected exactly one $Label, found $($matches.Count)" }
    return $matches[0].FullName
}

function Get-ArtifactSetHash {
    $lines = foreach ($artifact in @(
        $script:State.AppDebugApk, $script:State.AppReleaseApk,
        $script:State.EngineTestApk, $script:State.EngineDebugAar,
        $script:State.EngineReleaseAar
    )) { "$(Get-Sha256File $artifact)  $artifact" }
    return Get-Sha256Text ($lines -join "`n")
}

function Invoke-AndroidBuild {
    $flags = @('--no-daemon', '--no-parallel', '--console=plain', '--stacktrace', "--max-workers=$Workers")
    $tasks = @(
        '-p', $AndroidRoot, 'clean', ':engine:assembleDebug', ':engine:assembleRelease',
        ':app:assembleDebug', ':app:assembleRelease', ':engine:assembleDebugAndroidTest'
    ) + $flags
    $result = Invoke-Gradle -Arguments $tasks -LogPath (Join-Path $script:State.OutputPath 'logs/build.log') -Echo
    if ($result.ExitCode -ne 0) { Fail-Gate 'Android build failed' }

    $script:State.EngineDebugAar = Get-SingleArtifact (Join-Path $AndroidRoot 'engine/build/outputs/aar') 'engine-debug.aar' 'engine debug AAR'
    $script:State.EngineReleaseAar = Get-SingleArtifact (Join-Path $AndroidRoot 'engine/build/outputs/aar') 'engine-release.aar' 'engine release AAR'
    $script:State.AppDebugApk = Get-SingleArtifact (Join-Path $AndroidRoot 'app/build/outputs/apk/debug') '*.apk' 'app debug APK'
    $script:State.AppReleaseApk = Get-SingleArtifact (Join-Path $AndroidRoot 'app/build/outputs/apk/release') '*.apk' 'app release APK'
    $script:State.EngineTestApk = Get-SingleArtifact (Join-Path $AndroidRoot 'engine/build/outputs/apk/androidTest/debug') '*.apk' 'engine test APK'
}

function Invoke-ArtifactVerification {
    $apkManifest = Join-Path $script:State.OutputPath 'manifests/native-apk.json'
    Test-NativeApkSet `
        $script:State.AppDebugApk $script:State.AppReleaseApk $script:State.EngineTestApk `
        $script:State.EngineDebugAar $script:State.EngineReleaseAar $script:State.SelectedAbi `
        $apkManifest
    $script:State.VerifiedArtifactSetSha = Get-ArtifactSetHash
}

function Capture-FatalLogcat {
    if (-not $script:State.DeviceSerial) { return }
    $result = Invoke-Adb @('-s', $script:State.DeviceSerial, 'logcat', '-d', '*:F')
    $path = Join-Path $script:State.OutputPath 'logs/logcat-fatal.log'
    [System.IO.File]::WriteAllText($path, $result.Combined, $Utf8NoBom)
}

function Invoke-NativeInstrumentation {
    $reportStarted = [DateTime]::UtcNow
    $flags = @('--no-daemon', '--no-parallel', '--console=plain', '--stacktrace', "--max-workers=$Workers")
    $arguments = @(
        '-p', $AndroidRoot, ':engine:connectedDebugAndroidTest'
    ) + $flags + @(
        "-Pandroid.testInstrumentationRunnerArguments.class=$InstrumentationClass",
        '-Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000'
    )
    $result = Invoke-Gradle -Arguments $arguments `
        -LogPath (Join-Path $script:State.OutputPath 'logs/instrumentation.log') `
        -TimeoutSeconds $InstrumentationTimeoutSeconds -Echo
    if ($result.ExitCode -ne 0) {
        Capture-FatalLogcat
        $detail = if ($result.TimedOut) { 'timed out' } else { "exit $($result.ExitCode)" }
        Fail-Gate "instrumentation failed or timed out ($detail)"
    }

    $reports = @(Get-ChildItem -LiteralPath (Join-Path $AndroidRoot 'engine/build') -File -Recurse -Filter 'TEST-*.xml' |
        Where-Object {
            $_.LastWriteTimeUtc -gt $reportStarted -and
            [System.IO.File]::ReadAllText($_.FullName).Contains($InstrumentationClass, [StringComparison]::Ordinal)
        })
    if ($reports.Count -ne 1) { Fail-Gate "expected one fresh instrumentation XML report, found $($reports.Count)" }
    try { [xml]$xml = [System.IO.File]::ReadAllText($reports[0].FullName) } catch {
        Fail-Gate 'instrumentation report is not valid XML'
    }
    $suite = $xml.SelectSingleNode('//testsuite')
    if ($null -eq $suite) { Fail-Gate 'instrumentation XML has no testsuite element' }
    $tests = [int]$suite.GetAttribute('tests')
    $failures = if ($suite.HasAttribute('failures')) { [int]$suite.GetAttribute('failures') } else { 0 }
    $errors = if ($suite.HasAttribute('errors')) { [int]$suite.GetAttribute('errors') } else { 0 }
    $skipped = if ($suite.HasAttribute('skipped')) { [int]$suite.GetAttribute('skipped') } else { 0 }
    if ($tests -ne 1 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
        Fail-Gate "instrumentation XML is not one clean pass: tests=$tests failures=$failures errors=$errors skipped=$skipped"
    }
    Copy-Item -LiteralPath $reports[0].FullName `
        -Destination (Join-Path $script:State.OutputPath 'reports/TEST-AndroidFairyEngineInstrumentedTest.xml')
    $deviceState = Invoke-Adb @('-s', $script:State.DeviceSerial, 'get-state')
    if ($deviceState.ExitCode -ne 0 -or $deviceState.Stdout.Trim() -ne 'device') {
        Fail-Gate 'device disconnected after instrumentation'
    }
    if ((Get-ArtifactSetHash) -ne $script:State.VerifiedArtifactSetSha) {
        Fail-Gate 'Gradle changed a verified artifact during instrumentation'
    }
    $script:State.RuntimeVerifiedAbi = $script:State.SelectedAbi
}

function Test-NativeStructure {
    param([switch]$RequireSource)

    $nativeRoot = Join-Path $RepositoryRoot 'engine/native'
    $androidEngine = Join-Path $AndroidRoot 'engine'
    $lockFile = $NativeLockPath
    $wasmLockPath = Join-Path $nativeRoot 'wasm-poc.properties'
    $sourceManifest = Join-Path $nativeRoot 'source-manifest.txt'
    $nativeBridge = Join-Path $androidEngine 'src/main/cpp/native_bridge.cpp'
    $nativeExports = Join-Path $androidEngine 'src/main/cpp/native_exports.map'
    $kotlinBindings = Join-Path $androidEngine 'src/main/kotlin/com/drawlesschess/engine/FairyNativeBindings.kt'
    $jniPort = Join-Path $androidEngine 'src/main/kotlin/com/drawlesschess/engine/JniFairyEnginePort.kt'
    $engineFactory = Join-Path $androidEngine 'src/main/kotlin/com/drawlesschess/engine/AndroidFairyEngineFactory.kt'
    $instrumentedTest = Join-Path $androidEngine 'src/androidTest/kotlin/com/drawlesschess/engine/AndroidFairyEngineInstrumentedTest.kt'
    $consumerRules = Join-Path $androidEngine 'consumer-rules.pro'
    $hostBindings = Join-Path $nativeRoot 'host-test/com/drawlesschess/engine/FairyNativeBindings.java'
    $engineBuild = Join-Path $androidEngine 'build.gradle.kts'
    $cmakeLists = Join-Path $androidEngine 'src/main/cpp/CMakeLists.txt'
    $sourceNotice = Join-Path $nativeRoot 'SOURCE_NOTICE.txt'

    foreach ($required in @(
        $lockFile, $wasmLockPath, $sourceManifest, $sourceNotice, $engineBuild,
        $consumerRules, $cmakeLists, $nativeBridge, $nativeExports,
        (Join-Path $androidEngine 'src/main/cpp/native_identity.cpp'), $kotlinBindings,
        $jniPort, $engineFactory,
        (Join-Path $androidEngine 'src/main/kotlin/com/drawlesschess/engine/AndroidUciTimeoutScheduler.kt'),
        (Join-Path $androidEngine 'src/main/kotlin/com/drawlesschess/engine/VariantConfigInstaller.kt'),
        (Join-Path $androidEngine 'src/test/kotlin/com/drawlesschess/core/JniFairyEnginePortTests.kt'),
        $instrumentedTest, $hostBindings,
        (Join-Path $ScriptDirectory 'native-verify-jni-host.sh'),
        (Join-Path $ScriptDirectory 'native-verify-aar.sh')
    )) { Require-File $required }

    $revision = Get-NativeProperty 'revision'
    $tree = Get-NativeProperty 'tree'
    $patchedTree = Get-NativeProperty 'patchedTree'
    $repository = Get-NativeProperty 'repository'
    $sourceDirectory = Get-NativeProperty 'sourceDirectory'
    $patchSeriesRelative = Get-NativeProperty 'patchSeries'
    $expectedPatchHash = Get-NativeProperty 'patchSeriesSha256'
    $patchVersion = Get-NativeProperty 'drawlessPatchVersion'
    $ndkVersion = Get-NativeProperty 'androidNdkVersion'
    $cmakeVersion = Get-NativeProperty 'cmakeVersion'
    $cmakeExecutableVersion = Get-NativeProperty 'cmakeExecutableVersion'
    $libraryName = Get-NativeProperty 'nativeLibraryName'
    $variantConfigHash = Get-NativeProperty 'variantConfigSha256'
    $bridgeAbiVersion = Get-NativeProperty 'nativeBridgeAbiVersion'

    foreach ($identity in @($revision, $tree, $patchedTree)) {
        if ($identity -notmatch '^[0-9a-f]{40}$') { Fail-Gate 'native Git identity is not a full hash' }
    }
    if ($expectedPatchHash -notmatch '^[0-9a-f]{64}$') { Fail-Gate 'invalid patch-series SHA-256' }
    if ($repository -ne 'https://github.com/fairy-stockfish/Fairy-Stockfish.git') {
        Fail-Gate 'native repository is not the canonical Fairy-Stockfish URL'
    }
    if ($patchVersion -notmatch '^[1-9][0-9]*$') { Fail-Gate 'invalid Drawless patch version' }
    if ($ndkVersion -notmatch '^\d+\.\d+\.\d+$') { Fail-Gate 'invalid NDK version' }
    if ($cmakeVersion -notmatch '^\d+\.\d+\.\d+$') { Fail-Gate 'invalid CMake version' }
    if ($cmakeExecutableVersion -notmatch '^\S+$') { Fail-Gate 'invalid CMake executable version' }
    if ($libraryName -ne 'drawless_fairy') { Fail-Gate 'unexpected version-1 native library name' }
    if ($variantConfigHash -notmatch '^[0-9a-f]{64}$') { Fail-Gate 'invalid variant-config SHA-256' }
    if ($bridgeAbiVersion -notmatch '^[1-9][0-9]*$') { Fail-Gate 'invalid native bridge ABI version' }
    if ((Get-Sha256File (Join-Path $RepositoryRoot 'engine/variants.ini')) -ne $variantConfigHash) {
        Fail-Gate 'Drawless native variant configuration drifted from the lock'
    }

    $wasmLock = Read-PropertyFile $wasmLockPath
    if ($wasmLock.packageGitHead -ne '5589ea54f322e8e76c199440e55ae39fe5d3b09c' -or
        $wasmLock.packageVersion -ne '1.1.11' -or
        $wasmLock.nativeSourceIdentityIsSeparate -ne 'true') {
        Fail-Gate 'WASM proof-of-concept identity or separation contract drifted'
    }

    $patchSeriesPath = [System.IO.Path]::GetFullPath((Join-Path $nativeRoot $patchSeriesRelative))
    Require-File $patchSeriesPath
    $patchDirectory = Split-Path -Parent $patchSeriesPath
    $seriesBytes = [System.IO.File]::ReadAllBytes($patchSeriesPath)
    $patchResult = Get-OrderedPatchHash -SeriesBytes $seriesBytes -ReadPatchBytes {
        param($entry)
        $patchPath = Join-Path $patchDirectory $entry
        if (-not (Test-Path -LiteralPath $patchPath -PathType Leaf)) { return $null }
        return ,([System.IO.File]::ReadAllBytes($patchPath))
    }
    if ($patchResult.Entries.Count -eq 0) { Fail-Gate 'production patch series is empty' }
    if ($patchResult.Hash -ne $expectedPatchHash) { Fail-Gate 'ordered patch series drifted from native lock' }

    $manifestEntries = [System.Collections.Generic.List[string]]::new()
    foreach ($rawLine in [System.IO.File]::ReadAllLines($sourceManifest)) {
        $entry = $rawLine.TrimEnd("`r")
        if (-not $entry -or $entry.StartsWith('#', [StringComparison]::Ordinal)) { continue }
        if (-not $entry.EndsWith('.cpp', [StringComparison]::Ordinal) -or $entry -eq 'main.cpp') {
            Fail-Gate "invalid source-manifest entry: $entry"
        }
        [void]$manifestEntries.Add($entry)
    }
    if ($manifestEntries.Count -eq 0) { Fail-Gate 'source manifest is empty' }
    $uniqueManifestEntries = @($manifestEntries | Sort-Object -Unique)
    if ($uniqueManifestEntries.Count -ne $manifestEntries.Count) { Fail-Gate 'duplicate source-manifest entry' }

    $requiredTextByFile = [ordered]@{
        $engineBuild = @(
            'abiFilters += listOf("arm64-v8a", "x86_64")',
            'ndkVersion = nativePin("androidNdkVersion")', '"-DANDROID_STL=c++_static"',
            'gitOutput("write-tree")', 'patchesApplied" to "true"',
            'consumerProguardFiles("consumer-rules.pro")', '"NATIVE_BRIDGE_ABI_VERSION"',
            'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"',
            'androidTestImplementation("androidx.test:core:1.7.0")',
            'androidTestImplementation("androidx.test:runner:1.7.0")',
            'androidTestImplementation("androidx.test.ext:junit:1.3.0")'
        )
        $cmakeLists = @(
            'set(DRAWLESS_ALLOWED_ABIS arm64-v8a x86_64)', 'add_library(drawless_fairy SHARED',
            'native_bridge.cpp', 'native_identity.cpp',
            'cmake_path(SET FAIRY_SOURCE_DIR NORMALIZE "${FAIRY_SOURCE_DIR}")',
            'cmake_path(SET DRAWLESS_NATIVE_ROOT NORMALIZE "${DRAWLESS_NATIVE_ROOT}")',
            'DRAWLESS_BRIDGE_ABI_VERSION=${DRAWLESS_BRIDGE_ABI_VERSION}',
            'COMMAND "${GIT_EXECUTABLE}" -C "${FAIRY_SOURCE_DIR}" write-tree',
            'find_library(ANDROID_LOG_LIBRARY log REQUIRED)',
            'target_link_options(drawless_fairy PRIVATE -Wl,--no-gc-sections)',
            '-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/native_exports.map'
        )
        $nativeExports = @('JNI_OnLoad;', 'local:', '*;')
        $nativeBridge = @(
            'constexpr char kBindingClass[] = "com/drawlesschess/engine/FairyNativeBindings";',
            'extern "C" JNIEXPORT jint JNICALL JNI_OnLoad', 'environment->RegisterNatives(',
            '{const_cast<char*>("nativeCreate"), const_cast<char*>("(Ljava/lang/String;)J")',
            '{const_cast<char*>("nativeStart"), const_cast<char*>("(J)V")',
            '{const_cast<char*>("nativeWrite"), const_cast<char*>("(J[BII)I")',
            '{const_cast<char*>("nativeRead"), const_cast<char*>("(J[BII)I")',
            '{const_cast<char*>("nativeReadError"), const_cast<char*>("(J[BII)I")',
            '{const_cast<char*>("nativeClose"), const_cast<char*>("(J)V")'
        )
        $kotlinBindings = @(
            'System.loadLibrary("drawless_fairy")', 'object FairyNativeBindings {',
            'external fun nativeCreate(variantConfigPath: String): Long',
            'external fun nativeStart(handle: Long)',
            'external fun nativeWrite(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int',
            'external fun nativeRead(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int',
            'external fun nativeReadError(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int',
            'external fun nativeClose(handle: Long)'
        )
        $consumerRules = @(
            '-keep class com.drawlesschess.engine.FairyNativeBindings {',
            'public static native <methods>;'
        )
        $jniPort = @('class JniFairyEnginePort private constructor(')
        $engineFactory = @(
            'port = JniFairyEnginePort(variantFile.absolutePath, portPolicy)',
            'BuildConfig.NATIVE_BRIDGE_ABI_VERSION'
        )
        $instrumentedTest = @(
            '@RunWith(AndroidJUnit4::class)', 'class AndroidFairyEngineInstrumentedTest',
            'fun forcedRepetitionSearchClosesAndRestartsSequentially()', 'AndroidFairyEngineFactory(',
            'val first = factory.create()', 'first.close()', 'val second = factory.create()',
            'second.close()', 'h8g8', 'response.engine.drawlessPatch'
        )
        $hostBindings = @(
            'private static native long nativeCreate(String variantConfigPath);',
            'private static native void nativeClose(long handle);'
        )
    }
    foreach ($fileEntry in $requiredTextByFile.GetEnumerator()) {
        foreach ($requiredText in $fileEntry.Value) { Require-Text $fileEntry.Key $requiredText }
    }
    Reject-Text $cmakeLists 'ANDROID_ATOMIC_LIBRARY'
    Require-TextCount $kotlinBindings '@JvmStatic' 6
    Require-TextCount $kotlinBindings 'external fun native' 6
    Require-TextCount $nativeBridge 'reinterpret_cast<void*>(native_' 6
    Require-Text $sourceNotice 'GNU General Public License'
    Require-Text $sourceNotice $revision
    Require-Text $sourceNotice 'complete corresponding source'

    $source = [System.IO.Path]::GetFullPath((Join-Path $nativeRoot $sourceDirectory))
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        if ($RequireSource) {
            Fail-Gate 'pinned source is absent; run pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/native-fetch-fairy.ps1'
        }
        Write-PhaseMessage 'Native structure PASS (source checkout absent; NDK compilation not tested).'
        return
    }
    if (-not (Test-Path -LiteralPath (Join-Path $source '.git'))) {
        Fail-Gate 'source directory is not the pinned Git checkout'
    }

    $git = $script:State.GitPath
    $head = Invoke-CheckedProcess -FilePath $git -Arguments @('-c', 'core.filemode=false', '-C', $source, 'rev-parse', 'HEAD') -FailureMessage 'could not read native source revision'
    $headTree = Invoke-CheckedProcess -FilePath $git -Arguments @('-c', 'core.filemode=false', '-C', $source, 'rev-parse', 'HEAD^{tree}') -FailureMessage 'could not read native source tree'
    if ($head.Stdout.Trim() -ne $revision) { Fail-Gate 'source revision does not match lock' }
    if ($headTree.Stdout.Trim() -ne $tree) { Fail-Gate 'source base tree does not match lock' }

    $stateFilePath = Join-Path $source '.drawless-source-state.properties'
    $sourceState = Read-PropertyFile $stateFilePath
    $expectedState = [ordered]@{
        upstreamRevision = $revision; upstreamTree = $tree; patchedTree = $patchedTree
        patchVersion = $patchVersion; patchesApplied = 'true'; patchSeriesSha256 = $expectedPatchHash
    }
    foreach ($entry in $expectedState.GetEnumerator()) {
        if (-not $sourceState.ContainsKey($entry.Key) -or $sourceState[$entry.Key] -ne $entry.Value) {
            Fail-Gate "source-state $($entry.Key) mismatch"
        }
    }
    foreach ($sourceEntry in $manifestEntries) {
        Require-File (Join-Path $source "src/$sourceEntry")
    }
    Require-File (Join-Path $source 'Copying.txt')
    Require-File (Join-Path $source 'AUTHORS')

    $temporaryIndex = Join-Path $script:State.TempRoot 'expected-native-index'
    $gitEnvironment = @{ GIT_INDEX_FILE = $temporaryIndex }
    try {
        $null = Invoke-CheckedProcess -FilePath $git -Arguments @('-c', 'core.filemode=false', '-C', $source, 'read-tree', 'HEAD') -Environment $gitEnvironment -FailureMessage 'could not create expected native index'
        foreach ($patchEntry in $patchResult.Entries) {
            $patchPath = Join-Path $patchDirectory $patchEntry
            $null = Invoke-CheckedProcess -FilePath $git -Arguments @('-c', 'core.filemode=false', '-C', $source, 'apply', '--cached', $patchPath) -Environment $gitEnvironment -FailureMessage "could not apply expected patch $patchEntry"
        }
        $expectedTreeResult = Invoke-CheckedProcess -FilePath $git -Arguments @('-c', 'core.filemode=false', '-C', $source, 'write-tree') -Environment $gitEnvironment -FailureMessage 'could not compute expected patched tree'
        $expectedPatchedTree = $expectedTreeResult.Stdout.Trim()
    } finally {
        if (Test-Path -LiteralPath $temporaryIndex) { Remove-Item -LiteralPath $temporaryIndex -Force }
        $indexLock = "$temporaryIndex.lock"
        if (Test-Path -LiteralPath $indexLock) { Remove-Item -LiteralPath $indexLock -Force }
    }
    $actualTree = Invoke-CheckedProcess -FilePath $git -Arguments @('-c', 'core.filemode=false', '-C', $source, 'write-tree') -FailureMessage 'could not compute actual patched tree'
    if ($actualTree.Stdout.Trim() -ne $expectedPatchedTree -or $actualTree.Stdout.Trim() -ne $patchedTree) {
        Fail-Gate 'patched source tree does not match the exact ordered patch series and lock'
    }
    $unstaged = Invoke-ProcessCapture -FilePath $git -Arguments @('-c', 'core.filemode=false', '-C', $source, 'diff', '--quiet')
    if ($unstaged.ExitCode -ne 0) { Fail-Gate 'source has unstaged modifications' }
    $null = Invoke-CheckedProcess -FilePath $git -Arguments @('-c', 'core.filemode=false', '-C', $source, 'diff', '--cached', '--check') -FailureMessage 'staged source patch has whitespace errors'
    $untrackedResult = Invoke-CheckedProcess -FilePath $git -Arguments @('-c', 'core.filemode=false', '-C', $source, 'ls-files', '--others', '--exclude-standard') -FailureMessage 'could not inspect native untracked files'
    $unexpected = @($untrackedResult.Stdout -split "`r?`n" | Where-Object { $_ -and $_ -ne '.drawless-source-state.properties' })
    if ($unexpected.Count -gt 0) { Fail-Gate "source contains unexpected untracked files: $($unexpected -join ', ')" }

    Write-PhaseMessage 'Native structure and pinned source PASS.'
    Write-PhaseMessage "  native pin / patched tree: $revision / $patchedTree"
    Write-PhaseMessage "  patch series SHA-256: $($patchResult.Hash)"
    Write-PhaseMessage "  ABIs: arm64-v8a, x86_64; NDK/CMake: $ndkVersion / $cmakeVersion"
}

try {
    Initialize-Evidence
    Invoke-Preflight
    if ($PreflightOnly) {
        $script:State.Status = 'preflight-passed'
        $script:State.FailedPhase = ''
        $script:State.ExitCode = 0
    } else {
        $script:State.FailedPhase = 'device-selection'
        Select-AndroidDevice
        $script:State.FailedPhase = 'build'
        Invoke-AndroidBuild
        $script:State.FailedPhase = 'artifact-verification'
        Invoke-ArtifactVerification
        $script:State.FailedPhase = 'instrumentation'
        Invoke-NativeInstrumentation
        $script:State.Status = 'passed'
        $script:State.FailedPhase = ''
        $script:State.ExitCode = 0
        Write-Host "Android machine verification PASS ($($script:State.RuntimeVerifiedAbi) on $($script:State.DeviceKind))."
    }
} catch {
    $script:State.ExitCode = 1
    [Console]::Error.WriteLine("android-machine-verify: $($_.Exception.Message)")
    if ($script:State.OutputPath) {
        try {
            [System.IO.File]::AppendAllText(
                (Join-Path $script:State.OutputPath 'logs/gate.log'),
                "android-machine-verify: $($_.Exception.Message)`r`n", $Utf8NoBom
            )
        } catch { }
    }
} finally {
    Finalize-Evidence
}

exit $script:State.ExitCode
