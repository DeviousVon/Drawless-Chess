package com.drawlesschess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.drawlesschess.core.presentation.BoardTheme

@Composable
internal fun ThemePickerDialog(
    selectedTheme: BoardTheme,
    onSelect: (BoardTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("theme_picker"),
        onDismissRequest = onDismiss,
        title = { Text("Choose a theme") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Board, pieces, and menus update immediately. Your choice is saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DrawlessVisualThemes.all.forEach { visualTheme ->
                    ThemeOption(
                        visualTheme = visualTheme,
                        selected = visualTheme.boardTheme.id == selectedTheme.id,
                        onClick = { onSelect(visualTheme.boardTheme) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
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
                Text(visualTheme.boardTheme.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    visualTheme.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun ThemePreview(theme: BoardTheme) {
    val light = Color(theme.lightSquare.value)
    val dark = Color(theme.darkSquare.value)
    Canvas(
        Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp)),
    ) {
        val half = Size(size.width / 2f, size.height / 2f)
        drawRect(light, size = half)
        drawRect(dark, topLeft = Offset(half.width, 0f), size = half)
        drawRect(dark, topLeft = Offset(0f, half.height), size = half)
        drawRect(light, topLeft = Offset(half.width, half.height), size = half)
        drawCircle(
            color = Color(theme.selected.value),
            radius = size.minDimension * 0.12f,
            center = center,
        )
    }
}
