[CmdletBinding()]
param(
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptDirectory '..')).Path
$androidRoot = Join-Path $repositoryRoot 'android'
$gradleWrapper = Join-Path $androidRoot 'gradlew.bat'
$reportDirectory = if ($OutputDirectory) {
    if ([IO.Path]::IsPathRooted($OutputDirectory)) {
        [IO.Path]::GetFullPath($OutputDirectory)
    } else {
        [IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputDirectory))
    }
} else {
    Join-Path $repositoryRoot 'release\reports'
}
$dependencyReportPath = Join-Path $reportDirectory 'release-runtime-dependencies.txt'
$sbomPath = Join-Path $reportDirectory 'release-sbom.cdx.json'
$apacheLicensePath = Join-Path $repositoryRoot 'APACHE-2.0.txt'
$appBuildPath = Join-Path $androidRoot 'app\build.gradle.kts'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Fail {
    param([Parameter(Mandatory)][string]$Message)
    throw "generate-release-sbom: $Message"
}

function Write-Utf8Lf {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Content
    )
    $normalized = $Content.Replace("`r`n", "`n").Replace("`r", "`n")
    if (-not $normalized.EndsWith("`n", [StringComparison]::Ordinal)) {
        $normalized += "`n"
    }
    $parent = Split-Path -Parent $Path
    [void][System.IO.Directory]::CreateDirectory($parent)
    [System.IO.File]::WriteAllText($Path, $normalized, $utf8NoBom)
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Get-PomFile {
    param(
        [Parameter(Mandatory)][string]$Group,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Version
    )
    $gradleUserHome = if ($env:GRADLE_USER_HOME) {
        $env:GRADLE_USER_HOME
    } else {
        Join-Path $HOME '.gradle'
    }
    $moduleDirectory = Join-Path $gradleUserHome (
        'caches\modules-2\files-2.1\{0}\{1}\{2}' -f $Group, $Name, $Version
    )
    if (-not (Test-Path -LiteralPath $moduleDirectory -PathType Container)) {
        Fail "Gradle cache directory is absent for ${Group}:${Name}:${Version}: $moduleDirectory"
    }
    $poms = @(Get-ChildItem -LiteralPath $moduleDirectory -File -Recurse -Filter '*.pom' |
        Sort-Object FullName)
    if ($poms.Count -ne 1) {
        Fail "expected one cached POM for ${Group}:${Name}:${Version}, found $($poms.Count)"
    }
    return $poms[0]
}

function Convert-ToSpdxLicense {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Url,
        [Parameter(Mandatory)][string]$Coordinate
    )
    $combined = "$Name $Url"
    if ($combined -match '(?i)Apache(?: Software)? License.*(?:Version )?2\.0|apache\.org/licenses/LICENSE-2\.0') {
        return 'Apache-2.0'
    }
    Fail "unreviewed license '$Name' ('$Url') for $Coordinate"
}

function Get-MavenPackaging {
    param([Parameter(Mandatory)][xml]$PomXml)
    $node = $PomXml.project.SelectSingleNode("*[local-name()='packaging']")
    if ($null -eq $node -or -not $node.InnerText.Trim()) { return 'jar' }
    return $node.InnerText.Trim()
}

function Get-XmlChildText {
    param(
        [Parameter(Mandatory)][System.Xml.XmlNode]$Node,
        [Parameter(Mandatory)][string]$LocalName
    )
    $child = $Node.SelectSingleNode("*[local-name()='$LocalName']")
    if ($null -eq $child) { return '' }
    return $child.InnerText.Trim()
}

function Get-PomLicenseEvidence {
    param(
        [Parameter(Mandatory)][string]$Group,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Version,
        [string[]]$Trail = @()
    )
    $coordinate = "${Group}:${Name}:${Version}"
    if ($Trail -contains $coordinate) {
        Fail "cyclic Maven parent chain while resolving license evidence for $coordinate"
    }

    $pom = Get-PomFile -Group $Group -Name $Name -Version $Version
    try {
        [xml]$xml = [System.IO.File]::ReadAllText($pom.FullName)
    } catch {
        Fail "cached POM is not valid XML for $coordinate ($($pom.FullName)): $($_.Exception.Message)"
    }
    $pomSha256 = Get-Sha256 $pom.FullName
    $declaredLicenses = @($xml.SelectNodes(
        "/*[local-name()='project']/*[local-name()='licenses']/*[local-name()='license']"
    ))
    if ($declaredLicenses.Count -gt 0) {
        $spdxIds = @($declaredLicenses | ForEach-Object {
            Convert-ToSpdxLicense -Name (Get-XmlChildText $_ 'name') `
                -Url (Get-XmlChildText $_ 'url') -Coordinate $coordinate
        } | Sort-Object -Unique)
        return [pscustomobject]@{
            SpdxIds = $spdxIds
            Evidence = "Declared by Maven POM $coordinate (SHA-256 $pomSha256)"
            PomSha256 = $pomSha256
            EvidencePomSha256 = $pomSha256
            Packaging = Get-MavenPackaging $xml
        }
    }

    $parent = $xml.SelectSingleNode("/*[local-name()='project']/*[local-name()='parent']")
    if ($null -eq $parent -or -not (Get-XmlChildText $parent 'artifactId') -or
        -not (Get-XmlChildText $parent 'version')) {
        Fail "POM $coordinate has neither a license declaration nor a resolvable parent POM"
    }
    $parentGroupText = Get-XmlChildText $parent 'groupId'
    $parentGroup = if ($parentGroupText) { $parentGroupText } else { $Group }
    $parentName = Get-XmlChildText $parent 'artifactId'
    $parentVersion = Get-XmlChildText $parent 'version'
    if ("$parentGroup$parentName$parentVersion" -match '\$\{') {
        Fail "POM $coordinate uses an unresolved property in its parent coordinate"
    }
    $parentCoordinate = "${parentGroup}:${parentName}:${parentVersion}"
    $parentEvidence = Get-PomLicenseEvidence `
        -Group $parentGroup -Name $parentName -Version $parentVersion -Trail ($Trail + $coordinate)
    return [pscustomobject]@{
        SpdxIds = @($parentEvidence.SpdxIds)
        Evidence = "Inherited from Maven parent POM $parentCoordinate via $coordinate; $($parentEvidence.Evidence)"
        PomSha256 = $pomSha256
        EvidencePomSha256 = $parentEvidence.EvidencePomSha256
        Packaging = Get-MavenPackaging $xml
    }
}

foreach ($required in @($gradleWrapper, $appBuildPath, $apacheLicensePath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        Fail "required file is absent: $required"
    }
}

if (-not $env:JAVA_HOME) {
    $androidStudioJbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path -LiteralPath (Join-Path $androidStudioJbr 'bin\java.exe') -PathType Leaf) {
        $env:JAVA_HOME = $androidStudioJbr
    }
}
if (-not $env:JAVA_HOME -or
    -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe') -PathType Leaf)) {
    Fail 'JAVA_HOME must identify a JDK (Android Studio jbr is accepted)'
}
if (-not $env:ANDROID_HOME) {
    $defaultAndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $defaultAndroidSdk -PathType Container) {
        $env:ANDROID_HOME = $defaultAndroidSdk
    }
}
if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath $env:ANDROID_HOME -PathType Container)) {
    Fail 'ANDROID_HOME must identify the installed Android SDK'
}

$appBuildText = [System.IO.File]::ReadAllText($appBuildPath)
$versionNameMatch = [regex]::Match($appBuildText, '(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$')
$versionCodeMatch = [regex]::Match($appBuildText, '(?m)^\s*versionCode\s*=\s*(\d+)\s*$')
if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) {
    Fail 'could not read versionName/versionCode from android/app/build.gradle.kts'
}
$versionName = $versionNameMatch.Groups[1].Value
$versionCode = $versionCodeMatch.Groups[1].Value

[void][System.IO.Directory]::CreateDirectory($reportDirectory)
$gradleArguments = @('--offline', '--no-daemon', '--console=plain')
Push-Location $androidRoot
try {
    $dependencyOutput = @(& $gradleWrapper @gradleArguments ':app:dependencies' `
        '--configuration' 'releaseRuntimeClasspath' 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) {
        Fail "Gradle dependency report failed:`n$($dependencyOutput -join "`n")"
    }
} finally {
    Pop-Location
}
$reportStart = -1
for ($index = 0; $index -lt $dependencyOutput.Count; $index++) {
    if ($dependencyOutput[$index].StartsWith('> Task :app:dependencies', [StringComparison]::Ordinal)) {
        $reportStart = $index
        break
    }
}
if ($reportStart -lt 0) { Fail 'Gradle output did not contain the app dependency task report' }
$canonicalReport = [System.Collections.Generic.List[string]]::new()
for ($index = $reportStart; $index -lt $dependencyOutput.Count; $index++) {
    if ($dependencyOutput[$index].StartsWith('BUILD SUCCESSFUL', [StringComparison]::Ordinal)) { break }
    $canonicalReport.Add($dependencyOutput[$index].TrimEnd())
}
while ($canonicalReport.Count -gt 0 -and -not $canonicalReport[$canonicalReport.Count - 1]) {
    $canonicalReport.RemoveAt($canonicalReport.Count - 1)
}
Write-Utf8Lf -Path $dependencyReportPath -Content ($canonicalReport -join "`n")

$initScriptPath = Join-Path ([System.IO.Path]::GetTempPath()) (
    'drawless-release-sbom-{0}.init.gradle' -f [guid]::NewGuid().ToString('N')
)
$initScript = @'
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult

gradle.beforeProject { project ->
    if (project.path == ':app') {
        project.tasks.register('drawlessReleaseSbomInventory') {
            doLast {
                def configuration = project.configurations.getByName('releaseRuntimeClasspath')
                def result = configuration.incoming.resolutionResult
                result.allComponents.each { component ->
                    if (component.id instanceof ModuleComponentIdentifier) {
                        def id = component.id
                        println("DRAWLESS_SBOM_COMPONENT\t${id.group}\t${id.module}\t${id.version}")
                        component.dependencies.each { dependency ->
                            if (dependency instanceof ResolvedDependencyResult &&
                                    dependency.selected.id instanceof ModuleComponentIdentifier) {
                                def selected = dependency.selected.id
                                println("DRAWLESS_SBOM_EDGE\t${id.group}\t${id.module}\t${id.version}" +
                                    "\t${selected.group}\t${selected.module}\t${selected.version}")
                            }
                        }
                    }
                }
            }
        }
    }
}
'@
[System.IO.File]::WriteAllText($initScriptPath, $initScript, $utf8NoBom)
try {
    Push-Location $androidRoot
    try {
        $inventoryOutput = @(& $gradleWrapper @gradleArguments `
            '--init-script' $initScriptPath ':app:drawlessReleaseSbomInventory' 2>&1 |
            ForEach-Object { "$_" })
        if ($LASTEXITCODE -ne 0) {
            Fail "Gradle resolved-component inventory failed:`n$($inventoryOutput -join "`n")"
        }
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item -LiteralPath $initScriptPath -Force -ErrorAction SilentlyContinue
}

$coordinateSet = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$edgeSet = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($line in $inventoryOutput) {
    if ($line.StartsWith("DRAWLESS_SBOM_COMPONENT`t", [StringComparison]::Ordinal)) {
        $parts = $line.Split("`t")
        if ($parts.Count -ne 4) { Fail "malformed Gradle component record: $line" }
        [void]$coordinateSet.Add("$($parts[1]):$($parts[2]):$($parts[3])")
    } elseif ($line.StartsWith("DRAWLESS_SBOM_EDGE`t", [StringComparison]::Ordinal)) {
        $parts = $line.Split("`t")
        if ($parts.Count -ne 7) { Fail "malformed Gradle dependency-edge record: $line" }
        [void]$edgeSet.Add(
            "$($parts[1]):$($parts[2]):$($parts[3])`t$($parts[4]):$($parts[5]):$($parts[6])"
        )
    }
}
if ($coordinateSet.Count -eq 0) {
    Fail 'Gradle returned no external modules for app:releaseRuntimeClasspath'
}

$components = [System.Collections.Generic.List[object]]::new()
$references = @{}
foreach ($coordinate in @($coordinateSet | Sort-Object)) {
    $parts = $coordinate.Split(':')
    if ($parts.Count -ne 3) { Fail "unsupported Maven coordinate: $coordinate" }
    $group = $parts[0]
    $name = $parts[1]
    $version = $parts[2]
    $purl = "pkg:maven/$group/$name@$version"
    $references[$coordinate] = $purl
    $licenseEvidence = Get-PomLicenseEvidence -Group $group -Name $name -Version $version
    $licenses = @($licenseEvidence.SpdxIds | Sort-Object -Unique | ForEach-Object {
        [ordered]@{
            license = [ordered]@{
                id = $_
                url = if ($_ -eq 'Apache-2.0') {
                    'https://www.apache.org/licenses/LICENSE-2.0.txt'
                } else {
                    Fail "no canonical license URL configured for $_"
                }
            }
        }
    })
    $component = [ordered]@{
        type = 'library'
        'bom-ref' = $purl
        group = $group
        name = $name
        version = $version
        purl = $purl
        licenses = $licenses
        properties = @(
            [ordered]@{ name = 'drawless:resolvedConfiguration'; value = 'app:releaseRuntimeClasspath' },
            [ordered]@{ name = 'drawless:mavenPackaging'; value = $licenseEvidence.Packaging },
            [ordered]@{ name = 'drawless:pomSha256'; value = $licenseEvidence.PomSha256 },
            [ordered]@{ name = 'drawless:licenseEvidencePomSha256'; value = $licenseEvidence.EvidencePomSha256 },
            [ordered]@{ name = 'drawless:licenseEvidence'; value = $licenseEvidence.Evidence }
        )
    }
    $components.Add($component)
}

$rootReference = "pkg:generic/drawless-chess@$versionName"
$dependencyMap = @{}
foreach ($coordinate in $references.Keys) {
    $dependencyMap[$coordinate] = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
}
foreach ($edge in $edgeSet) {
    $edgeParts = $edge.Split("`t")
    if ($edgeParts.Count -eq 2 -and $references.ContainsKey($edgeParts[0]) -and
        $references.ContainsKey($edgeParts[1])) {
        [void]$dependencyMap[$edgeParts[0]].Add($references[$edgeParts[1]])
    }
}
$dependencies = [System.Collections.Generic.List[object]]::new()
$dependencies.Add([ordered]@{
    ref = $rootReference
    dependsOn = @($references.Values | Sort-Object -Unique)
})
foreach ($coordinate in @($references.Keys | Sort-Object)) {
    $dependencies.Add([ordered]@{
        ref = $references[$coordinate]
        dependsOn = @($dependencyMap[$coordinate] | Sort-Object)
    })
}

$reportSha256 = Get-Sha256 $dependencyReportPath
$apacheSha256 = Get-Sha256 $apacheLicensePath
$bom = [ordered]@{
    bomFormat = 'CycloneDX'
    specVersion = '1.5'
    version = 1
    metadata = [ordered]@{
        component = [ordered]@{
            type = 'application'
            'bom-ref' = $rootReference
            group = 'com.drawlesschess'
            name = 'Drawless Chess'
            version = $versionName
            purl = $rootReference
            licenses = @(
                [ordered]@{ license = [ordered]@{ id = 'GPL-3.0-or-later' } }
            )
            properties = @(
                [ordered]@{ name = 'drawless:androidVersionCode'; value = $versionCode },
                [ordered]@{ name = 'drawless:gradleConfiguration'; value = 'app:releaseRuntimeClasspath' },
                [ordered]@{ name = 'drawless:dependencyReportSha256'; value = $reportSha256 },
                [ordered]@{ name = 'drawless:apacheLicenseTextSha256'; value = $apacheSha256 },
                [ordered]@{ name = 'drawless:licenseEvidencePolicy'; value = 'Maven POM declarations; parent inheritance resolved and recorded' }
            )
        }
    }
    components = @($components)
    dependencies = @($dependencies)
}
$json = $bom | ConvertTo-Json -Depth 20
Write-Utf8Lf -Path $sbomPath -Content $json

# Parse the just-written artifact so truncation or serialization errors fail the gate.
try {
    $roundTrip = [System.IO.File]::ReadAllText($sbomPath) | ConvertFrom-Json
} catch {
    Fail "generated CycloneDX JSON is invalid: $($_.Exception.Message)"
}
if ($roundTrip.bomFormat -cne 'CycloneDX' -or $roundTrip.specVersion -cne '1.5' -or
    @($roundTrip.components).Count -ne $coordinateSet.Count) {
    Fail 'generated CycloneDX JSON failed its component-count/format self-check'
}

Write-Host (
    'Release SBOM PASS: {0} resolved modules; dependency report {1}; CycloneDX {2}' -f `
        $coordinateSet.Count, $dependencyReportPath, $sbomPath
)
