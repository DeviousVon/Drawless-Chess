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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drawlesschess.persistence.OpponentStatistics
import com.drawlesschess.persistence.PlayerStatistics
import java.util.Locale

@Composable
internal fun PlayerStatsScreen(
    state: PlayerStatsState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Player stats") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        when (state) {
            PlayerStatsState.Loading -> StatsMessage(
                title = "Loading your stats…",
                body = "Getting your latest record ready.",
                modifier = Modifier.padding(padding),
            )
            is PlayerStatsState.Failed -> StatsMessage(
                title = "Stats unavailable",
                body = state.message,
                modifier = Modifier.padding(padding),
                action = { Button(onClick = onRetry) { Text("Try again") } },
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
                    if (statistics.completedGames == 0) "Your first result starts here"
                    else "${statistics.completedGames} completed games",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("stats_completed_games"),
                )
                MetricGrid(
                    MetricSpec(
                        label = "Record",
                        value = "${statistics.wins}–${statistics.losses}",
                        accessibilityValue = "${statistics.wins} wins, ${statistics.losses} losses",
                    ),
                    MetricSpec(
                        label = "Win rate",
                        value = statistics.winPercentage?.let { "${it.oneDecimal()}%" } ?: "—",
                        accessibilityValue = statistics.winPercentage
                            ?.let { "${it.oneDecimal()} percent" }
                            ?: "Not available",
                    ),
                    MetricSpec(
                        label = "Average game score",
                        value = statistics.averageScore?.oneDecimal() ?: "—",
                        accessibilityValue = statistics.averageScore?.oneDecimal() ?: "Not available",
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
                    "Momentum",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                MetricGrid(
                    MetricSpec(
                        "Current streak",
                        statistics.currentWinStreak.toString(),
                        "${statistics.currentWinStreak} consecutive wins",
                    ),
                    MetricSpec(
                        "Best streak",
                        statistics.bestWinStreak.toString(),
                        "${statistics.bestWinStreak} consecutive wins",
                    ),
                    MetricSpec(
                        "Unassisted wins",
                        statistics.unassistedWins.toString(),
                        "${statistics.unassistedWins} unassisted wins",
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
                    "About game score",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "A clean win scores 100. Successful hints and undos deduct 10 each; " +
                        "timed pauses and threat indication deduct 5. Losses score 0.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Game score is a motivational measure of clean wins—not a chess rating. " +
                        "Your average includes every completed game, including zero-point " +
                        "losses. Future online Elo will remain separate.",
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
                        "By opponent",
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
            "Stats are derived from completed games.",
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
    var metricModifier = modifier
    metric.testTag?.let { metricModifier = metricModifier.testTag(it) }
    metricModifier = metricModifier.clearAndSetSemantics {
        contentDescription = "${metric.label}: ${metric.accessibilityValue}"
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
    val title = profile?.let { "${it.name} · ${it.level.displayName}" }
        ?: opponent.opponentStableId.removePrefix("bot:").replace(':', ' ')
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            buildString {
                append("${opponent.completedGames} games · ${opponent.wins}–${opponent.losses}")
                opponent.opponentExactElo?.let {
                    if (profile == null) {
                        append(" · $it estimated Elo")
                    } else {
                        append(" · latest strength: $it estimated Elo")
                    }
                }
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "${opponent.winPercentage.oneDecimal()}% wins · Avg score ${opponent.averageScore.oneDecimal()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Double.oneDecimal(): String = String.format(Locale.US, "%.1f", this)
