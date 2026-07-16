@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.drawlesschess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drawlesschess.persistence.OpponentStatistics
import com.drawlesschess.persistence.PlayerStatistics
import com.drawlesschess.R

@Composable
internal fun PlayerStatsScreen(
    state: PlayerStatsState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("stats_back")) {
                        Text(stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            PlayerStatsState.Loading -> StatsMessage(
                title = stringResource(R.string.stats_loading_title),
                body = stringResource(R.string.stats_loading_body),
                modifier = Modifier.padding(padding),
            )
            is PlayerStatsState.Failed -> StatsMessage(
                title = stringResource(R.string.stats_unavailable_title),
                body = state.message.resolve(),
                modifier = Modifier.padding(padding),
                action = { Button(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) } },
            )
            is PlayerStatsState.Ready -> PlayerStatsContent(
                statistics = state.statistics,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun StatsMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium)
        action?.let {
            Spacer(Modifier.height(16.dp))
            it()
        }
    }
}

@Composable
private fun PlayerStatsContent(
    statistics: PlayerStatistics,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .testTag("player_stats_content"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    statistics.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    if (statistics.completedGames == 0) stringResource(R.string.stats_first_result)
                    else pluralStringResource(
                        R.plurals.stats_completed_games,
                        statistics.completedGames,
                        statistics.completedGames,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("stats_completed_games"),
                )
                MetricGrid(
                    MetricSpec(
                        label = stringResource(R.string.stats_record),
                        value = stringResource(R.string.stats_record_value, statistics.wins, statistics.losses),
                        accessibilityValue = stringResource(
                            R.string.stats_record_accessibility,
                            statistics.wins,
                            statistics.losses,
                        ),
                    ),
                    MetricSpec(
                        label = stringResource(R.string.stats_win_rate),
                        value = statistics.winPercentage?.let {
                            stringResource(R.string.stats_percent, oneDecimal(it))
                        } ?: "—",
                        accessibilityValue = statistics.winPercentage
                            ?.let { stringResource(R.string.stats_percent_accessibility, oneDecimal(it)) }
                            ?: stringResource(R.string.label_not_available),
                    ),
                    MetricSpec(
                        label = stringResource(R.string.stats_average_game_score),
                        value = statistics.averageScore?.let { oneDecimal(it) } ?: "—",
                        accessibilityValue = statistics.averageScore?.let { oneDecimal(it) }
                            ?: stringResource(R.string.label_not_available),
                        testTag = "stats_average_score",
                    ),
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.stats_momentum),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                MetricGrid(
                    MetricSpec(
                        stringResource(R.string.stats_current_streak),
                        statistics.currentWinStreak.toString(),
                        pluralStringResource(
                            R.plurals.stats_consecutive_wins,
                            statistics.currentWinStreak,
                            statistics.currentWinStreak,
                        ),
                    ),
                    MetricSpec(
                        stringResource(R.string.stats_best_streak),
                        statistics.bestWinStreak.toString(),
                        pluralStringResource(
                            R.plurals.stats_consecutive_wins,
                            statistics.bestWinStreak,
                            statistics.bestWinStreak,
                        ),
                    ),
                    MetricSpec(
                        stringResource(R.string.stats_unassisted_wins),
                        statistics.unassistedWins.toString(),
                        pluralStringResource(
                            R.plurals.stats_unassisted_wins_accessibility,
                            statistics.unassistedWins,
                            statistics.unassistedWins,
                        ),
                    ),
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.stats_about_score),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.stats_score_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.stats_score_context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (statistics.opponents.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().testTag("stats_opponent_breakdown"),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(
                        stringResource(R.string.stats_by_opponent),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(6.dp))
                    statistics.opponents.forEachIndexed { index, opponent ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        OpponentStatsRow(opponent)
                    }
                }
            }
        }

        Text(
            stringResource(R.string.stats_source_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

private data class MetricSpec(
    val label: String,
    val value: String,
    val accessibilityValue: String,
    val testTag: String? = null,
)

@Composable
private fun MetricGrid(vararg metrics: MetricSpec) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 420.dp
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                metrics.forEach { metric ->
                    StatMetric(metric, compact = true, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                metrics.forEach { metric ->
                    StatMetric(metric, compact = false, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatMetric(metric: MetricSpec, compact: Boolean, modifier: Modifier = Modifier) {
    val metricDescription = stringResource(
        R.string.stats_metric_accessibility,
        metric.label,
        metric.accessibilityValue,
    )
    var metricModifier = modifier
    metric.testTag?.let { metricModifier = metricModifier.testTag(it) }
    metricModifier = metricModifier.clearAndSetSemantics {
        contentDescription = metricDescription
    }
    if (compact) {
        Row(
            modifier = metricModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                metric.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(metric.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    } else {
        Column(modifier = metricModifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(metric.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                metric.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OpponentStatsRow(opponent: OpponentStatistics) {
    val profile = opponent.opponentStableId
        .removePrefix("bot:")
        .takeIf { !it.startsWith("elo:") && !it.startsWith("skill:") }
        ?.let { id -> OpponentProfiles.all.singleOrNull { it.level.id == id } }
    val title = profile?.let {
        stringResource(R.string.game_title_summary, opponentName(it), botLevelName(it.level))
    }
        ?: opponent.opponentStableId.removePrefix("bot:").replace(':', ' ')
    val record = pluralStringResource(
        R.plurals.stats_opponent_games_record,
        opponent.completedGames,
        opponent.completedGames,
        opponent.wins,
        opponent.losses,
    )
    val elo = opponent.opponentExactElo?.let {
        stringResource(
            if (profile == null) R.string.stats_estimated_elo else R.string.stats_latest_strength,
            it,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(record, elo).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                R.string.stats_opponent_summary,
                oneDecimal(opponent.winPercentage),
                oneDecimal(opponent.averageScore),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
