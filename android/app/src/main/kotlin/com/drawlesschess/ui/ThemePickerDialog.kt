package com.drawlesschess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.drawlesschess.core.presentation.BoardTheme
import com.drawlesschess.R

@Composable
internal fun ThemePickerDialog(
    selectedTheme: BoardTheme,
    onSelect: (BoardTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("theme_picker"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_choose)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.theme_picker_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DrawlessVisualThemes.all.forEach { visualTheme ->
                    ThemeOption(
                        visualTheme = visualTheme,
                        selected = visualTheme.boardTheme.id == selectedTheme.id,
                        onClick = {
                            onSelect(visualTheme.boardTheme)
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ThemeOption(
    visualTheme: DrawlessVisualTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectionColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("theme_option_${visualTheme.boardTheme.id}")
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        color = selectionColor,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThemePreview(visualTheme.boardTheme)
            Column(Modifier.weight(1f)) {
                Text(themeName(visualTheme.boardTheme.id), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(visualTheme.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
internal fun themeName(themeId: String): String = stringResource(
    when (themeId) {
        "imperial_marble" -> R.string.theme_imperial_marble
        "desert_sandstone" -> R.string.theme_desert_sandstone
        "glacier_slate" -> R.string.theme_glacier_slate
        "verdigris_copper" -> R.string.theme_verdigris_copper
        "amethyst_geode" -> R.string.theme_amethyst_geode
        else -> R.string.theme_imperial_marble
    },
)

@Composable
private fun ThemePreview(theme: BoardTheme) {
    val light = Color(theme.lightSquare.value)
    val dark = Color(theme.darkSquare.value)
    val tile = 27.dp
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp)),
    ) {
        ThemePreviewTile(theme, light, true, 0, 1, Modifier.align(Alignment.TopStart).size(tile))
        ThemePreviewTile(theme, dark, false, 1, 1, Modifier.align(Alignment.TopEnd).size(tile))
        ThemePreviewTile(theme, dark, false, 0, 0, Modifier.align(Alignment.BottomStart).size(tile))
        ThemePreviewTile(theme, light, true, 1, 0, Modifier.align(Alignment.BottomEnd).size(tile))
        Box(
            Modifier
                .align(Alignment.Center)
                .size(13.dp)
                .background(Color(theme.selected.value), CircleShape),
        )
    }
}

@Composable
private fun ThemePreviewTile(
    theme: BoardTheme,
    color: Color,
    light: Boolean,
    file: Int,
    rank: Int,
    modifier: Modifier,
) {
    Box(
        modifier
            .background(color)
            .squareTexture(theme.textureId, light, file, rank),
    )
}
