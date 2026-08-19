[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [switch]$AllowSingleFinalAdjacent
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "Review audit file not found: $Path"
}

$events = @(
    Get-Content -LiteralPath $Path |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object {
            try {
                $_ | ConvertFrom-Json
            } catch {
                throw "Invalid JSONL review-audit record: $_"
            }
        }
)

if ($events.Count -eq 0) { throw 'Review audit file is empty' }

function Last-Event([string]$Name) {
    @($events | Where-Object event -eq $Name)[-1]
}

function Ply-List($Value) {
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) { return @() }
    @(([string]$Value).Split(',') | Where-Object { $_ -ne '' } | ForEach-Object { [int]$_ })
}

$terminal = Last-Event 'terminal_handoff_frozen'
$coverage = Last-Event 'seed_coverage_materialized'
$ready = Last-Event 'review_ready'
if ($null -eq $terminal) { throw 'Missing terminal_handoff_frozen record' }
if ($null -eq $coverage) { throw 'Missing seed_coverage_materialized record' }
if ($null -eq $ready) { throw 'Missing review_ready record' }

$expected = @(Ply-List $coverage.expectedPlies)
$exactSeeded = @(Ply-List $coverage.exactSeededPlies)
$materializable = @(Ply-List $coverage.materializablePlies)
$missingExact = @(Ply-List $coverage.missingExactPlies)
$missingAdjacent = @(Ply-List $coverage.missingAdjacentPlies)
$searches = @($events | Where-Object event -eq 'postgame_search_submitted')
$exactSearches = @($searches | Where-Object kind -eq 'exact_root')
$adjacentSearches = @($searches | Where-Object kind -eq 'adjacent_helper')

if ($missingExact.Count -ne 0) {
    throw "Foreground review missed exact player roots: $($missingExact -join ',')"
}
if ($exactSearches.Count -ne 0) {
    throw "Final review repeated exact-root searches at plies: $(@($exactSearches.ply) -join ',')"
}

$acceptedComponents = @{}
foreach ($event in @($events | Where-Object event -eq 'accepted')) {
    $componentKind = if ($event.kind -eq 'adjacent') { 'adjacent_helper' } else { 'exact_root' }
    $component = "$componentKind|$($event.positionId)|$($event.playedMove)"
    $acceptedComponents[$component] = $true
}
foreach ($search in $searches) {
    $component = "$($search.kind)|$($search.rootPositionId)|$($search.playedMove)"
    if ($acceptedComponents.ContainsKey($component)) {
        throw "Accepted foreground component was submitted again postgame: $component"
    }
}

if ($AllowSingleFinalAdjacent) {
    if ($adjacentSearches.Count -gt 1) {
        throw "More than one adjacent helper was submitted postgame: $($adjacentSearches.Count)"
    }
    if ($adjacentSearches.Count -eq 1 -and $expected.Count -gt 0) {
        $finalExpectedPly = ($expected | Measure-Object -Maximum).Maximum
        if ([int]$adjacentSearches[0].ply -ne $finalExpectedPly) {
            throw "The only permitted late adjacent helper was not for the final player decision"
        }
    }
} elseif ($searches.Count -ne 0) {
    throw "Review was not fully ready at terminal; postgame searches: $($searches.Count)"
}

$reviewOpened = Last-Event 'review-opened'
if ($null -ne $reviewOpened) {
    $openedAt = [int64]$reviewOpened.timestampEpochMillis
    $afterTap = @($searches | Where-Object { [int64]$_.timestampEpochMillis -ge $openedAt })
    if ($afterTap.Count -ne 0) {
        throw "Opening Review triggered $($afterTap.Count) engine search(es)"
    }
}

if ($expected.Count -ne $exactSeeded.Count) {
    throw "Exact seed coverage is $($exactSeeded.Count)/$($expected.Count)"
}
if ($missingAdjacent.Count -eq 0 -and $materializable.Count -ne $expected.Count) {
    throw "All evidence exists but only $($materializable.Count)/$($expected.Count) decisions materialized"
}

[pscustomobject]@{
    GameId = $ready.gameId
    ExpectedPlayerDecisions = $expected.Count
    ExactSeeded = $exactSeeded.Count
    MaterializableAtHandoff = $materializable.Count
    MissingAdjacentAtHandoff = $missingAdjacent.Count
    PostgameSearches = $searches.Count
    ReviewReady = $true
    DuplicateAcceptedSearches = 0
}
