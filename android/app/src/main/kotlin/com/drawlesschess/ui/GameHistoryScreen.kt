@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.drawlesschess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drawlesschess.R
import com.drawlesschess.core.Side
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.persistence.GameHistoryEntry
import com.drawlesschess.persistence.HistoricalReviewAvailability
import java.text.DateFormat
import java.util.Date

@Composable
internal fun GameHistoryScreen(
    state: GameHistoryState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenGame: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("history_back"),
                    ) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .testTag("game_history"),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                GameHistoryState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.testTag("history_loading"),
                )
                is GameHistoryState.Failed -> Column(
                    modifier = Modifier.widthIn(max = 560.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.history_unavailable_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(state.message.resolve())
                    Button(onClick = onRetry, modifier = Modifier.testTag("history_retry")) {
                        Text(stringResource(R.string.action_try_again))
                    }
                }
                is GameHistoryState.Ready -> if (state.entries.isEmpty()) {
                    Column(
                        modifier = Modifier.widthIn(max = 560.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.history_empty_title),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.history_empty_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                            .testTag("history_list"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = 12.dp,
                            bottom = 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.entries, key = { it.game.gameId }) { entry ->
                            GameHistoryRow(entry, onOpenGame)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameHistoryRow(
    entry: GameHistoryEntry,
    onOpenGame: (String) -> Unit,
) {
    val game = entry.game
    val result = stringResource(
        if (game.playerWon) R.string.history_result_win else R.string.history_result_loss,
    )
    val side = stringResource(
        if (game.playerSide == Side.WHITE) R.string.label_white else R.string.label_black,
    )
    val opponent = historyOpponentName(entry)
    val date = historyDate(game.completedAtEpochMillis)
    val reviewStatus = stringResource(
        when (entry.reviewAvailability) {
            HistoricalReviewAvailability.READY -> R.string.history_review_ready
            HistoricalReviewAvailability.NOT_ANALYZED -> R.string.history_review_not_analyzed
            HistoricalReviewAvailability.STALE -> R.string.history_review_update_needed
        },
    )
    val description = stringResource(
        R.string.history_row_accessibility,
        result,
        opponent,
        side,
        date,
        reviewStatus,
    )
    Card(
        onClick = { onOpenGame(game.gameId) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .testTag("history_game_${game.gameId}")
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    result,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    reviewStatus,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (entry.reviewAvailability) {
                        HistoricalReviewAvailability.READY -> MaterialTheme.colorScheme.primary
                        HistoricalReviewAvailability.NOT_ANALYZED,
                        HistoricalReviewAvailability.STALE -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                stringResource(R.string.history_opponent_and_side, opponent, side),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun historyOpponentName(entry: GameHistoryEntry): String {
    val game = entry.game
    val levelId = game.opponentStableId.removePrefix("bot:")
    val level = when (levelId) {
        BotDifficultyCatalog.ADAPTIVE_LEVEL_ID -> BotDifficultyCatalog.adaptiveLevel(
            game.opponentExactElo ?: BotDifficultyCatalog.ADAPTIVE_STARTING_ELO,
        )
        else -> BotDifficultyCatalog.namedOrNull(levelId)
    }
    if (level != null) {
        val profile = OpponentProfiles.forLevel(level)
        return stringResource(
            R.string.game_title_summary,
            opponentName(profile),
            botLevelName(level),
        )
    }
    return game.opponentExactElo?.let { elo ->
        stringResource(R.string.history_custom_opponent, elo)
    } ?: stringResource(R.string.history_unknown_opponent)
}

@Composable
private fun historyDate(epochMillis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    }
    return formatter.format(Date(epochMillis))
}
