@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.drawlesschess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun OptionsScreen(
    preferences: GamePreferences,
    onPreferencesChanged: (GamePreferences) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Options") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            OptionSection(
                title = "Audio",
                description = "Control the physical board and game-result sounds.",
            ) {
                PreferenceSwitchRow(
                    title = "Sound effects",
                    description = "Play move, capture, victory, and defeat sounds.",
                    checked = preferences.soundEnabled,
                    testTag = "option_sound_effects",
                    onCheckedChange = {
                        onPreferencesChanged(preferences.copy(soundEnabled = it))
                    },
                )
            }

            OptionSection(
                title = "Board",
                description = "Choose the guidance shown around and on the board.",
            ) {
                PreferenceSwitchRow(
                    title = "Board coordinates",
                    description = "Show rank and file labels along the board edges.",
                    checked = preferences.boardCoordinatesEnabled,
                    testTag = "option_board_coordinates",
                    onCheckedChange = {
                        onPreferencesChanged(preferences.copy(boardCoordinatesEnabled = it))
                    },
                )
                PreferenceSwitchRow(
                    title = "Threat indication",
                    description = "Highlight your pieces that are attacked by the opponent. " +
                        "This is assistance: a win scores 95 instead of 100 points and " +
                        "does not qualify for rated play. It applies to new games; a " +
                        "resumed game keeps the setting it started with.",
                    checked = preferences.threatIndicationEnabled,
                    testTag = "option_threat_indication",
                    onCheckedChange = {
                        onPreferencesChanged(preferences.copy(threatIndicationEnabled = it))
                    },
                )
            }

            OptionSection(
                title = "Game feedback",
                description = "Adjust result presentation without changing chess rules.",
            ) {
                PreferenceSwitchRow(
                    title = "Win/loss celebration effects",
                    description = "Show the full-screen victory or defeat animation and play " +
                        "its matching fireworks or glass cue. The Sound effects setting remains " +
                        "the master audio control. The result and score are always shown.",
                    checked = preferences.celebrationEffectsEnabled,
                    testTag = "option_celebration_effects",
                    onCheckedChange = {
                        onPreferencesChanged(preferences.copy(celebrationEffectsEnabled = it))
                    },
                )
            }

            Text(
                "Saved locally by Drawless Chess and not sent to BB_Games. Android backup may " +
                    "include these settings according to your device settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OptionSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ElevatedCard(shape = RoundedCornerShape(18.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
