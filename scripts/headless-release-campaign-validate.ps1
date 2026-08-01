[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $ReportPath,

    [ValidateRange(10000, 100000)]
    [int] $ExpectedGames = 10032,

    [ValidateRange(5000, 100000)]
    [int] $RequiredCleanTail = 5000,

    [switch] $RequireZeroWorkResume
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runsRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build/headless/runs')).TrimEnd('\', '/')
$candidate = if ([IO.Path]::IsPathRooted($ReportPath)) {
    $ReportPath
} else {
    Join-Path $repositoryRoot $ReportPath
}
$report = [IO.Path]::GetFullPath($candidate)
if (-not $report.StartsWith($runsRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "ReportPath must remain under '$runsRoot': '$report'."
}
if (-not (Test-Path -LiteralPath $report -PathType Leaf)) {
    throw "Release campaign report is missing: '$report'."
}

$allowedReasons = @(
    'CHECKMATE',
    'STALEMATE',
    'REPETITION',
    'DEAD_POSITION_MATERIAL',
    'FIFTY_MOVE_LIMIT',
    'BARE_KING'
)
$headers = 0
$header = $null
$fingerprints = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$jobIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$pairs = @{}
$starts = [Collections.Generic.List[object]]::new()
$summaries = [Collections.Generic.List[object]]::new()
$games = 0
$failures = 0
$sameLevelGames = 0
$pairedGames = 0
$cleanStreak = 0
$maximumCleanStreak = 0
$categoryGames = [ordered]@{ opening = 0; endgame = 0; edge = 0 }
$lineNumber = 0

$reader = [IO.StreamReader]::new($report, [Text.UTF8Encoding]::new($false, $true), $true)
try {
    while (-not $reader.EndOfStream) {
        $line = $reader.ReadLine()
        $lineNumber++
        if ([string]::IsNullOrWhiteSpace($line)) {
            throw "Report contains a blank record at line $lineNumber."
        }
        try {
            $record = $line | ConvertFrom-Json -AsHashtable -Depth 100
        } catch {
            throw "Report line $lineNumber is not valid JSON: $($_.Exception.Message)"
        }
        if (-not $record.ContainsKey('event')) {
            throw "Report line $lineNumber has no event."
        }
        if ($record.ContainsKey('run_fingerprint')) {
            $null = $fingerprints.Add([string]$record.run_fingerprint)
        }
        switch ([string]$record.event) {
            'run_header' {
                $headers++
                $header = $record
            }
            'invocation_started' {
                $starts.Add($record)
            }
            'game_failure' {
                $failures++
                $cleanStreak = 0
            }
            'game' {
                $games++
                $cleanStreak++
                $maximumCleanStreak = [math]::Max($maximumCleanStreak, $cleanStreak)
                if ($record.record_complete -ne $true) {
                    throw "Game at line $lineNumber is not marked complete."
                }
                $jobId = [string]$record.job_id
                if ([string]::IsNullOrWhiteSpace($jobId) -or -not $jobIds.Add($jobId)) {
                    throw "Game at line $lineNumber has a missing or duplicate job_id '$jobId'."
                }
                $openingId = [string]$record.opening_id
                $category = @(@('opening', 'endgame', 'edge') | Where-Object {
                    $openingId.StartsWith("$_-", [StringComparison]::Ordinal)
                })
                if ($category.Count -ne 1) {
                    throw "Game '$jobId' has an unclassified release position '$openingId'."
                }
                $categoryGames[$category[0]]++

                $openingMoves = @($record.opening_moves)
                $uciMoves = @($record.uci_moves)
                $sanMoves = @($record.san_moves)
                $fenTimeline = @($record.fen_timeline)
                $searches = @($record.searches)
                $plies = [int]$record.plies
                $openingPlies = [int]$record.opening_plies
                if (
                    $openingMoves.Count -ne $openingPlies -or
                    $uciMoves.Count -ne $plies -or
                    $sanMoves.Count -ne $plies -or
                    $fenTimeline.Count -ne ($plies + 1) -or
                    $fenTimeline[0] -cne [string]$record.initial_fen -or
                    $fenTimeline[-1] -cne [string]$record.final_fen -or
                    $searches.Count -ne ($plies - $openingPlies)
                ) {
                    throw "Game '$jobId' has inconsistent move, SAN, FEN, or search history."
                }
                for ($index = 0; $index -lt $openingMoves.Count; $index++) {
                    if ([string]$openingMoves[$index] -cne [string]$uciMoves[$index]) {
                        throw "Game '$jobId' opening prefix differs from its UCI history."
                    }
                }
                $initialFields = ([string]$record.initial_fen).Split(' ', [StringSplitOptions]::RemoveEmptyEntries)
                if ($initialFields.Count -ne 6 -or $initialFields[1] -notin @('w', 'b')) {
                    throw "Game '$jobId' has an invalid six-field initial FEN."
                }
                $initialWhite = $initialFields[1] -eq 'w'
                for ($index = 0; $index -lt $searches.Count; $index++) {
                    $search = $searches[$index]
                    $expectedPly = $openingPlies + $index + 1
                    $whiteToMove = if ((($expectedPly - 1) % 2) -eq 0) { $initialWhite } else { -not $initialWhite }
                    $expectedSide = if ($whiteToMove) { 'WHITE' } else { 'BLACK' }
                    $expectedCompetitor = if ($whiteToMove) { $record.white_competitor } else { $record.black_competitor }
                    $expectedStrength = if ($whiteToMove) { $record.white_strength } else { $record.black_strength }
                    if (
                        [int]$search.ply -ne $expectedPly -or
                        [string]$search.side -cne $expectedSide -or
                        [string]$search.competitor -cne [string]$expectedCompetitor -or
                        [string]$search.strength -cne [string]$expectedStrength
                    ) {
                        throw "Game '$jobId' has inconsistent search ownership at ply $expectedPly."
                    }
                }

                if ($record.censored -eq $true) {
                    if (
                        $null -ne $record.winner -or
                        $null -ne $record.loser -or
                        $null -ne $record.end_reason -or
                        $null -ne $record.adjudication_facts -or
                        $record.continuation_recommended -ne $true -or
                        $plies -ne [int]$record.max_plies
                    ) {
                        throw "Censored game '$jobId' carries an invalid terminal result."
                    }
                } elseif (
                    [string]$record.winner -notin @('WHITE', 'BLACK') -or
                    [string]$record.loser -notin @('WHITE', 'BLACK') -or
                    [string]$record.winner -ceq [string]$record.loser -or
                    [string]$record.end_reason -notin $allowedReasons -or
                    $null -eq $record.adjudication_facts -or
                    $record.continuation_recommended -ne $false
                ) {
                    throw "Completed game '$jobId' carries an invalid outcome."
                }

                if ($null -eq $record.pair_id) {
                    $sameLevelGames++
                    if (
                        $null -ne $record.pair_leg -or
                        [string]$record.white_level_id -cne [string]$record.black_level_id -or
                        [string]$record.white_competitor -cne [string]$record.black_competitor -or
                        [string]$record.white_strength -cne [string]$record.black_strength
                    ) {
                        throw "Release same-level game '$jobId' is malformed."
                    }
                } else {
                    $pairedGames++
                    $pairId = [string]$record.pair_id
                    if (-not $pairs.ContainsKey($pairId)) {
                        $pairs[$pairId] = @{}
                    }
                    $leg = [string]$record.pair_leg
                    if ($leg -notin @('lower-white', 'higher-white') -or $pairs[$pairId].ContainsKey($leg)) {
                        throw "Release pair '$pairId' has an invalid or duplicate leg '$leg'."
                    }
                    $pairs[$pairId][$leg] = [ordered]@{
                        opening = $openingId
                        matchup = [string]$record.matchup_id
                        whiteCompetitor = [string]$record.white_competitor
                        blackCompetitor = [string]$record.black_competitor
                        whiteLevel = [string]$record.white_level_id
                        blackLevel = [string]$record.black_level_id
                        whiteStrength = [string]$record.white_strength
                        blackStrength = [string]$record.black_strength
                    }
                }
            }
            'invocation_summary' {
                $summaries.Add($record)
            }
        }
    }
} finally {
    $reader.Dispose()
}

if ($headers -ne 1 -or $null -eq $header -or $fingerprints.Count -ne 1) {
    throw "Report must contain exactly one header and one run fingerprint."
}
$expectedConfig = [ordered]@{
    schemaVersion = '2'
    runLabel = 'release-100-diversified'
    jobSource = 'release-campaign'
    games = [string]$ExpectedGames
    parallelGames = '4'
    searchLimit = 'movetime:350'
    maxPlies = '300'
    variant = 'drawless'
    deadPosition = 'material_victory'
    fiftyMove = 'material_victory'
    bareKing = 'bare_king_loses'
    pairColors = 'true'
    markCappedForContinuation = 'true'
}
foreach ($entry in $expectedConfig.GetEnumerator()) {
    if ([string]$header.config[$entry.Key] -cne [string]$entry.Value) {
        throw "Release header config $($entry.Key)='$($header.config[$entry.Key])'; expected '$($entry.Value)'."
    }
}
if (
    -not $header.fixture_sha256.ContainsKey('release_positions') -or
    -not $header.fixture_sha256.ContainsKey('ladder_levels') -or
    -not $header.fixture_sha256.ContainsKey('adjacent_matchups')
) {
    throw 'Release header is missing a frozen fixture hash.'
}
if ($games -ne $ExpectedGames -or $jobIds.Count -ne $ExpectedGames -or $failures -ne 0) {
    throw "Release report has games=$games unique=$($jobIds.Count) failures=$failures; expected $ExpectedGames/zero."
}
if ($sameLevelGames -ne 3696 -or $pairedGames -ne 6336 -or $pairs.Count -ne 3168) {
    throw "Release matrix split is invalid: same=$sameLevelGames paired=$pairedGames pairs=$($pairs.Count)."
}
foreach ($entry in $pairs.GetEnumerator()) {
    $legs = $entry.Value
    if ($legs.Count -ne 2 -or -not $legs.ContainsKey('lower-white') -or -not $legs.ContainsKey('higher-white')) {
        throw "Release pair '$($entry.Key)' does not contain two complementary legs."
    }
    $lower = $legs['lower-white']
    $higher = $legs['higher-white']
    if (
        $lower.opening -cne $higher.opening -or
        $lower.matchup -cne $higher.matchup -or
        $lower.whiteCompetitor -cne $higher.blackCompetitor -or
        $lower.blackCompetitor -cne $higher.whiteCompetitor -or
        $lower.whiteLevel -cne $higher.blackLevel -or
        $lower.blackLevel -cne $higher.whiteLevel -or
        $lower.whiteStrength -cne $higher.blackStrength -or
        $lower.blackStrength -cne $higher.whiteStrength
    ) {
        throw "Release pair '$($entry.Key)' is not color-complementary."
    }
}
if ($categoryGames.opening -ne 5016 -or $categoryGames.endgame -ne 2508 -or $categoryGames.edge -ne 2508) {
    throw "Release category coverage is invalid: $($categoryGames | ConvertTo-Json -Compress)."
}
if ($cleanStreak -lt $RequiredCleanTail -or $games -lt 10000) {
    throw "Final clean streak is $cleanStreak games; required $RequiredCleanTail after at least 10,000 total."
}
if ($starts.Count -eq 0 -or $summaries.Count -eq 0) {
    throw 'Release report has no complete invocation evidence.'
}
if (@($summaries | Where-Object { $_.aborted -ne $false -or [int]$_.failures_this_invocation -ne 0 }).Count -ne 0) {
    throw 'Release report contains an aborted or failed invocation summary.'
}
if ($RequireZeroWorkResume) {
    $lastStart = $starts[-1]
    $lastSummary = $summaries[-1]
    if (
        $lastStart.invocation_id -cne $lastSummary.invocation_id -or
        [int]$lastStart.total_jobs -ne $ExpectedGames -or
        [int]$lastStart.pending_jobs -ne 0 -or
        [int]$lastStart.resumed_jobs -ne $ExpectedGames -or
        [int]$lastSummary.scheduled_this_invocation -ne 0 -or
        [int]$lastSummary.resumed_records_skipped -ne $ExpectedGames -or
        [int]$lastSummary.completed_this_invocation -ne 0 -or
        [int]$lastSummary.failures_this_invocation -ne 0 -or
        $lastSummary.aborted -ne $false
    ) {
        throw 'Release report did not pass its exact zero-work resume gate.'
    }
}

[ordered]@{
    status = 'passed'
    report = $report
    games = $games
    failures = $failures
    finalCleanStreak = $cleanStreak
    maximumCleanStreak = $maximumCleanStreak
    categories = $categoryGames
    sameLevelGames = $sameLevelGames
    adjacentColorLegs = $pairedGames
    adjacentPairs = $pairs.Count
    runFingerprint = @($fingerprints)[0]
    engineSha256 = [string]$header.engine_sha256
    runtimeSha256 = [string]$header.runtime_sha256
    variantsSha256 = [string]$header.variants_sha256
    releasePositionsSha256 = [string]$header.fixture_sha256.release_positions
    ladderLevelsSha256 = [string]$header.fixture_sha256.ladder_levels
    adjacentMatchupsSha256 = [string]$header.fixture_sha256.adjacent_matchups
    zeroWorkResume = [bool]$RequireZeroWorkResume
} | ConvertTo-Json -Depth 5
