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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.drawlesschess.BuildConfig
import com.drawlesschess.R
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
internal fun OptionsScreen(
    preferences: GamePreferences,
    onPreferencesChanged: (GamePreferences) -> Unit,
    onSoundVolumePreview: (Int) -> Unit,
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
                SoundVolumeRow(
                    value = preferences.soundVolumePercent,
                    enabled = preferences.soundEnabled,
                    onValueChange = {
                        onPreferencesChanged(preferences.copy(soundVolumePercent = it))
                    },
                    onPreview = onSoundVolumePreview,
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
                    title = stringResource(R.string.options_haptic_feedback),
                    description = stringResource(R.string.options_haptic_feedback_description),
                    checked = preferences.hapticFeedbackEnabled,
                    testTag = "option_haptic_feedback",
                    onCheckedChange = {
                        onPreferencesChanged(preferences.copy(hapticFeedbackEnabled = it))
                    },
                )
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
            Text(
                stringResource(
                    R.string.options_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                modifier = Modifier.testTag("options_version"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SoundVolumeRow(
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onPreview: (Int) -> Unit,
) {
    val normalized = value.coerceIn(MIN_SOUND_VOLUME_PERCENT, MAX_SOUND_VOLUME_PERCENT)
    val previewVolume = remember { mutableIntStateOf(normalized) }
    val previewRequest = remember { mutableIntStateOf(0) }
    LaunchedEffect(previewRequest.intValue) {
        if (previewRequest.intValue > 0) {
            delay(VOLUME_PREVIEW_SETTLE_MILLIS)
            onPreview(previewVolume.intValue)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("option_sound_volume")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.options_sound_volume),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.options_sound_volume_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.options_sound_volume_value, normalized),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = normalized.toFloat(),
            onValueChange = {
                val selected = (it / 10f).roundToInt() * 10
                previewVolume.intValue = selected
                previewRequest.intValue += 1
                onValueChange(selected)
            },
            modifier = Modifier.fillMaxWidth().testTag("option_sound_volume_slider"),
            enabled = enabled,
            valueRange = MIN_SOUND_VOLUME_PERCENT.toFloat()..MAX_SOUND_VOLUME_PERCENT.toFloat(),
            steps = 9,
        )
    }
}

private const val VOLUME_PREVIEW_SETTLE_MILLIS = 180L

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
