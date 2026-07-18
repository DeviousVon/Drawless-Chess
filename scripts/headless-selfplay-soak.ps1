[CmdletBinding(PositionalBinding = $false)]
param(
    [ValidateRange(0.001, 168.0)]
    [double] $MinimumHours = 3.0,

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string] $RunId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'),

    [string] $RunDirectory,

    [string] $SameLevelConfig = 'tools/selfplay/config/same-level-diagnostic.properties',

    [string] $AdjacentConfig = 'tools/selfplay/config/adjacent-diagnostic.properties',

    [ValidateRange(10, 720)]
    [int] $ChildTimeoutMinutes = 120,

    [switch] $AllowNonProductionConfig,

    [switch] $Resume
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'headless-selfplay-soak.ps1 requires 64-bit PowerShell 7 (pwsh).'
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runsRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build/headless/runs'))
$wrapperPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'headless-selfplay.ps1'))
$pwshPath = Join-Path $PSHOME 'pwsh.exe'
$utf8NoBom = [Text.UTF8Encoding]::new($false)

function Assert-PathUnderRoot {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $Description
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullRoot = [IO.Path]::GetFullPath($Root).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    $prefix = $fullRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description must remain under '$fullRoot': '$fullPath'."
    }
    return $fullPath
}

function Resolve-RepositoryFile {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Description
    )

    $candidate = if ([IO.Path]::IsPathRooted($Path)) {
        $Path
    } else {
        Join-Path $repositoryRoot $Path
    }
    $fullPath = Assert-PathUnderRoot -Path $candidate -Root $repositoryRoot -Description $Description
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "$Description is not a file: '$fullPath'."
    }
    return $fullPath
}

function ConvertTo-RepositoryArgument {
    param([Parameter(Mandatory)] [string] $Path)

    return [IO.Path]::GetRelativePath($repositoryRoot, $Path).Replace('\', '/')
}

function Write-LogLine {
    param([Parameter(Mandatory)] [string] $Message)

    $timestamp = (Get-Date).ToUniversalTime().ToString('o')
    Write-Output "[$timestamp] $Message"
}

if (-not (Test-Path -LiteralPath $wrapperPath -PathType Leaf)) {
    throw "Headless wrapper is missing: '$wrapperPath'."
}
if (-not (Test-Path -LiteralPath $pwshPath -PathType Leaf)) {
    throw "PowerShell 7 executable is missing: '$pwshPath'."
}

[IO.Directory]::CreateDirectory($runsRoot) | Out-Null
$runDirectoryCandidate = if ([string]::IsNullOrWhiteSpace($RunDirectory)) {
    Join-Path $runsRoot "soak-$RunId"
} elseif ([IO.Path]::IsPathRooted($RunDirectory)) {
    $RunDirectory
} else {
    Join-Path $repositoryRoot $RunDirectory
}
$runRoot = Assert-PathUnderRoot -Path $runDirectoryCandidate -Root $runsRoot -Description 'RunDirectory'
[IO.Directory]::CreateDirectory($runRoot) | Out-Null

$sameConfigPath = Resolve-RepositoryFile -Path $SameLevelConfig -Description 'SameLevelConfig'
$adjacentConfigPath = Resolve-RepositoryFile -Path $AdjacentConfig -Description 'AdjacentConfig'
$statePath = Join-Path $runRoot 'state.json'
$ledgerPath = Join-Path $runRoot 'ledger.jsonl'
$stopRequestPath = Join-Path $runRoot 'stop.request'
$lockPath = Join-Path $runsRoot 'selfplay-soak.lock'

$lockStream = $null
$ownsSupervisorLock = $false
$sleepPreventionEnabled = $false
$sameConfigArgument = ConvertTo-RepositoryArgument -Path $sameConfigPath
$adjacentConfigArgument = ConvertTo-RepositoryArgument -Path $adjacentConfigPath
$state = [ordered]@{
    schemaVersion = 1
    runId = $RunId
    status = 'starting'
    phase = 'initializing'
    minimumHours = $MinimumHours
    childTimeoutMinutes = $ChildTimeoutMinutes
    productionProfileRequired = -not [bool]$AllowNonProductionConfig
    sameLevelConfig = $sameConfigArgument
    adjacentConfig = $adjacentConfigArgument
    supervisorPid = $PID
    supervisorStartedAtUtc = (Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o')
    createdAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    soakStartedAtUtc = $null
    minimumEndAtUtc = $null
    lastHeartbeatUtc = (Get-Date).ToUniversalTime().ToString('o')
    completedRounds = 0
    completedMatrices = 0
    currentRound = $null
    currentMatrix = $null
    currentReport = $null
    currentReportBytes = 0
    childPid = $null
    childStartedAtUtc = $null
    baselineIdentity = $null
    reports = @()
    stoppedByRequest = $false
    finishedAtUtc = $null
    elapsedSeconds = 0
    selfPlaySeconds = 0
    resumeCount = 0
    evidenceClassification = 'stability soak; repeated fixed matrices are not independent strength samples'
    error = $null
}

function Write-State {
    $state.lastHeartbeatUtc = (Get-Date).ToUniversalTime().ToString('o')
    $json = $state | ConvertTo-Json -Depth 12
    $lastError = $null
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $temporaryPath = "$statePath.tmp.$PID.$attempt"
        try {
            $bytes = $utf8NoBom.GetBytes($json + [Environment]::NewLine)
            $stream = [IO.File]::Open(
                $temporaryPath,
                [IO.FileMode]::Create,
                [IO.FileAccess]::Write,
                [IO.FileShare]::None
            )
            try {
                $stream.Write($bytes, 0, $bytes.Length)
                $stream.Flush($true)
            } finally {
                $stream.Dispose()
            }
            if (Test-Path -LiteralPath $statePath -PathType Leaf) {
                [IO.File]::Move($temporaryPath, $statePath, $true)
            } else {
                [IO.File]::Move($temporaryPath, $statePath)
            }
            return
        } catch {
            $lastError = $_.Exception
            if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
                Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
            }
            if ($attempt -lt 5) {
                Start-Sleep -Milliseconds (100 * $attempt)
            }
        }
    }
    throw [IO.IOException]::new("Could not atomically persist soak state after five attempts.", $lastError)
}

function Write-LedgerRecord {
    param([Parameter(Mandatory)] [hashtable] $Record)

    $json = $Record | ConvertTo-Json -Compress -Depth 8
    [IO.File]::AppendAllText($ledgerPath, $json + [Environment]::NewLine, $utf8NoBom)
}

function Update-CurrentReportEvidence {
    if ($null -ne $state.currentReport -and (Test-Path -LiteralPath $state.currentReport -PathType Leaf)) {
        $state.currentReportBytes = (Get-Item -LiteralPath $state.currentReport).Length
    }
}

function Invoke-ChildPowerShell {
    param(
        [Parameter(Mandatory)] [string] $Purpose,
        [Parameter(Mandatory)] [string[]] $Arguments
    )

    $attemptLabel = '{0}-{1}' -f `
        (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ'), `
        ([guid]::NewGuid().ToString('N').Substring(0, 8))
    $safePurpose = ($Purpose -replace '[^A-Za-z0-9._-]+', '-').Trim('-')
    $stdoutPath = Join-Path $runRoot "$attemptLabel-$safePurpose.stdout.log"
    $stderrPath = Join-Path $runRoot "$attemptLabel-$safePurpose.stderr.log"
    $startUtc = (Get-Date).ToUniversalTime()
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $child = $null
    $stdoutTask = $null
    $stderrTask = $null
    $exitCode = $null
    $terminalError = $null
    $cleanupError = $null
    $timedOut = $false
    $confirmedExited = $false

    try {
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $pwshPath
        $startInfo.WorkingDirectory = $repositoryRoot
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        foreach ($argument in @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $wrapperPath) + $Arguments) {
            $startInfo.ArgumentList.Add($argument)
        }

        Write-LogLine "$Purpose starting."
        $child = [Diagnostics.Process]::new()
        $child.StartInfo = $startInfo
        if (-not $child.Start()) {
            throw "$Purpose could not start child PowerShell."
        }
        $stdoutTask = $child.StandardOutput.ReadToEndAsync()
        $stderrTask = $child.StandardError.ReadToEndAsync()
        $state.childPid = $child.Id
        $state.childStartedAtUtc = $child.StartTime.ToUniversalTime().ToString('o')
        Write-State

        while (-not $child.WaitForExit(15000)) {
            if ($stopwatch.Elapsed.TotalMinutes -ge $ChildTimeoutMinutes) {
                $timedOut = $true
                throw [TimeoutException]::new(
                    "$Purpose exceeded the $ChildTimeoutMinutes-minute child-process watchdog."
                )
            }
            Update-CurrentReportEvidence
            Write-State
        }
        $child.WaitForExit()
        $confirmedExited = $true
        $exitCode = $child.ExitCode
    } catch {
        $terminalError = $_.Exception
    } finally {
        if ($null -ne $child -and -not $confirmedExited) {
            try {
                if (-not $child.HasExited) {
                    $child.Kill($true)
                }
                if (-not $child.WaitForExit(30000)) {
                    throw "Child process tree did not exit within 30 seconds of termination."
                }
                $child.WaitForExit()
                $confirmedExited = $true
                $exitCode = $child.ExitCode
            } catch {
                $cleanupError = $_.Exception
            }
        }

        $stdout = ''
        $stderr = ''
        if ($confirmedExited) {
            try {
                if ($null -ne $stdoutTask) {
                    $stdout = $stdoutTask.GetAwaiter().GetResult()
                }
                if ($null -ne $stderrTask) {
                    $stderr = $stderrTask.GetAwaiter().GetResult()
                }
            } catch {
                if ($null -eq $cleanupError) {
                    $cleanupError = $_.Exception
                }
            }
        }
        [IO.File]::WriteAllText($stdoutPath, $stdout, $utf8NoBom)
        [IO.File]::WriteAllText($stderrPath, $stderr, $utf8NoBom)
        if (-not [string]::IsNullOrWhiteSpace($stdout)) {
            Write-Output $stdout.TrimEnd()
        }
        if (-not [string]::IsNullOrWhiteSpace($stderr)) {
            [Console]::Error.WriteLine($stderr.TrimEnd())
        }

        if ($confirmedExited) {
            $state.childPid = $null
            $state.childStartedAtUtc = $null
        }
        Update-CurrentReportEvidence
        try {
            Write-State
        } catch {
            if ($null -eq $terminalError) {
                $terminalError = $_.Exception
            }
        }
        if ($null -ne $child) {
            $child.Dispose()
        }
    }

    $stopwatch.Stop()
    $endUtc = (Get-Date).ToUniversalTime()
    try {
        Write-LedgerRecord -Record ([ordered]@{
            event = 'child_process'
            purpose = $Purpose
            startedAtUtc = $startUtc.ToString('o')
            finishedAtUtc = $endUtc.ToString('o')
            durationMs = $stopwatch.ElapsedMilliseconds
            exitCode = $exitCode
            timedOut = $timedOut
            confirmedExited = $confirmedExited
            stdout = $stdoutPath
            stderr = $stderrPath
            arguments = $Arguments
        })
    } catch {
        if ($null -eq $terminalError) {
            $terminalError = $_.Exception
        }
    }

    if ($null -ne $cleanupError) {
        if ($null -ne $terminalError) {
            throw [AggregateException]::new($terminalError, $cleanupError)
        }
        throw $cleanupError
    }
    if ($null -ne $terminalError) {
        throw $terminalError
    }
    Write-LogLine "$Purpose finished with exit code $exitCode after $([math]::Round($stopwatch.Elapsed.TotalSeconds, 1)) seconds."
    if ($exitCode -ne 0) {
        throw "$Purpose failed with exit code $exitCode. See '$stdoutPath' and '$stderrPath'."
    }
}

function Get-StrengthElo {
    param([Parameter(Mandatory)] [string] $Strength)

    if ($Strength -notmatch '^elo:([0-9]+)$') {
        throw "Expected an Elo-limited strength, got '$Strength'."
    }
    return [int]$Matches[1]
}

function Get-NearestRankPercentile {
    param(
        [Parameter(Mandatory)] [long[]] $Values,
        [Parameter(Mandatory)] [ValidateRange(0.0, 1.0)] [double] $Percentile
    )

    if ($Values.Count -eq 0) {
        return $null
    }
    $sorted = @($Values | Sort-Object)
    $index = [math]::Max(0, [math]::Ceiling($Percentile * $sorted.Count) - 1)
    return $sorted[$index]
}

function Read-ValidatedReportSummary {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [ValidateSet('same-level', 'adjacent')] [string] $Matrix,
        [Parameter(Mandatory)] [int] $ExpectedGames,
        [Parameter(Mandatory)] [bool] $RequireProductionProfile,
        [switch] $RequireZeroWorkResume
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Completed $Matrix report is missing: '$Path'."
    }
    $records = @(Get-Content -LiteralPath $Path -Encoding utf8 | ForEach-Object {
        $_ | ConvertFrom-Json -Depth 100
    })
    $headers = @($records | Where-Object { $_.event -eq 'run_header' })
    $starts = @($records | Where-Object { $_.event -eq 'invocation_started' })
    $games = @($records | Where-Object { $_.event -eq 'game' })
    $failures = @($records | Where-Object { $_.event -eq 'game_failure' })
    $summaries = @($records | Where-Object { $_.event -eq 'invocation_summary' })
    $fingerprints = @($records.run_fingerprint | Where-Object { $null -ne $_ } | Sort-Object -Unique)
    $uniqueJobs = @($games.job_id | Sort-Object -Unique)

    if ($headers.Count -ne 1 -or $fingerprints.Count -ne 1) {
        throw "$Matrix report must have exactly one header and fingerprint."
    }
    $header = $headers[0]
    if ($RequireProductionProfile) {
        $expectedConfig = [ordered]@{
            schemaVersion = '1'
            runLabel = if ($Matrix -eq 'adjacent') { 'diagnostic-adjacent-matrix' } else { 'diagnostic-same-level-matrix' }
            jobSource = $Matrix
            games = [string]$ExpectedGames
            parallelGames = '4'
            searchLimit = 'movetime:350'
            maxPlies = '300'
            variant = 'drawless'
            deadPosition = 'material_victory'
            fiftyMove = 'disabled'
            pairColors = if ($Matrix -eq 'adjacent') { 'true' } else { 'false' }
            markCappedForContinuation = 'true'
            whiteStrength = 'matrix'
            blackStrength = 'matrix'
            hashMb = '16'
            handshakeTimeoutMillis = '30000'
            readyTimeoutMillis = '30000'
            searchTimeoutMillis = '30000'
            stopGraceMillis = '2000'
            quitTimeoutMillis = '3000'
            initialFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'
        }
        foreach ($entry in $expectedConfig.GetEnumerator()) {
            $actual = $header.config.PSObject.Properties[$entry.Key].Value
            if ([string]$actual -cne [string]$entry.Value) {
                throw "$Matrix report is not the production profile: $($entry.Key)='$actual', expected '$($entry.Value)'."
            }
        }
        $expectedFixtureHashes = [ordered]@{
            openings = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $repositoryRoot 'tools/selfplay/fixtures/openings.tsv')).Hash.ToLowerInvariant()
            ladder_levels = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $repositoryRoot 'tools/selfplay/fixtures/ladder-levels.tsv')).Hash.ToLowerInvariant()
        }
        if ($Matrix -eq 'adjacent') {
            $expectedFixtureHashes.adjacent_matchups = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $repositoryRoot 'tools/selfplay/fixtures/adjacent-matchups.tsv')).Hash.ToLowerInvariant()
        }
        foreach ($entry in $expectedFixtureHashes.GetEnumerator()) {
            $actual = $header.fixture_sha256.PSObject.Properties[$entry.Key].Value
            if ([string]$actual -cne [string]$entry.Value) {
                throw "$Matrix report fixture hash '$($entry.Key)' does not match the production fixture."
            }
        }
    }

    if ($games.Count -ne $ExpectedGames -or $uniqueJobs.Count -ne $ExpectedGames) {
        throw "$Matrix report has $($games.Count) games and $($uniqueJobs.Count) unique jobs; expected $ExpectedGames."
    }
    if ($failures.Count -ne 0) {
        throw "$Matrix report contains $($failures.Count) game failures."
    }
    if ($starts.Count -eq 0 -or $summaries.Count -eq 0) {
        throw "$Matrix report has no complete invocation evidence."
    }
    $badSummaries = @($summaries | Where-Object {
        $_.aborted -ne $false -or [int]$_.failures_this_invocation -ne 0
    })
    $summarizedCompleted = ($summaries | Measure-Object -Property completed_this_invocation -Sum).Sum
    $summarizedCensored = ($summaries | Measure-Object -Property censored_this_invocation -Sum).Sum
    $censoredGames = @($games | Where-Object { $_.censored -eq $true })
    $uncensoredGames = @($games | Where-Object { $_.censored -ne $true })
    $summaryInvocationIds = @($summaries.invocation_id | Sort-Object -Unique)
    $unmatchedStarts = @($starts | Where-Object { $summaryInvocationIds -notcontains $_.invocation_id })
    if (
        $badSummaries.Count -ne 0 -or
        [int]$summarizedCompleted -gt $ExpectedGames -or
        [int]$summarizedCensored -gt $censoredGames.Count -or
        ($unmatchedStarts.Count -eq 0 -and [int]$summarizedCompleted -ne $ExpectedGames) -or
        ($unmatchedStarts.Count -eq 0 -and [int]$summarizedCensored -ne $censoredGames.Count)
    ) {
        throw "$Matrix report invocation summaries do not reconcile with its game records."
    }

    $lastStart = $starts[-1]
    $lastSummary = $summaries[-1]
    if ($RequireZeroWorkResume -and (
        $lastStart.invocation_id -ne $lastSummary.invocation_id -or
        [int]$lastStart.total_jobs -ne $ExpectedGames -or
        [int]$lastStart.pending_jobs -ne 0 -or
        [int]$lastStart.resumed_jobs -ne $ExpectedGames -or
        [int]$lastSummary.scheduled_this_invocation -ne 0 -or
        [int]$lastSummary.resumed_records_skipped -ne $ExpectedGames -or
        [int]$lastSummary.completed_this_invocation -ne 0 -or
        [int]$lastSummary.censored_this_invocation -ne 0 -or
        [int]$lastSummary.failures_this_invocation -ne 0 -or
        $lastSummary.aborted -ne $false
    )) {
        throw "$Matrix report did not pass its matched zero-work resume gate."
    }

    $allowedReasons = @('CHECKMATE', 'STALEMATE', 'REPETITION', 'DEAD_POSITION_MATERIAL')
    $invalidOutcomeJobs = @()
    $invalidHistoryJobs = @()
    foreach ($game in $games) {
        $winnerValid = $game.winner -in @('WHITE', 'BLACK')
        $loserValid = $game.loser -in @('WHITE', 'BLACK')
        $outcomeInvalid = $game.record_complete -ne $true
        if ($game.censored -eq $true) {
            $outcomeInvalid = $outcomeInvalid -or (
                $null -ne $game.winner -or
                $null -ne $game.loser -or
                $null -ne $game.end_reason -or
                $null -ne $game.adjudication_facts -or
                $game.continuation_recommended -ne $true -or
                [int]$game.plies -ne [int]$game.max_plies
            )
        } else {
            $outcomeInvalid = $outcomeInvalid -or (
                -not $winnerValid -or
                -not $loserValid -or
                $game.winner -eq $game.loser -or
                $null -eq $game.adjudication_facts -or
                $game.continuation_recommended -ne $false -or
                $allowedReasons -notcontains [string]$game.end_reason
            )
        }
        if ($outcomeInvalid) {
            $invalidOutcomeJobs += $game.job_id
        }

        $openingMoves = @($game.opening_moves)
        $uciMoves = @($game.uci_moves)
        $sanMoves = @($game.san_moves)
        $fenTimeline = @($game.fen_timeline)
        $searches = @($game.searches)
        $historyInvalid = (
            $openingMoves.Count -ne [int]$game.opening_plies -or
            $uciMoves.Count -ne [int]$game.plies -or
            $sanMoves.Count -ne [int]$game.plies -or
            $fenTimeline.Count -ne ([int]$game.plies + 1) -or
            $fenTimeline[0] -ne $game.initial_fen -or
            $fenTimeline[-1] -ne $game.final_fen -or
            $searches.Count -ne ([int]$game.plies - [int]$game.opening_plies)
        )
        if (-not $historyInvalid) {
            for ($index = 0; $index -lt $openingMoves.Count; $index++) {
                if ($openingMoves[$index] -ne $uciMoves[$index]) {
                    $historyInvalid = $true
                    break
                }
            }
        }
        if (-not $historyInvalid) {
            for ($index = 0; $index -lt $searches.Count; $index++) {
                $search = $searches[$index]
                $expectedPly = [int]$game.opening_plies + $index + 1
                $expectedSide = if (($expectedPly % 2) -eq 1) { 'WHITE' } else { 'BLACK' }
                $expectedCompetitor = if ($expectedSide -eq 'WHITE') { $game.white_competitor } else { $game.black_competitor }
                $expectedStrength = if ($expectedSide -eq 'WHITE') { $game.white_strength } else { $game.black_strength }
                if (
                    [int]$search.ply -ne $expectedPly -or
                    $search.side -ne $expectedSide -or
                    $search.competitor -ne $expectedCompetitor -or
                    $search.strength -ne $expectedStrength
                ) {
                    $historyInvalid = $true
                    break
                }
            }
        }
        if ($historyInvalid) {
            $invalidHistoryJobs += $game.job_id
        }
    }
    if ($invalidOutcomeJobs.Count -ne 0 -or $invalidHistoryJobs.Count -ne 0) {
        throw "$Matrix report has $($invalidOutcomeJobs.Count) invalid outcomes and $($invalidHistoryJobs.Count) invalid histories."
    }

    $openingIds = @($games.opening_id | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
    $matchupIds = @($games.matchup_id | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
    $cells = @($games | Group-Object { "$($_.opening_id)|$($_.matchup_id)" })
    $pairCount = 0
    $badPairCount = 0
    $pairs = @()
    if ($Matrix -eq 'same-level') {
        $badSameGames = @($games | Where-Object {
            $_.white_level_id -ne $_.black_level_id -or
            $_.white_competitor -ne $_.black_competitor -or
            $_.white_strength -ne $_.black_strength -or
            $null -ne $_.pair_id -or
            $null -ne $_.pair_leg
        })
        if (
            $openingIds.Count -ne 8 -or
            $matchupIds.Count -ne 7 -or
            $cells.Count -ne 56 -or
            @($cells | Where-Object { $_.Count -ne 1 }).Count -ne 0 -or
            $badSameGames.Count -ne 0
        ) {
            throw 'Same-level report is not the exact 7-level by 8-opening symmetric matrix.'
        }
    } else {
        $pairs = @($games | Group-Object pair_id)
        $pairCount = $pairs.Count
        $badPairs = @($pairs | Where-Object {
            if ($_.Count -ne 2 -or [string]::IsNullOrWhiteSpace($_.Name)) {
                return $true
            }
            $lowerWhite = @($_.Group | Where-Object { $_.pair_leg -eq 'lower-white' })
            $higherWhite = @($_.Group | Where-Object { $_.pair_leg -eq 'higher-white' })
            if ($lowerWhite.Count -ne 1 -or $higherWhite.Count -ne 1) {
                return $true
            }
            $lower = $lowerWhite[0]
            $higher = $higherWhite[0]
            return (
                $lower.white_competitor -ne $higher.black_competitor -or
                $lower.black_competitor -ne $higher.white_competitor -or
                $lower.white_level_id -ne $higher.black_level_id -or
                $lower.black_level_id -ne $higher.white_level_id -or
                $lower.white_strength -ne $higher.black_strength -or
                $lower.black_strength -ne $higher.white_strength -or
                (Get-StrengthElo $lower.white_strength) -ge (Get-StrengthElo $lower.black_strength) -or
                $lower.opening_id -ne $higher.opening_id -or
                $lower.matchup_id -ne $higher.matchup_id
            )
        })
        $badPairCount = $badPairs.Count
        if (
            $openingIds.Count -ne 8 -or
            $matchupIds.Count -ne 6 -or
            $cells.Count -ne 48 -or
            @($cells | Where-Object { $_.Count -ne 2 }).Count -ne 0 -or
            $pairCount -ne 48 -or
            $badPairCount -ne 0
        ) {
            throw 'Adjacent report is not the exact 6-matchup by 8-opening color-swapped matrix.'
        }
    }

    $gameInvocationSeconds = 0.0
    foreach ($summary in @($summaries | Where-Object { [int]$_.scheduled_this_invocation -gt 0 })) {
        $matchingStart = @($starts | Where-Object { $_.invocation_id -eq $summary.invocation_id })
        if ($matchingStart.Count -ne 1 -or [int]$matchingStart[0].pending_jobs -le 0) {
            throw "$Matrix report has unmatched game-bearing invocation evidence."
        }
        $durationMs = [long]$summary.finished_at_epoch_ms - [long]$matchingStart[0].started_at_epoch_ms
        if ($durationMs -lt 0) {
            throw "$Matrix report has a negative invocation duration."
        }
        $gameInvocationSeconds += $durationMs / 1000.0
    }

    $endReasons = [ordered]@{}
    foreach ($group in @($uncensoredGames | Group-Object end_reason | Sort-Object Name)) {
        $endReasons[$group.Name] = $group.Count
    }
    $whiteWins = @($uncensoredGames | Where-Object { $_.winner -eq 'WHITE' }).Count
    $blackWins = @($uncensoredGames | Where-Object { $_.winner -eq 'BLACK' }).Count
    $higherWins = 0
    $lowerWins = 0
    $fullyUncensoredPairs = 0
    $higherTwoZeroPairs = 0
    $splitPairs = 0
    $lowerTwoZeroPairs = 0
    if ($Matrix -eq 'adjacent') {
        foreach ($game in $uncensoredGames) {
            $winnerElo = if ($game.winner -eq 'WHITE') {
                Get-StrengthElo $game.white_strength
            } else {
                Get-StrengthElo $game.black_strength
            }
            $loserElo = if ($game.winner -eq 'WHITE') {
                Get-StrengthElo $game.black_strength
            } else {
                Get-StrengthElo $game.white_strength
            }
            if ($winnerElo -gt $loserElo) { $higherWins++ } else { $lowerWins++ }
        }
        foreach ($pair in $pairs) {
            if (@($pair.Group | Where-Object { $_.censored -eq $true }).Count -ne 0) {
                continue
            }
            $fullyUncensoredPairs++
            $pairHigherWins = 0
            foreach ($game in $pair.Group) {
                $winnerElo = if ($game.winner -eq 'WHITE') {
                    Get-StrengthElo $game.white_strength
                } else {
                    Get-StrengthElo $game.black_strength
                }
                $loserElo = if ($game.winner -eq 'WHITE') {
                    Get-StrengthElo $game.black_strength
                } else {
                    Get-StrengthElo $game.white_strength
                }
                if ($winnerElo -gt $loserElo) { $pairHigherWins++ }
            }
            switch ($pairHigherWins) {
                2 { $higherTwoZeroPairs++ }
                1 { $splitPairs++ }
                0 { $lowerTwoZeroPairs++ }
            }
        }
    }

    $elapsedValues = [long[]]@($games | ForEach-Object { [long]$_.elapsed_ms })
    return [ordered]@{
        report = $Path
        matrix = $Matrix
        fingerprint = $fingerprints[0]
        engineSha256 = $header.engine_sha256
        variantsSha256 = $header.variants_sha256
        runtimeSha256 = $header.runtime_sha256
        games = $games.Count
        uniqueJobs = $uniqueJobs.Count
        failures = $failures.Count
        censored = $censoredGames.Count
        uncensored = $uncensoredGames.Count
        pairs = $pairCount
        badPairs = $badPairCount
        fullyUncensoredPairs = $fullyUncensoredPairs
        whiteWins = $whiteWins
        blackWins = $blackWins
        higherWins = $higherWins
        lowerWins = $lowerWins
        higherTwoZeroPairs = $higherTwoZeroPairs
        splitPairs = $splitPairs
        lowerTwoZeroPairs = $lowerTwoZeroPairs
        endReasons = $endReasons
        elapsedMsP50 = Get-NearestRankPercentile -Values $elapsedValues -Percentile 0.50
        elapsedMsP95 = Get-NearestRankPercentile -Values $elapsedValues -Percentile 0.95
        gameInvocationSeconds = [math]::Round($gameInvocationSeconds, 3)
        strengthEvidence = if ($uncensoredGames.Count -eq 0) {
            'insufficient: all games censored'
        } else {
            'diagnostic only: fixed-opening repetitions are not independent samples'
        }
        bytes = (Get-Item -LiteralPath $Path).Length
        zeroWorkResume = [bool]$RequireZeroWorkResume
    }
}

function Test-RecordedProcessActive {
    param(
        [Parameter(Mandatory)] [int] $ProcessId,
        [Parameter(Mandatory)] [string] $StartedAtUtc
    )

    try {
        $process = Get-Process -Id $ProcessId -ErrorAction Stop
        $recorded = [DateTimeOffset]::Parse($StartedAtUtc).UtcDateTime
        $actual = $process.StartTime.ToUniversalTime()
        return [math]::Abs(($actual - $recorded).TotalSeconds) -lt 1.0
    } catch {
        return $false
    }
}

function Get-CurrentArtifactIdentity {
    $enginePath = Join-Path $repositoryRoot 'build/headless/linux-x86_64/drawless-fairy'
    $runnerPath = Join-Path $repositoryRoot 'build/headless/drawless-selfplay.jar'
    $variantsPath = Join-Path $repositoryRoot 'engine/variants.ini'
    foreach ($path in @($enginePath, $runnerPath, $variantsPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Soak identity input is missing: '$path'."
        }
    }
    return [ordered]@{
        engineSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $enginePath).Hash.ToLowerInvariant()
        variantsSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $variantsPath).Hash.ToLowerInvariant()
        runtimeSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $runnerPath).Hash.ToLowerInvariant()
    }
}

function Assert-IdentityMatches {
    param(
        [Parameter(Mandatory)] $Expected,
        [Parameter(Mandatory)] $Actual,
        [Parameter(Mandatory)] [string] $Description
    )

    foreach ($key in @('engineSha256', 'variantsSha256', 'runtimeSha256')) {
        if ([string]$Expected[$key] -cne [string]$Actual[$key]) {
            throw "$Description changed $key from '$($Expected[$key])' to '$($Actual[$key])'."
        }
    }
}

$stateOwnedByThisProcess = $false
try {
    try {
        $lockStream = [IO.File]::Open(
            $lockPath,
            [IO.FileMode]::OpenOrCreate,
            [IO.FileAccess]::ReadWrite,
            [IO.FileShare]::None
        )
        $ownsSupervisorLock = $true
    } catch {
        throw "Another headless soak supervisor owns '$lockPath'."
    }

    if ($Resume) {
        if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) {
            throw "Resume requested, but state.json is missing from '$runRoot'."
        }
        $loadedState = Get-Content -LiteralPath $statePath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable -Depth 20
        if (
            [int]$loadedState.schemaVersion -ne 1 -or
            [string]$loadedState.runId -cne $RunId -or
            [math]::Abs([double]$loadedState.minimumHours - $MinimumHours) -gt 0.0000001 -or
            [int]$loadedState.childTimeoutMinutes -ne $ChildTimeoutMinutes -or
            [bool]$loadedState.productionProfileRequired -ne (-not [bool]$AllowNonProductionConfig) -or
            [string]$loadedState.sameLevelConfig -cne $sameConfigArgument -or
            [string]$loadedState.adjacentConfig -cne $adjacentConfigArgument
        ) {
            throw 'Resume parameters do not match the recorded soak state.'
        }
        if ([string]$loadedState.status -eq 'completed') {
            throw "Soak '$RunId' is already completed."
        }
        if (Test-Path -LiteralPath $stopRequestPath -PathType Leaf) {
            throw "Remove '$stopRequestPath' before resuming this gracefully stopped soak."
        }
        if (
            $null -ne $loadedState.supervisorPid -and
            $null -ne $loadedState.supervisorStartedAtUtc -and
            (Test-RecordedProcessActive `
                -ProcessId ([int]$loadedState.supervisorPid) `
                -StartedAtUtc ([string]$loadedState.supervisorStartedAtUtc))
        ) {
            throw "The recorded supervisor for soak '$RunId' is still running."
        }
        if (
            $null -ne $loadedState.childPid -and
            $null -ne $loadedState.childStartedAtUtc -and
            (Test-RecordedProcessActive `
                -ProcessId ([int]$loadedState.childPid) `
                -StartedAtUtc ([string]$loadedState.childStartedAtUtc))
        ) {
            throw "The recorded child process for soak '$RunId' is still running; refusing concurrent resume."
        }
        $state = $loadedState
        $state.supervisorPid = $PID
        $state.supervisorStartedAtUtc = (Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o')
        $state.status = 'resuming'
        $state.phase = 'validating before resume'
        $state.resumeCount = [int]$state.resumeCount + 1
        $state.stoppedByRequest = $false
        $state.finishedAtUtc = $null
        $state.error = $null
        $state.childPid = $null
        $state.childStartedAtUtc = $null
        $stateOwnedByThisProcess = $true
        Write-State
    } else {
        if (Test-Path -LiteralPath $statePath -PathType Leaf) {
            throw "RunDirectory already contains state.json; use -Resume or choose a new run: '$runRoot'."
        }
        $stateOwnedByThisProcess = $true
        Write-State
    }

    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class DrawlessSoakPower {
    [DllImport("kernel32.dll")]
    public static extern uint SetThreadExecutionState(uint flags);
}
'@
    $executionState = [DrawlessSoakPower]::SetThreadExecutionState([uint32]2147483649)
    $sleepPreventionEnabled = $executionState -ne 0
    if (-not $sleepPreventionEnabled) {
        throw 'Windows refused the system-awake request for the soak supervisor.'
    }

    Write-LogLine "Soak '$RunId' initialized in '$runRoot' (resume=$([bool]$Resume))."
    $state.status = 'preflight'
    $state.phase = 'validating final engine and runner artifacts'
    Write-State
    Invoke-ChildPowerShell -Purpose 'full preflight validation' -Arguments @(
        '--no-build',
        '--validate-only'
    )

    $currentIdentity = Get-CurrentArtifactIdentity
    if ($Resume) {
        if ($null -eq $state.baselineIdentity) {
            throw 'Recorded soak state has no frozen engine/runner/variants identity.'
        }
        Assert-IdentityMatches `
            -Expected $state.baselineIdentity `
            -Actual $currentIdentity `
            -Description 'Current artifacts'

        $creditedReports = @($state.reports)
        $revalidatedReports = @()
        $seenReportPaths = [Collections.Generic.HashSet[string]]::new(
            [StringComparer]::OrdinalIgnoreCase
        )
        foreach ($creditedReport in $creditedReports) {
            $creditedPath = Assert-PathUnderRoot `
                -Path ([string]$creditedReport['report']) `
                -Root $runRoot `
                -Description 'Credited report'
            if (-not $seenReportPaths.Add($creditedPath)) {
                throw "Resume state credits the same report more than once: '$creditedPath'."
            }
            $creditedMatrix = [string]$creditedReport['matrix']
            if ($creditedMatrix -notin @('same-level', 'adjacent')) {
                if ([IO.Path]::GetFileName($creditedPath) -match '-(same-level|adjacent)\.jsonl$') {
                    $creditedMatrix = $Matches[1]
                } else {
                    throw "Cannot determine the matrix for credited report '$creditedPath'."
                }
            }
            $creditedExpected = if ($creditedMatrix -eq 'same-level') { 56 } else { 96 }
            $revalidated = Read-ValidatedReportSummary `
                -Path $creditedPath `
                -Matrix $creditedMatrix `
                -ExpectedGames $creditedExpected `
                -RequireProductionProfile:(-not [bool]$AllowNonProductionConfig) `
                -RequireZeroWorkResume
            $reportIdentity = [ordered]@{
                engineSha256 = $revalidated.engineSha256
                variantsSha256 = $revalidated.variantsSha256
                runtimeSha256 = $revalidated.runtimeSha256
            }
            Assert-IdentityMatches `
                -Expected $state.baselineIdentity `
                -Actual $reportIdentity `
                -Description "Credited report '$creditedPath'"
            $revalidatedReports += $revalidated
        }
        $state.reports = $revalidatedReports
        $state.completedMatrices = $revalidatedReports.Count
        $state.selfPlaySeconds = if ($revalidatedReports.Count -eq 0) {
            0.0
        } else {
            [math]::Round(
                ($revalidatedReports | Measure-Object -Property gameInvocationSeconds -Sum).Sum,
                3
            )
        }
        $completeRounds = 0
        while ($true) {
            $candidateRound = $completeRounds + 1
            $sameName = 'round-{0:D4}-same-level.jsonl' -f $candidateRound
            $adjacentName = 'round-{0:D4}-adjacent.jsonl' -f $candidateRound
            $names = @($revalidatedReports | ForEach-Object { [IO.Path]::GetFileName($_.report) })
            if ($names -contains $sameName -and $names -contains $adjacentName) {
                $completeRounds++
            } else {
                break
            }
        }
        $state.completedRounds = $completeRounds
        Write-State
    } else {
        $state.baselineIdentity = $currentIdentity
        Write-State
    }

    $state.status = 'running'
    $state.phase = 'production-budget matrices'
    if ($null -eq $state.soakStartedAtUtc) {
        $state.soakStartedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    }
    $minimumSelfPlaySeconds = $MinimumHours * 3600.0
    $remainingSelfPlaySeconds = [math]::Max(0.0, $minimumSelfPlaySeconds - [double]$state.selfPlaySeconds)
    $state.minimumEndAtUtc = (Get-Date).ToUniversalTime().AddSeconds($remainingSelfPlaySeconds).ToString('o')
    $priorElapsedSeconds = [double]$state.elapsedSeconds
    $soakStopwatch = [Diagnostics.Stopwatch]::StartNew()
    Write-State

    $matrices = @(
        [ordered]@{
            name = 'same-level'
            config = $sameConfigPath
            expectedGames = 56
        },
        [ordered]@{
            name = 'adjacent'
            config = $adjacentConfigPath
            expectedGames = 96
        }
    )
    $round = [int]$state.completedRounds
    do {
        $round++
        foreach ($matrix in $matrices) {
            if (Test-Path -LiteralPath $stopRequestPath -PathType Leaf) {
                $state.stoppedByRequest = $true
                break
            }

            $reportName = 'round-{0:D4}-{1}.jsonl' -f $round, $matrix.name
            $reportPath = Join-Path $runRoot $reportName
            $alreadyRecorded = @($state.reports | Where-Object {
                [string]$_.report -ieq $reportPath
            }).Count -eq 1
            if ($alreadyRecorded) {
                Write-LogLine "round $round $($matrix.name) already passed and was skipped during resume."
                continue
            }
            if ((Test-Path -LiteralPath $reportPath) -and -not $Resume) {
                throw "Refusing to overwrite an existing soak report: '$reportPath'."
            }
            $configArgument = ConvertTo-RepositoryArgument -Path $matrix.config
            $reportArgument = ConvertTo-RepositoryArgument -Path $reportPath
            $state.currentRound = $round
            $state.currentMatrix = $matrix.name
            $state.currentReport = $reportPath
            $state.currentReportBytes = 0
            Write-State

            Invoke-ChildPowerShell -Purpose "round $round $($matrix.name) matrix" -Arguments @(
                '--no-build',
                '--skip-runner-tests',
                '--config', $configArgument,
                '--output', $reportArgument
            )
            $null = Read-ValidatedReportSummary `
                -Path $reportPath `
                -Matrix $matrix.name `
                -ExpectedGames $matrix.expectedGames `
                -RequireProductionProfile:(-not [bool]$AllowNonProductionConfig)

            Invoke-ChildPowerShell -Purpose "round $round $($matrix.name) zero-work resume" -Arguments @(
                '--no-build',
                '--skip-runner-tests',
                '--config', $configArgument,
                '--output', $reportArgument
            )
            $reportSummary = Read-ValidatedReportSummary `
                -Path $reportPath `
                -Matrix $matrix.name `
                -ExpectedGames $matrix.expectedGames `
                -RequireProductionProfile:(-not [bool]$AllowNonProductionConfig) `
                -RequireZeroWorkResume
            $reportIdentity = [ordered]@{
                engineSha256 = $reportSummary.engineSha256
                variantsSha256 = $reportSummary.variantsSha256
                runtimeSha256 = $reportSummary.runtimeSha256
            }
            Assert-IdentityMatches `
                -Expected $state.baselineIdentity `
                -Actual $reportIdentity `
                -Description "round $round $($matrix.name) report"
            $state.reports += $reportSummary
            $state.completedMatrices = [int]$state.completedMatrices + 1
            $state.selfPlaySeconds = [math]::Round(
                [double]$state.selfPlaySeconds + [double]$reportSummary.gameInvocationSeconds,
                3
            )
            $state.elapsedSeconds = [math]::Round($priorElapsedSeconds + $soakStopwatch.Elapsed.TotalSeconds, 3)
            Write-State
        }
        if ($state.stoppedByRequest) {
            break
        }
        $state.completedRounds = $round
        $state.elapsedSeconds = [math]::Round($priorElapsedSeconds + $soakStopwatch.Elapsed.TotalSeconds, 3)
        Write-State
    } while ([double]$state.selfPlaySeconds -lt $minimumSelfPlaySeconds)

    $soakStopwatch.Stop()
    $finalIdentity = Get-CurrentArtifactIdentity
    Assert-IdentityMatches `
        -Expected $state.baselineIdentity `
        -Actual $finalIdentity `
        -Description 'Final current artifacts'
    $state.elapsedSeconds = [math]::Round($priorElapsedSeconds + $soakStopwatch.Elapsed.TotalSeconds, 3)
    $state.currentRound = $null
    $state.currentMatrix = $null
    $state.currentReport = $null
    $state.currentReportBytes = 0
    $state.status = if ($state.stoppedByRequest) { 'stopped' } else { 'completed' }
    $state.phase = if ($state.stoppedByRequest) { 'graceful stop requested' } else { 'minimum duration satisfied' }
    $state.finishedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    Write-State
    Write-LogLine "Soak '$RunId' $($state.status) after $($state.selfPlaySeconds) game-bearing seconds and $($state.completedMatrices) matrices."
} catch {
    $originalError = $_.Exception
    if ($stateOwnedByThisProcess) {
        try {
            $state.status = 'failed'
            $state.phase = 'failed'
            $state.error = $originalError.ToString()
            $state.finishedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
            if (Get-Variable -Name soakStopwatch -ErrorAction SilentlyContinue) {
                $state.elapsedSeconds = [math]::Round(
                    $priorElapsedSeconds + $soakStopwatch.Elapsed.TotalSeconds,
                    3
                )
            }
            Write-State
        } catch {
            $emergencyPath = Join-Path $runRoot 'state-write-failure.log'
            [IO.File]::AppendAllText(
                $emergencyPath,
                "$(Get-Date -Format o) original=$originalError state_write=$($_.Exception)" + [Environment]::NewLine,
                $utf8NoBom
            )
        }
    }
    [Console]::Error.WriteLine($originalError.ToString())
    exit 1
} finally {
    if ($sleepPreventionEnabled) {
        $null = [DrawlessSoakPower]::SetThreadExecutionState([uint32]2147483648)
    }
    if ($null -ne $lockStream) {
        $lockStream.Dispose()
    }
}
