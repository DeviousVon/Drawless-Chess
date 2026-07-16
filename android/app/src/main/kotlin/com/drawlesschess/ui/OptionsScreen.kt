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
import androidx.compose.ui.res.stringResource
import com.drawlesschess.R

@Composable
internal fun OptionsScreen(
    preferences: GamePreferences,
    onPreferencesChanged: (GamePreferences) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.options_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("options_back")) {
                        Text(stringResource(R.string.action_back))
                    }
                },
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
                title = stringResource(R.string.options_audio),
                description = stringResource(R.string.options_audio_description),
            ) {
                PreferenceSwitchRow(
                    title = stringResource(R.string.options_sound_effects),
                    description = stringResource(R.string.options_sound_effects_description),
                    checked = preferences.soundEnabled,
                    testTag = "option_sound_effects",
                    onCheckedChange = {
                        onPreferencesChanged(preferences.copy(soundEnabled = it))
                    },
                )
            }

            OptionSection(
                title = stringResource(R.string.options_board),
                description = stringResource(R.string.options_board_description),
            ) {
                PreferenceSwitchRow(
                    title = stringResource(R.string.options_board_coordinates),
                    description = stringResource(R.string.options_board_coordinates_description),
                    checked = preferences.boardCoordinatesEnabled,
                    testTag = "option_board_coordinates",
                    onCheckedChange = {
                        onPreferencesChanged(preferences.copy(boardCoordinatesEnabled = it))
                    },
                )
                PreferenceSwitchRow(
                    title = stringResource(R.string.options_threat_indication),
                    description = stringResource(R.string.options_threat_indication_description),
                    checked = preferences.threatIndicationEnabled,
                    testTag = "option_threat_indication",
                    onCheckedChange = {
                        onPreferencesChanged(preferences.copy(threatIndicationEnabled = it))
                    },
                )
            }

            OptionSection(
                title = stringResource(R.string.options_game_feedback),
                description = stringResource(R.string.options_game_feedback_description),
            ) {
                PreferenceSwitchRow(
                    title = stringResource(R.string.options_celebration_effects),
                    description = stringResource(R.string.options_celebration_effects_description),
                    checked = preferences.celebrationEffectsEnabled,
                    testTag = "option_celebration_effects",
                    onCheckedChange = {
                        onPreferencesChanged(preferences.copy(celebrationEffectsEnabled = it))
                    },
                )
            }

            Text(
                stringResource(R.string.options_local_storage_notice),
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
