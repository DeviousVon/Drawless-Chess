package com.drawlesschess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.drawlesschess.core.presentation.BoardTheme
import com.drawlesschess.R

@Composable
internal fun ThemePickerDialog(
    selectedTheme: BoardTheme,
    onSelect: (BoardTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            ThemePickerContent(
                selectedTheme = selectedTheme,
                onSelect = onSelect,
                onDismiss = onDismiss,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.94f),
            )
        }
    }
}

@Composable
internal fun ThemePickerContent(
    selectedTheme: BoardTheme,
    onSelect: (BoardTheme) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.theme_choose)
    val scrollState = rememberScrollState()
    Surface(
        modifier = modifier.testTag("theme_picker").semantics { paneTitle = title },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("theme_picker_close")) {
                    Text(stringResource(R.string.action_close))
                }
            }
            Box(Modifier.weight(1f, fill = false).padding(top = 12.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("theme_options_scroller")
                        .verticalScroll(scrollState)
                        .selectableGroup()
                        .padding(end = 12.dp),
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
                if (scrollState.maxValue > 0) {
                    ThemePickerScrollbar(scrollState, Modifier.matchParentSize())
                }
            }
        }
    }
}

@Composable
private fun ThemePickerScrollbar(scrollState: ScrollState, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.testTag("theme_scroll_indicator")) {
        val width = 4.dp.toPx()
        val thumbHeight = (size.height * size.height / (size.height + scrollState.maxValue))
            .coerceIn(minOf(24.dp.toPx(), size.height), size.height)
        val thumbTop = (size.height - thumbHeight) * scrollState.value /
            scrollState.maxValue.coerceAtLeast(1)
        val left = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width - width
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(left, 0f),
            size = Size(width, size.height),
            cornerRadius = CornerRadius(width / 2f),
        )
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(left, thumbTop),
            size = Size(width, thumbHeight),
            cornerRadius = CornerRadius(width / 2f),
        )
    }
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
        "celestial_observatory", "amethyst_geode" -> R.string.theme_celestial_observatory
        "halloween_emberwood", "all_hallows_court" -> R.string.theme_halloween_emberwood
        "halloween_witchglass" -> R.string.theme_halloween_witchglass
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
