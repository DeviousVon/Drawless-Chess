package com.drawlesschess.core.presentation

import com.drawlesschess.core.chess.Piece

@JvmInline
value class ArgbColor(val value: Long) {
    init { require(value in 0..0xFFFF_FFFFL) }
}

data class BoardTheme(
    val id: String,
    val lightSquare: ArgbColor,
    val darkSquare: ArgbColor,
    val selected: ArgbColor,
    val legalMove: ArgbColor,
    val legalCapture: ArgbColor,
    val lastMove: ArgbColor,
    val check: ArgbColor,
    val surface: ArgbColor,
    val onSurface: ArgbColor,
    val textureId: String? = null,
)

object BoardTextureIds {
    const val SANDSTONE = "sandstone"
    const val MARBLE = "marble"
    const val SLATE = "slate"
    const val VERDIGRIS = "verdigris"
    const val AMETHYST = "amethyst"
}

object BoardThemes {
    val DESERT_SANDSTONE = BoardTheme(
        id = "desert_sandstone",
        lightSquare = ArgbColor(0xFFE9D9B0),
        darkSquare = ArgbColor(0xFFB07E54),
        selected = ArgbColor(0xCC2E8B74),
        legalMove = ArgbColor(0x992E8B74),
        legalCapture = ArgbColor(0x99C34A33),
        lastMove = ArgbColor(0x88D9A441),
        check = ArgbColor(0xB3C43B2E),
        surface = ArgbColor(0xFF1C1410),
        onSurface = ArgbColor(0xFFF6EEDD),
        textureId = BoardTextureIds.SANDSTONE,
    )
    val IMPERIAL_MARBLE = BoardTheme(
        id = "imperial_marble",
        lightSquare = ArgbColor(0xFFF2F0EB),
        darkSquare = ArgbColor(0xFF344A3F),
        selected = ArgbColor(0xCCD4AF37),
        legalMove = ArgbColor(0x99C9A227),
        legalCapture = ArgbColor(0x99B03A48),
        lastMove = ArgbColor(0x88D4AF37),
        check = ArgbColor(0xB3B22B38),
        surface = ArgbColor(0xFF14171A),
        onSurface = ArgbColor(0xFFF4F2ED),
        textureId = BoardTextureIds.MARBLE,
    )
    val GLACIER_SLATE = BoardTheme(
        id = "glacier_slate",
        lightSquare = ArgbColor(0xFFE4EAF0),
        darkSquare = ArgbColor(0xFF61748A),
        selected = ArgbColor(0xCC2878D0),
        legalMove = ArgbColor(0x992878D0),
        legalCapture = ArgbColor(0x99D85D4A),
        lastMove = ArgbColor(0x88F2B84B),
        check = ArgbColor(0xB3D73B45),
        surface = ArgbColor(0xFF10161D),
        onSurface = ArgbColor(0xFFF0F4F8),
        textureId = BoardTextureIds.SLATE,
    )
    val VERDIGRIS_COPPER = BoardTheme(
        id = "verdigris_copper",
        lightSquare = ArgbColor(0xFFECE4D2),
        darkSquare = ArgbColor(0xFF356C67),
        selected = ArgbColor(0xCCD29A3A),
        legalMove = ArgbColor(0x99BD8A36),
        legalCapture = ArgbColor(0x99C34A33),
        lastMove = ArgbColor(0x88D8A24A),
        check = ArgbColor(0xB3C43B3A),
        surface = ArgbColor(0xFF0D1B1A),
        onSurface = ArgbColor(0xFFF1F2EA),
        textureId = BoardTextureIds.VERDIGRIS,
    )
    val AMETHYST_GEODE = BoardTheme(
        id = "amethyst_geode",
        lightSquare = ArgbColor(0xFFE3D9F0),
        darkSquare = ArgbColor(0xFF54406E),
        selected = ArgbColor(0xCCF1C75B),
        legalMove = ArgbColor(0x99C9A94E),
        legalCapture = ArgbColor(0x99E25A4F),
        lastMove = ArgbColor(0x88E9B949),
        check = ArgbColor(0xB3D9465F),
        surface = ArgbColor(0xFF171021),
        onSurface = ArgbColor(0xFFF6F1FF),
        textureId = BoardTextureIds.AMETHYST,
    )

    val DEFAULT = IMPERIAL_MARBLE
    val all = listOf(
        IMPERIAL_MARBLE,
        DESERT_SANDSTONE,
        GLACIER_SLATE,
        VERDIGRIS_COPPER,
        AMETHYST_GEODE,
    )

    /** Stable-id lookup for persisted presentation preferences, including retired themes. */
    fun fromId(id: String?): BoardTheme = when (id) {
        "malachite_court" -> VERDIGRIS_COPPER
        else -> all.firstOrNull { it.id == id } ?: DEFAULT
    }
}

data class PieceSet(
    val id: String,
    private val assetPrefix: String,
) {
    fun assetKey(piece: Piece): String = buildString {
        append(assetPrefix)
        append('_')
        append(piece.side.name.lowercase())
        append('_')
        append(piece.type.name.lowercase())
    }
}

object PieceSets {
    val MODERN_FLAT = PieceSet("modern_flat", "modern_flat")
    val GLASS = PieceSet("glass", "glass")
    val SCULPTED = PieceSet("sculpted", "sculpted")
    val all = listOf(MODERN_FLAT, GLASS, SCULPTED)
}
