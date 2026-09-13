package com.drawlesschess.core.presentation

import kotlin.jvm.JvmInline

import com.drawlesschess.core.chess.Piece

@JvmInline
value class ArgbColor(val value: Long) {
    init { require(value in 0..0xFFFF_FFFFL) }
}

object PieceStyleIds {
    const val MODERN_FLAT = "modern_flat"
    const val ALL_HALLOWS = "all_hallows"
    const val CELESTIAL_OBSERVATORY = "celestial_observatory"
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
    val pieceStyleId: String = PieceStyleIds.MODERN_FLAT,
    val legalMoveOutline: ArgbColor = ArgbColor(0x00000000),
)

object BoardTextureIds {
    const val SANDSTONE = "sandstone"
    const val MARBLE = "marble"
    const val SLATE = "slate"
    const val VERDIGRIS = "verdigris"
    const val AMETHYST = "amethyst"
    const val ALL_HALLOWS = "all_hallows"
    const val EMBERWOOD = "emberwood"
    const val WITCHGLASS = "witchglass"
    const val CELESTIAL_OBSERVATORY = "celestial_observatory"
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
    val CELESTIAL_OBSERVATORY = BoardTheme(
        id = "celestial_observatory",
        lightSquare = ArgbColor(0xFFDED5C2),
        darkSquare = ArgbColor(0xFF152A3B),
        selected = ArgbColor(0xCCB88A3E),
        legalMove = ArgbColor(0xFFF0C96B),
        legalMoveOutline = ArgbColor(0xFF172637),
        legalCapture = ArgbColor(0xBBCB5353),
        lastMove = ArgbColor(0x8893B6C2),
        check = ArgbColor(0xCCB93646),
        surface = ArgbColor(0xFF0D1823),
        onSurface = ArgbColor(0xFFF7EEDC),
        textureId = BoardTextureIds.CELESTIAL_OBSERVATORY,
        pieceStyleId = PieceStyleIds.MODERN_FLAT,
    )
    /** Source compatibility for callers of the replaced purple theme. */
    val AMETHYST_GEODE = CELESTIAL_OBSERVATORY
    val HALLOWEEN_EMBERWOOD = BoardTheme(
        id = "halloween_emberwood",
        lightSquare = ArgbColor(0xFFC99658),
        darkSquare = ArgbColor(0xFF4B332A),
        selected = ArgbColor(0xCCA8D0C4),
        legalMove = ArgbColor(0xFFFF941F),
        legalMoveOutline = ArgbColor(0xFF11161C),
        legalCapture = ArgbColor(0x99E04E3F),
        lastMove = ArgbColor(0x888CB7C4),
        check = ArgbColor(0xB3E33535),
        surface = ArgbColor(0xFF11161C),
        onSurface = ArgbColor(0xFFF3E7D2),
        textureId = BoardTextureIds.EMBERWOOD,
        pieceStyleId = PieceStyleIds.ALL_HALLOWS,
    )

    val HALLOWEEN_WITCHGLASS = HALLOWEEN_EMBERWOOD.copy(
        id = "halloween_witchglass",
        lightSquare = ArgbColor(0xFFA6C2AF),
        darkSquare = ArgbColor(0xFF59425D),
        textureId = BoardTextureIds.WITCHGLASS,
        selected = ArgbColor(0xCCD9903D),
        lastMove = ArgbColor(0x88E6A23C),
    )
    /** Old Halloween selections adopt Emberwood during the two-board comparison. */
    val ALL_HALLOWS_COURT = HALLOWEEN_EMBERWOOD

    val DEFAULT = IMPERIAL_MARBLE
    val all = listOf(
        IMPERIAL_MARBLE,
        DESERT_SANDSTONE,
        GLACIER_SLATE,
        VERDIGRIS_COPPER,
        CELESTIAL_OBSERVATORY,
        HALLOWEEN_EMBERWOOD,
        HALLOWEEN_WITCHGLASS,
    )

    /** Stable-id lookup for persisted presentation preferences, including retired themes. */
    fun fromId(id: String?): BoardTheme = when (id) {
        "malachite_court" -> VERDIGRIS_COPPER
        "amethyst_geode" -> CELESTIAL_OBSERVATORY
        "all_hallows_court" -> HALLOWEEN_EMBERWOOD
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
