package com.drawlesschess.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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

internal data class DrawlessHomePalette(
    val gradient: List<Color>,
    val title: Color,
    val subtitle: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
)

internal data class DrawlessVisualTheme(
    val boardTheme: BoardTheme,
    val description: String,
    val lightColors: ColorScheme,
    val darkColors: ColorScheme,
    val home: DrawlessHomePalette,
    val pieces: DrawlessPiecePalette,
)

internal object DrawlessVisualThemes {
    val OBSIDIAN_GLASS = DrawlessVisualTheme(
        boardTheme = BoardThemes.OBSIDIAN_GLASS,
        description = "Cool charcoal and mint",
        lightColors = lightColorScheme(
            primary = Color(0xFF236B58), onPrimary = Color.White,
            primaryContainer = Color(0xFFBFEBDD), onPrimaryContainer = Color(0xFF052019),
            secondary = Color(0xFF765B00), onSecondary = Color.White,
            secondaryContainer = Color(0xFFFFE08B), onSecondaryContainer = Color(0xFF241A00),
            background = Color(0xFFF4F7F8), onBackground = Color(0xFF172026),
            surface = Color.White, onSurface = Color(0xFF172026),
            surfaceVariant = Color(0xFFE1E9ED), onSurfaceVariant = Color(0xFF3F484D),
            outline = Color(0xFF6F797E), error = Color(0xFFB3261E), onError = Color.White,
            errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
        ),
        darkColors = darkColorScheme(
            primary = Color(0xFF56C7A5), onPrimary = Color(0xFF052019),
            primaryContainer = Color(0xFF164F41), onPrimaryContainer = Color(0xFFC0F2E2),
            secondary = Color(0xFFE4C75A), onSecondary = Color(0xFF3D3000),
            secondaryContainer = Color(0xFF554600), onSecondaryContainer = Color(0xFFFFE88B),
            background = Color(0xFF0C1216), onBackground = Color(0xFFF2F5F6),
            surface = Color(0xFF151E24), onSurface = Color(0xFFF2F5F6),
            surfaceVariant = Color(0xFF24323A), onSurfaceVariant = Color(0xFFBFC9CE),
            outline = Color(0xFF89949A), error = Color(0xFFFF806D), onError = Color(0xFF690005),
            errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
        ),
        home = DrawlessHomePalette(
            gradient = listOf(Color(0xFF0B1216), Color(0xFF17262C), Color(0xFF0D1519)),
            title = Color(0xFFF2F5F6), subtitle = Color(0xFFB7C7CF),
            muted = Color(0xFF9EAFB7), faint = Color(0xFF82949D),
            accent = Color(0xFF56C7A5), onAccent = Color(0xFF052019),
        ),
        pieces = DrawlessPiecePalette(
            Color(0xFFF8F1DD), Color(0xFF283238), Color(0xFF665D4E), Color(0xFFB3261E),
            Color(0xFF172126), Color(0xFFE8E1CF), Color(0xFFB9B19E), Color(0xFFFFC857),
        ),
    )

    val ARCTIC_SLATE = DrawlessVisualTheme(
        boardTheme = BoardThemes.ARCTIC_SLATE,
        description = "Crisp silver and blue",
        lightColors = lightColorScheme(
            primary = Color(0xFF00639A), onPrimary = Color.White,
            primaryContainer = Color(0xFFCDE5FF), onPrimaryContainer = Color(0xFF001D32),
            secondary = Color(0xFF51606F), onSecondary = Color.White,
            secondaryContainer = Color(0xFFD5E4F7), onSecondaryContainer = Color(0xFF0D1D2A),
            background = Color(0xFFF8F9FF), onBackground = Color(0xFF191C20),
            surface = Color.White, onSurface = Color(0xFF191C20),
            surfaceVariant = Color(0xFFDEE3EA), onSurfaceVariant = Color(0xFF42474E),
            outline = Color(0xFF72777F), error = Color(0xFFB3261E), onError = Color.White,
            errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
        ),
        darkColors = darkColorScheme(
            primary = Color(0xFF94CCFF), onPrimary = Color(0xFF003353),
            primaryContainer = Color(0xFF004A75), onPrimaryContainer = Color(0xFFCDE5FF),
            secondary = Color(0xFFB9C8DA), onSecondary = Color(0xFF23323F),
            secondaryContainer = Color(0xFF394956), onSecondaryContainer = Color(0xFFD5E4F7),
            background = Color(0xFF101418), onBackground = Color(0xFFE1E2E8),
            surface = Color(0xFF181C20), onSurface = Color(0xFFE1E2E8),
            surfaceVariant = Color(0xFF42474E), onSurfaceVariant = Color(0xFFC2C7CE),
            outline = Color(0xFF8C9199), error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        ),
        home = DrawlessHomePalette(
            gradient = listOf(Color(0xFF07131F), Color(0xFF0D2B3D), Color(0xFF091923)),
            title = Color(0xFFF0F8FF), subtitle = Color(0xFFB9D3E3),
            muted = Color(0xFF95B8CC), faint = Color(0xFF7798AB),
            accent = Color(0xFF94CCFF), onAccent = Color(0xFF003353),
        ),
        pieces = DrawlessPiecePalette(
            Color(0xFFF5FCFF), Color(0xFF18313F), Color(0xFF5A7785), Color(0xFFD63E58),
            Color(0xFF102630), Color(0xFFD9F3FF), Color(0xFF89A9B8), Color(0xFF45D7D9),
        ),
    )

    val MODERN_WALNUT = DrawlessVisualTheme(
        boardTheme = BoardThemes.MODERN_WALNUT,
        description = "Warm wood and teal",
        lightColors = lightColorScheme(
            primary = Color(0xFF93452F), onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDBD1), onPrimaryContainer = Color(0xFF3B0A02),
            secondary = Color(0xFF77574F), onSecondary = Color.White,
            secondaryContainer = Color(0xFFFFDAD1), onSecondaryContainer = Color(0xFF2C1510),
            background = Color(0xFFFFF8F6), onBackground = Color(0xFF241A18),
            surface = Color(0xFFFFF8F6), onSurface = Color(0xFF241A18),
            surfaceVariant = Color(0xFFF5DDD8), onSurfaceVariant = Color(0xFF53433F),
            outline = Color(0xFF85736E), error = Color(0xFFB3261E), onError = Color.White,
            errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
        ),
        darkColors = darkColorScheme(
            primary = Color(0xFFFFB4A2), onPrimary = Color(0xFF5D160B),
            primaryContainer = Color(0xFF76301F), onPrimaryContainer = Color(0xFFFFDAD1),
            secondary = Color(0xFFE7BDB3), onSecondary = Color(0xFF442A24),
            secondaryContainer = Color(0xFF5D4039), onSecondaryContainer = Color(0xFFFFDAD1),
            background = Color(0xFF1C100D), onBackground = Color(0xFFF5DED9),
            surface = Color(0xFF251915), onSurface = Color(0xFFF5DED9),
            surfaceVariant = Color(0xFF53433F), onSurfaceVariant = Color(0xFFD8C2BC),
            outline = Color(0xFFA08C86), error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        ),
        home = DrawlessHomePalette(
            gradient = listOf(Color(0xFF1A0D09), Color(0xFF3A1C13), Color(0xFF21100B)),
            title = Color(0xFFFFF3E8), subtitle = Color(0xFFE5C3B4),
            muted = Color(0xFFC69C8A), faint = Color(0xFFAD8372),
            accent = Color(0xFFFFB4A2), onAccent = Color(0xFF5D160B),
        ),
        pieces = DrawlessPiecePalette(
            Color(0xFFFFF3D8), Color(0xFF3B2118), Color(0xFF8B6555), Color(0xFFC5293D),
            Color(0xFF26140F), Color(0xFFFFE1C2), Color(0xFFC69A7F), Color(0xFF36C2B4),
        ),
    )

    val EMERALD_COURT = DrawlessVisualTheme(
        boardTheme = BoardThemes.EMERALD_COURT,
        description = "Deep green and gold",
        lightColors = lightColorScheme(
            primary = Color(0xFF386A20), onPrimary = Color.White,
            primaryContainer = Color(0xFFB7F397), onPrimaryContainer = Color(0xFF0A2100),
            secondary = Color(0xFF55624C), onSecondary = Color.White,
            secondaryContainer = Color(0xFFD8E7CB), onSecondaryContainer = Color(0xFF131F0D),
            background = Color(0xFFFDFDF5), onBackground = Color(0xFF1A1C18),
            surface = Color(0xFFFDFDF5), onSurface = Color(0xFF1A1C18),
            surfaceVariant = Color(0xFFE0E4D6), onSurfaceVariant = Color(0xFF44483F),
            outline = Color(0xFF74796E), error = Color(0xFFB3261E), onError = Color.White,
            errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
        ),
        darkColors = darkColorScheme(
            primary = Color(0xFF9CD67D), onPrimary = Color(0xFF103800),
            primaryContainer = Color(0xFF205107), onPrimaryContainer = Color(0xFFB7F397),
            secondary = Color(0xFFBCCBB0), onSecondary = Color(0xFF273420),
            secondaryContainer = Color(0xFF3D4A35), onSecondaryContainer = Color(0xFFD8E7CB),
            background = Color(0xFF12140F), onBackground = Color(0xFFE3E3DC),
            surface = Color(0xFF12140F), onSurface = Color(0xFFE3E3DC),
            surfaceVariant = Color(0xFF44483F), onSurfaceVariant = Color(0xFFC4C8BC),
            outline = Color(0xFF8E9288), error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        ),
        home = DrawlessHomePalette(
            gradient = listOf(Color(0xFF0B1710), Color(0xFF17301E), Color(0xFF0E1D13)),
            title = Color(0xFFF2F8E9), subtitle = Color(0xFFC3D2BA),
            muted = Color(0xFFA1B39A), faint = Color(0xFF84997F),
            accent = Color(0xFF9CD67D), onAccent = Color(0xFF103800),
        ),
        pieces = DrawlessPiecePalette(
            Color(0xFFF7F1D5), Color(0xFF26331F), Color(0xFF6B785C), Color(0xFFC84C32),
            Color(0xFF182019), Color(0xFFE8E3C6), Color(0xFFA8B397), Color(0xFFF3C35B),
        ),
    )

    val ROYAL_AMETHYST = DrawlessVisualTheme(
        boardTheme = BoardThemes.ROYAL_AMETHYST,
        description = "Purple dusk and coral",
        lightColors = lightColorScheme(
            primary = Color(0xFF6750A4), onPrimary = Color.White,
            primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
            secondary = Color(0xFF7D5260), onSecondary = Color.White,
            secondaryContainer = Color(0xFFFFD8E4), onSecondaryContainer = Color(0xFF31111D),
            background = Color(0xFFFFFBFE), onBackground = Color(0xFF1C1B1F),
            surface = Color(0xFFFFFBFE), onSurface = Color(0xFF1C1B1F),
            surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF49454F),
            outline = Color(0xFF79747E), error = Color(0xFFB3261E), onError = Color.White,
            errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
        ),
        darkColors = darkColorScheme(
            primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
            primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
            secondary = Color(0xFFEFB8C8), onSecondary = Color(0xFF492532),
            secondaryContainer = Color(0xFF633B48), onSecondaryContainer = Color(0xFFFFD8E4),
            background = Color(0xFF1C1B1F), onBackground = Color(0xFFE6E1E5),
            surface = Color(0xFF1C1B1F), onSurface = Color(0xFFE6E1E5),
            surfaceVariant = Color(0xFF49454F), onSurfaceVariant = Color(0xFFCAC4D0),
            outline = Color(0xFF938F99), error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        ),
        home = DrawlessHomePalette(
            gradient = listOf(Color(0xFF130D22), Color(0xFF2C1B43), Color(0xFF181027)),
            title = Color(0xFFF7F1FF), subtitle = Color(0xFFD3C4E5),
            muted = Color(0xFFB5A3C9), faint = Color(0xFF9583AA),
            accent = Color(0xFFD0BCFF), onAccent = Color(0xFF381E72),
        ),
        pieces = DrawlessPiecePalette(
            Color(0xFFFCF5E6), Color(0xFF2B1D38), Color(0xFF77648A), Color(0xFFC43E5C),
            Color(0xFF21162F), Color(0xFFEDE0F7), Color(0xFFBCA4D0), Color(0xFFFFD166),
        ),
    )

    val DEFAULT = OBSIDIAN_GLASS
    val all = listOf(OBSIDIAN_GLASS, ARCTIC_SLATE, MODERN_WALNUT, EMERALD_COURT, ROYAL_AMETHYST)

    init {
        check(all.map { it.boardTheme.id } == BoardThemes.all.map { it.id })
    }

    fun fromBoardTheme(theme: BoardTheme): DrawlessVisualTheme =
        all.firstOrNull { it.boardTheme.id == theme.id } ?: DEFAULT
}

internal val LocalDrawlessVisualTheme = staticCompositionLocalOf { DrawlessVisualThemes.DEFAULT }

@Composable
fun DrawlessTheme(
    theme: BoardTheme = BoardThemes.DEFAULT,
    content: @Composable () -> Unit,
) {
    val visualTheme = DrawlessVisualThemes.fromBoardTheme(theme)
    CompositionLocalProvider(LocalDrawlessVisualTheme provides visualTheme) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) visualTheme.darkColors else visualTheme.lightColors,
            typography = Typography(),
            content = content,
        )
    }
}
