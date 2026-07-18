package com.drawlesschess.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.drawlesschess.R
import com.drawlesschess.core.presentation.BoardTheme
import com.drawlesschess.core.presentation.BoardThemes

internal data class DrawlessPiecePalette(
    val whiteFill: Color,
    val whiteOutline: Color,
    val whiteDetail: Color,
    val whiteKingAccent: Color,
    val blackFill: Color,
    val blackOutline: Color,
    val blackDetail: Color,
    val blackKingAccent: Color,
)

internal data class DrawlessVisualTheme(
    val boardTheme: BoardTheme,
    @param:StringRes val descriptionRes: Int,
    val pieces: DrawlessPiecePalette,
)

internal object DrawlessVisualThemes {
    val GLACIER_SLATE = DrawlessVisualTheme(
        boardTheme = BoardThemes.GLACIER_SLATE,
        descriptionRes = R.string.theme_description_glacier_slate,
        pieces = DrawlessPiecePalette(
            Color(0xFFF5FCFF), Color(0xFF18313F), Color(0xFF5A7785), Color(0xFFD63E58),
            Color(0xFF102630), Color(0xFFD9F3FF), Color(0xFF89A9B8), Color(0xFF45D7D9),
        ),
    )

    val VERDIGRIS_COPPER = DrawlessVisualTheme(
        boardTheme = BoardThemes.VERDIGRIS_COPPER,
        descriptionRes = R.string.theme_description_verdigris_copper,
        pieces = DrawlessPiecePalette(
            Color(0xFFF8F0D9), Color(0xFF1D3531), Color(0xFF607E77), Color(0xFFC84C32),
            Color(0xFF102724), Color(0xFFF1E5CB), Color(0xFFA5BBB4), Color(0xFFE5A45D),
        ),
    )

    val AMETHYST_GEODE = DrawlessVisualTheme(
        boardTheme = BoardThemes.AMETHYST_GEODE,
        descriptionRes = R.string.theme_description_amethyst_geode,
        pieces = DrawlessPiecePalette(
            Color(0xFFFCF5E6), Color(0xFF2B1D38), Color(0xFF77648A), Color(0xFFC43E5C),
            Color(0xFF21162F), Color(0xFFEDE0F7), Color(0xFFBCA4D0), Color(0xFFFFD166),
        ),
    )

    val DESERT_SANDSTONE = DrawlessVisualTheme(
        boardTheme = BoardThemes.DESERT_SANDSTONE,
        descriptionRes = R.string.theme_description_desert_sandstone,
        pieces = DrawlessPiecePalette(
            Color(0xFFFFF5DD), Color(0xFF3A281D), Color(0xFF8A6A52), Color(0xFFC43B2E),
            Color(0xFF241710), Color(0xFFFFE4C7), Color(0xFFC89B78), Color(0xFF4DD8BD),
        ),
    )

    val IMPERIAL_MARBLE = DrawlessVisualTheme(
        boardTheme = BoardThemes.IMPERIAL_MARBLE,
        descriptionRes = R.string.theme_description_imperial_marble,
        pieces = DrawlessPiecePalette(
            Color(0xFFFFFCF2), Color(0xFF26332D), Color(0xFF738078), Color(0xFFAD3043),
            Color(0xFF111A16), Color(0xFFEAF1EC), Color(0xFF9FB0A6), Color(0xFFE9C349),
        ),
    )

    val DEFAULT = IMPERIAL_MARBLE
    val all = listOf(
        IMPERIAL_MARBLE,
        DESERT_SANDSTONE,
        GLACIER_SLATE,
        VERDIGRIS_COPPER,
        AMETHYST_GEODE,
    )

    init {
        check(all.map { it.boardTheme.id } == BoardThemes.all.map { it.id })
    }

    fun fromBoardTheme(theme: BoardTheme): DrawlessVisualTheme =
        all.firstOrNull { it.boardTheme.id == theme.id } ?: DEFAULT
}

// Board themes deliberately do not own these colors. The same app chrome is used on
// home, options, setup, statistics, and game controls regardless of board selection.
private val DrawlessAppLightColors = lightColorScheme(
    primary = Color(0xFF66561A), onPrimary = Color.White,
    primaryContainer = Color(0xFFEFE2AD), onPrimaryContainer = Color(0xFF201B00),
    secondary = Color(0xFF536348), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E8CA), onSecondaryContainer = Color(0xFF111F0C),
    tertiary = Color(0xFF85513B), onTertiary = Color.White,
    background = Color(0xFFF7F4EA), onBackground = Color(0xFF1B1D18),
    surface = Color(0xFFF7F4EA), onSurface = Color(0xFF1B1D18),
    surfaceVariant = Color(0xFFE2E6D8), onSurfaceVariant = Color(0xFF454A3F),
    surfaceDim = Color(0xFFD9D8CF), surfaceBright = Color(0xFFF7F4EA),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1EEE4),
    surfaceContainer = Color(0xFFEBE8DE), surfaceContainerHigh = Color(0xFFE5E2D8),
    surfaceContainerHighest = Color(0xFFDFDDD3), surfaceTint = Color(0xFF66561A),
    inverseSurface = Color(0xFF30332C), inverseOnSurface = Color(0xFFF3F4EC),
    inversePrimary = Color(0xFFC9B26F),
    outline = Color(0xFF757B6C), outlineVariant = Color(0xFFC4C9BA),
    scrim = Color.Black, error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val DrawlessAppDarkColors = darkColorScheme(
    primary = Color(0xFFC9B26F), onPrimary = Color(0xFF251D07),
    primaryContainer = Color(0xFF3B3421), onPrimaryContainer = Color(0xFFEFE1B6),
    secondary = Color(0xFFAEB89E), onSecondary = Color(0xFF1E2718),
    secondaryContainer = Color(0xFF35402F), onSecondaryContainer = Color(0xFFD7E8CA),
    tertiary = Color(0xFFC79D86), onTertiary = Color(0xFF33180D),
    background = Color(0xFF141812), onBackground = Color(0xFFF3F0E6),
    surface = Color(0xFF20261D), onSurface = Color(0xFFF3F0E6),
    surfaceVariant = Color(0xFF30382C), onSurfaceVariant = Color(0xFFB8BDAF),
    surfaceDim = Color(0xFF11150F), surfaceBright = Color(0xFF30362D),
    surfaceContainerLowest = Color(0xFF0F130D), surfaceContainerLow = Color(0xFF1A1F18),
    surfaceContainer = Color(0xFF20261D), surfaceContainerHigh = Color(0xFF283025),
    surfaceContainerHighest = Color(0xFF30382C), surfaceTint = Color(0xFFC9B26F),
    inverseSurface = Color(0xFFE5E7DE), inverseOnSurface = Color(0xFF2D302A),
    inversePrimary = Color(0xFF66561A),
    outline = Color(0xFF5D6657), outlineVariant = Color(0xFF454D40),
    scrim = Color.Black, error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

internal val LocalDrawlessVisualTheme = staticCompositionLocalOf { DrawlessVisualThemes.DEFAULT }

@Composable
fun DrawlessTheme(
    theme: BoardTheme = BoardThemes.DEFAULT,
    content: @Composable () -> Unit,
) {
    val visualTheme = DrawlessVisualThemes.fromBoardTheme(theme)
    CompositionLocalProvider(LocalDrawlessVisualTheme provides visualTheme) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) DrawlessAppDarkColors else DrawlessAppLightColors,
            typography = Typography(),
            content = content,
        )
    }
}
