package com.drawlesschess.core.presentation

import com.drawlesschess.core.chess.Piece

@JvmInline
value class ArgbColor(val value: Long) {
    init { require(value in 0..0xFFFF_FFFFL) }
}

data class BoardTheme(
    val id: String,
    val name: String,
    val lightSquare: ArgbColor,
    val darkSquare: ArgbColor,
    val selected: ArgbColor,
    val legalMove: ArgbColor,
    val legalCapture: ArgbColor,
    val lastMove: ArgbColor,
    val check: ArgbColor,
    val surface: ArgbColor,
    val onSurface: ArgbColor,
)

object BoardThemes {
    val OBSIDIAN_GLASS = BoardTheme(
        "obsidian_glass", "Obsidian Glass",
        ArgbColor(0xFFB7C2C8), ArgbColor(0xFF34454F), ArgbColor(0xFF56C7A5),
        ArgbColor(0x9956C7A5), ArgbColor(0x99FF8066), ArgbColor(0x88E4C75A),
        ArgbColor(0xB3E34F4F), ArgbColor(0xFF11181D), ArgbColor(0xFFF2F5F6),
    )
    val ARCTIC_SLATE = BoardTheme(
        "arctic_slate", "Arctic Slate",
        ArgbColor(0xFFE8EDF2), ArgbColor(0xFF708399), ArgbColor(0xFF2878D0),
        ArgbColor(0x802878D0), ArgbColor(0x99D85D4A), ArgbColor(0x88F2B84B),
        ArgbColor(0xB3D73B45), ArgbColor(0xFFF5F7FA), ArgbColor(0xFF17202A),
    )
    val MODERN_WALNUT = BoardTheme(
        "modern_walnut", "Modern Walnut",
        ArgbColor(0xFFE5D1B4), ArgbColor(0xFF80563D), ArgbColor(0xFF3F8E7C),
        ArgbColor(0x883F8E7C), ArgbColor(0x99BC493E), ArgbColor(0x88E0A83A),
        ArgbColor(0xB3C83E4D), ArgbColor(0xFF211A17), ArgbColor(0xFFF7F0E8),
    )

    val all = listOf(OBSIDIAN_GLASS, ARCTIC_SLATE, MODERN_WALNUT)
}

data class PieceSet(
    val id: String,
    val name: String,
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
    val MODERN_FLAT = PieceSet("modern_flat", "Modern Flat", "modern_flat")
    val GLASS = PieceSet("glass", "Glass", "glass")
    val SCULPTED = PieceSet("sculpted", "Sculpted", "sculpted")
    val all = listOf(MODERN_FLAT, GLASS, SCULPTED)
}
