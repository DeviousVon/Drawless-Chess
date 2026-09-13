import Foundation
import SwiftUI

#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
#endif

/// Apple rendering for the procedural board materials and project-owned chess art used by the
/// Android product. Both Halloween candidates share static high-resolution sculpture
/// atlases with the code-native renderer as a safe fallback. No font
/// glyphs or third-party piece assets are required, and every material cut stays deterministic.
struct BoardSquareSurface: View {
    let themeId: String
    let isLight: Bool
    let file: Int
    let rank: Int

    init(themeId: String, isLight: Bool, file: Int, rank: Int) {
        self.themeId = themeId
        self.isLight = isLight
        self.file = file
        self.rank = rank
    }

    init(themeId: String, isLight: Bool, square: String) {
        let bytes = Array(square.utf8)
        self.init(
            themeId: themeId,
            isLight: isLight,
            file: bytes.first.map { max(0, min(7, Int($0) - 97)) } ?? 0,
            rank: bytes.dropFirst().first.map { max(0, min(7, Int($0) - 49)) } ?? 0
        )
    }

    var body: some View {
        let theme = BoardVisualTheme.resolve(themeId)
        Canvas(opaque: true, colorMode: .nonLinear, rendersAsynchronously: true) { context, size in
            var random = StableVisualRandom(seed: StableVisualRandom.squareSeed(
                textureId: theme.texture.rawValue,
                file: file,
                rank: rank
            ))
            BoardTexturePainter.draw(
                theme: theme,
                isLight: isLight,
                in: context,
                size: size,
                random: &random
            )
        }
        .clipped()
    }
}

/// Engraved brass rim drawn inside the existing board bounds, without changing touch geometry.
struct CelestialBoardBezel: View {
    var body: some View {
        Canvas(opaque: false, colorMode: .nonLinear, rendersAsynchronously: true) { context, size in
            let side = min(size.width, size.height)
            let rimWidth = max(2.4, min(4.2, side * 0.010))
            let rect = CGRect(origin: .zero, size: size).insetBy(dx: rimWidth / 2, dy: rimWidth / 2)
            let rim = Path(roundedRect: rect, cornerRadius: max(2, 8 - rimWidth / 2))
            context.stroke(
                rim,
                with: .linearGradient(
                    Gradient(colors: [visualColor(0xECD79F), visualColor(0xA27A39), visualColor(0xD8B76E)]),
                    startPoint: .zero,
                    endPoint: CGPoint(x: size.width, y: size.height)
                ),
                lineWidth: rimWidth
            )
            let inner = Path(roundedRect: CGRect(origin: .zero, size: size)
                .insetBy(dx: rimWidth, dy: rimWidth), cornerRadius: 4)
            context.stroke(inner, with: .color(visualColor(0x152A3B, opacity: 0.8)), lineWidth: 0.7)

            var ticks = Path()
            for index in 1..<64 where index % 8 != 0 {
                let fraction = CGFloat(index) / 64
                let length = rimWidth * (index.isMultiple(of: 4) ? 0.70 : 0.40)
                let offset = rimWidth * 0.15
                let x = fraction * size.width
                let y = fraction * size.height
                ticks.move(to: CGPoint(x: x, y: offset))
                ticks.addLine(to: CGPoint(x: x, y: offset + length))
                ticks.move(to: CGPoint(x: x, y: size.height - offset))
                ticks.addLine(to: CGPoint(x: x, y: size.height - offset - length))
                ticks.move(to: CGPoint(x: offset, y: y))
                ticks.addLine(to: CGPoint(x: offset + length, y: y))
                ticks.move(to: CGPoint(x: size.width - offset, y: y))
                ticks.addLine(to: CGPoint(x: size.width - offset - length, y: y))
            }
            context.stroke(ticks, with: .color(visualColor(0x49371D, opacity: 0.75)), lineWidth: 0.55)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// A blackened iron rim occupies only the existing board edge; no padding or hit areas change.
struct HalloweenBoardBezel: View {
    let themeId: String

    var body: some View {
        Canvas(opaque: false, colorMode: .nonLinear, rendersAsynchronously: true) { context, size in
            let side = min(size.width, size.height)
            let rimWidth = max(1.2, side / 8 * (1.6 / 72))
            let glass = BoardVisualTheme.resolve(themeId).texture == .witchglass
            let rect = CGRect(origin: .zero, size: size).insetBy(dx: rimWidth / 2, dy: rimWidth / 2)
            let rim = Path(roundedRect: rect, cornerRadius: max(2, 8 - rimWidth / 2))
            context.stroke(rim, with: .linearGradient(
                Gradient(colors: [visualColor(glass ? 0x5C6960 : 0x6A4B37),
                                  visualColor(0x181A1B), visualColor(0x353938)]),
                startPoint: .zero, endPoint: CGPoint(x: size.width, y: size.height)),
                lineWidth: rimWidth)
            let inner = Path(roundedRect: CGRect(origin: .zero, size: size)
                .insetBy(dx: rimWidth, dy: rimWidth), cornerRadius: max(2, 8 - rimWidth))
            context.stroke(inner, with: .color(visualColor(glass ? 0x9BAC9B : 0xD96C2B,
                                                           opacity: glass ? 0.28 : 0.34)),
                           lineWidth: max(0.35, side / 8 * 0.005))
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct ChessPieceView: View {
    let pieceCode: String
    let themeId: String

    var body: some View {
        let bytes = Array(pieceCode.utf8)
        if bytes.count == 2,
           let type = CodeNativePiece(rawValue: bytes[1]) {
            let white = bytes[0] == Character("w").asciiValue
            let theme = BoardVisualTheme.resolve(themeId)
            Canvas(opaque: false, colorMode: .nonLinear, rendersAsynchronously: true) { context, size in
                if !SculpturePieceAtlasPainter.draw(
                    type: type, style: theme.pieceStyle, white: white, in: context, size: size
                ) {
                    CodeNativePiecePainter.draw(
                        type: type,
                        palette: theme.pieces,
                        style: theme.pieceStyle,
                        white: white,
                        in: context,
                        size: size
                    )
                }
            }
            .accessibilityHidden(true)
        }
    }
}

struct BoardVisualTheme {
    enum Texture: String {
        case sandstone
        case marble
        case slate
        case verdigris
        case celestial = "celestial_observatory"
        case emberwood = "halloween_emberwood"
        case witchglass = "halloween_witchglass"
    }

    enum PieceStyle {
        case classic
        case celestial
        case allHallows
    }

    struct PiecePalette {
        let whiteFill: Color
        let whiteOutline: Color
        let whiteDetail: Color
        let whiteKingAccent: Color
        let whiteQueenAccent: Color
        let blackFill: Color
        let blackOutline: Color
        let blackDetail: Color
        let blackKingAccent: Color
        let blackQueenAccent: Color
    }

    let id: String
    let texture: Texture
    let lightSquare: Color
    let darkSquare: Color
    let pieces: PiecePalette
    let pieceStyle: PieceStyle

    static func resolve(_ id: String) -> BoardVisualTheme {
        switch id {
        case "desert_sandstone":
            BoardVisualTheme(
                id: id,
                texture: .sandstone,
                lightSquare: visualColor(0xE9D9B0),
                darkSquare: visualColor(0xB07E54),
                pieces: PiecePalette(
                    whiteFill: visualColor(0xFFF5DD), whiteOutline: visualColor(0x3A281D),
                    whiteDetail: visualColor(0x8A6A52), whiteKingAccent: visualColor(0xC43B2E),
                    whiteQueenAccent: visualColor(0x4DD8BD),
                    blackFill: visualColor(0x241710), blackOutline: visualColor(0xFFE4C7),
                    blackDetail: visualColor(0xC89B78), blackKingAccent: visualColor(0x4DD8BD),
                    blackQueenAccent: visualColor(0xC43B2E)
                ),
                pieceStyle: .classic
            )
        case "glacier_slate":
            BoardVisualTheme(
                id: id,
                texture: .slate,
                lightSquare: visualColor(0xE4EAF0),
                darkSquare: visualColor(0x61748A),
                pieces: PiecePalette(
                    whiteFill: visualColor(0xF5FCFF), whiteOutline: visualColor(0x18313F),
                    whiteDetail: visualColor(0x5A7785), whiteKingAccent: visualColor(0xD63E58),
                    whiteQueenAccent: visualColor(0x45D7D9),
                    blackFill: visualColor(0x102630), blackOutline: visualColor(0xD9F3FF),
                    blackDetail: visualColor(0x89A9B8), blackKingAccent: visualColor(0x45D7D9),
                    blackQueenAccent: visualColor(0xD63E58)
                ),
                pieceStyle: .classic
            )
        case "verdigris_copper", "malachite_court":
            BoardVisualTheme(
                id: "verdigris_copper",
                texture: .verdigris,
                lightSquare: visualColor(0xECE4D2),
                darkSquare: visualColor(0x356C67),
                pieces: PiecePalette(
                    whiteFill: visualColor(0xF8F0D9), whiteOutline: visualColor(0x1D3531),
                    whiteDetail: visualColor(0x607E77), whiteKingAccent: visualColor(0xC84C32),
                    whiteQueenAccent: visualColor(0xE5A45D),
                    blackFill: visualColor(0x102724), blackOutline: visualColor(0xF1E5CB),
                    blackDetail: visualColor(0xA5BBB4), blackKingAccent: visualColor(0xE5A45D),
                    blackQueenAccent: visualColor(0xC84C32)
                ),
                pieceStyle: .classic
            )
        case "celestial_observatory", "amethyst_geode":
            BoardVisualTheme(
                id: "celestial_observatory",
                texture: .celestial,
                lightSquare: visualColor(0xDED5C2),
                darkSquare: visualColor(0x152A3B),
                pieces: PiecePalette(
                    whiteFill: visualColor(0xF8F0DA), whiteOutline: visualColor(0x172637),
                    whiteDetail: visualColor(0x9E7C40), whiteKingAccent: visualColor(0xB93646),
                    whiteQueenAccent: visualColor(0xC4923E),
                    blackFill: visualColor(0x152638), blackOutline: visualColor(0xF1DEAA),
                    blackDetail: visualColor(0xC5A366), blackKingAccent: visualColor(0xF0C96B),
                    blackQueenAccent: visualColor(0xB93646)
                ),
                pieceStyle: .classic
            )
        case "all_hallows_court", "halloween_emberwood", "halloween_witchglass":
            BoardVisualTheme(
                id: id == "all_hallows_court" ? "halloween_emberwood" : id,
                texture: id == "halloween_witchglass" ? .witchglass : .emberwood,
                lightSquare: visualColor(id == "halloween_witchglass" ? 0xA6C2AF : 0xC99658),
                darkSquare: visualColor(id == "halloween_witchglass" ? 0x59425D : 0x4B332A),
                pieces: PiecePalette(
                    whiteFill: visualColor(0xF3E7CF), whiteOutline: visualColor(0x261A2C),
                    whiteDetail: visualColor(0x8B5E3C), whiteKingAccent: visualColor(0xC85427),
                    whiteQueenAccent: visualColor(0xE78A2F),
                    blackFill: visualColor(0x17101E), blackOutline: visualColor(0xF2DFC0),
                    blackDetail: visualColor(0xB8A3C5), blackKingAccent: visualColor(0xF39A3E),
                    blackQueenAccent: visualColor(0xD75A28)
                ),
                pieceStyle: .allHallows
            )
        default:
            BoardVisualTheme(
                id: "imperial_marble",
                texture: .marble,
                lightSquare: visualColor(0xF2F0EB),
                darkSquare: visualColor(0x344A3F),
                pieces: PiecePalette(
                    whiteFill: visualColor(0xFFFCF2), whiteOutline: visualColor(0x26332D),
                    whiteDetail: visualColor(0x738078), whiteKingAccent: visualColor(0xAD3043),
                    whiteQueenAccent: visualColor(0xE9C349),
                    blackFill: visualColor(0x111A16), blackOutline: visualColor(0xEAF1EC),
                    blackDetail: visualColor(0x9FB0A6), blackKingAccent: visualColor(0xE9C349),
                    blackQueenAccent: visualColor(0xAD3043)
                ),
                pieceStyle: .classic
            )
        }
    }
}

private enum BoardTexturePainter {
    static func draw(
        theme: BoardVisualTheme,
        isLight: Bool,
        in context: GraphicsContext,
        size: CGSize,
        random: inout StableVisualRandom
    ) {
        switch theme.texture {
        case .sandstone: drawSandstone(isLight: isLight, in: context, size: size, random: &random)
        case .marble: drawMarble(isLight: isLight, in: context, size: size, random: &random)
        case .slate: drawSlate(isLight: isLight, in: context, size: size, random: &random)
        case .verdigris: drawVerdigris(isLight: isLight, in: context, size: size, random: &random)
        case .celestial: drawCelestial(isLight: isLight, in: context, size: size, random: &random)
        case .emberwood: drawEmberwood(isLight: isLight, in: context, size: size, random: &random)
        case .witchglass: drawWitchglass(isLight: isLight, in: context, size: size, random: &random)
        }
    }

    private static func drawSandstone(
        isLight: Bool,
        in context: GraphicsContext,
        size: CGSize,
        random: inout StableVisualRandom
    ) {
        fill(size, color: isLight ? visualColor(0xE9D9B0) : visualColor(0xB07E54), in: context)
        let side = min(size.width, size.height)
        for _ in 0..<random.int(3...5) {
            let y = random.unit() * size.height
            let thickness = side * random.cgFloat(0.05...0.16)
            let amplitude = side * random.cgFloat(0.012...0.04)
            let phase = random.cgFloat(0...(2 * .pi))
            let frequency = random.cgFloat(0.8...1.8) * 2 * .pi / max(side, 1)
            var band = Path()
            band.move(to: CGPoint(x: 0, y: y + amplitude * sin(phase)))
            let step = max(3, side / 18)
            var x: CGFloat = 0
            while x <= size.width {
                band.addLine(to: CGPoint(x: x, y: y + amplitude * sin(frequency * x + phase)))
                x += step
            }
            x = size.width
            while x >= 0 {
                band.addLine(to: CGPoint(x: x, y: y + amplitude * sin(frequency * x + phase) + thickness))
                x -= step
            }
            band.closeSubpath()
            let dark = random.bool(probability: 0.6)
            let color: Color = switch (isLight, dark) {
            case (true, true): visualColor(0xB08A5C, opacity: random.double(0.09...0.18))
            case (true, false): visualColor(0xF6ECCD, opacity: random.double(0.09...0.18))
            case (false, true): visualColor(0x805234, opacity: random.double(0.09...0.18))
            case (false, false): visualColor(0xCEA070, opacity: random.double(0.09...0.18))
            }
            context.fill(band, with: .color(color))
        }
        for _ in 0..<random.int(105...145) {
            let radius = side * random.cgFloat(0.003...0.009)
            let dark = random.bool(probability: 0.55)
            let color = dark
                ? visualColor(isLight ? 0x96764C : 0x785436, opacity: random.double(0.06...0.17))
                : visualColor(0xFFF6DC, opacity: random.double(0.05...0.12))
            context.fill(
                Path(ellipseIn: CGRect(
                    x: random.unit() * size.width,
                    y: random.unit() * size.height,
                    width: radius * 2,
                    height: radius * 2
                )),
                with: .color(color)
            )
        }
        for _ in 0..<random.int(3...7) {
            let radius = side * random.cgFloat(0.012...0.026)
            let center = CGPoint(
                x: random.cgFloat(0.08...0.92) * size.width,
                y: random.cgFloat(0.08...0.92) * size.height
            )
            context.fill(
                Path(ellipseIn: CGRect(
                    x: center.x - radius, y: center.y - radius,
                    width: radius * 2, height: radius * 2
                )),
                with: .color(visualColor(0x46301E, opacity: random.double(0.07...0.14)))
            )
        }
    }

    private static func drawMarble(
        isLight: Bool,
        in context: GraphicsContext,
        size: CGSize,
        random: inout StableVisualRandom
    ) {
        fill(size, color: isLight ? visualColor(0xF2F0EB) : visualColor(0x344A3F), in: context)
        let side = min(size.width, size.height)
        for _ in 0..<random.int(4...6) {
            let radius = side * random.cgFloat(0.2...0.5)
            let center = CGPoint(x: random.unit() * size.width, y: random.unit() * size.height)
            let tint = isLight
                ? (random.bool(probability: 0.7) ? 0xC4C6C8 : 0xDED8C8)
                : (random.bool(probability: 0.6) ? 0x26382F : 0x546E60)
            context.fill(
                Path(ellipseIn: CGRect(
                    x: center.x - radius, y: center.y - radius,
                    width: radius * 2, height: radius * 2
                )),
                with: .color(visualColor(UInt32(tint), opacity: 0.04))
            )
        }
        for veinIndex in 0..<random.int(2...3) {
            let fromTop = random.bool()
            let start = fromTop
                ? CGPoint(x: random.unit() * size.width, y: -side * 0.05)
                : CGPoint(x: -side * 0.05, y: random.unit() * size.height)
            let angle = fromTop
                ? CGFloat.pi / 2 + random.cgFloat(-0.9...0.9)
                : random.cgFloat(-0.4...0.45)
            let tint: UInt32 = isLight
                ? (random.bool(probability: 0.8) ? 0x96989E : 0xAC9876)
                : (random.bool(probability: 0.75) ? 0xD6DED2 : 0x96B29E)
            drawVein(
                start: start,
                angle: angle,
                steps: veinIndex == 0 ? random.int(48...66) : random.int(32...52),
                width: side * (veinIndex == 0 ? random.cgFloat(0.018...0.035) : random.cgFloat(0.009...0.018)),
                color: visualColor(tint, opacity: random.double(0.18...0.32)),
                wobble: 0.30,
                in: context,
                size: size,
                random: &random
            )
        }
        for _ in 0..<random.int(3...6) {
            drawVein(
                start: CGPoint(x: random.unit() * size.width, y: random.unit() * size.height),
                angle: random.cgFloat(0...(2 * .pi)),
                steps: random.int(8...18),
                width: max(0.45, side * 0.006),
                color: visualColor(isLight ? 0xA8AAAF : 0xBCC8BC, opacity: 0.12),
                wobble: 0.6,
                in: context,
                size: size,
                random: &random
            )
        }
    }

    private static func drawVein(
        start: CGPoint,
        angle initialAngle: CGFloat,
        steps: Int,
        width: CGFloat,
        color: Color,
        wobble: CGFloat,
        in context: GraphicsContext,
        size: CGSize,
        random: inout StableVisualRandom
    ) {
        var point = start
        var angle = initialAngle
        var path = Path()
        path.move(to: point)
        for _ in 0..<steps {
            angle += random.cgFloat(-wobble...wobble)
            let step = min(size.width, size.height) * random.cgFloat(0.018...0.036)
            point = CGPoint(x: point.x + step * cos(angle), y: point.y + step * sin(angle))
            path.addLine(to: point)
        }
        context.stroke(
            path,
            with: .color(color),
            style: StrokeStyle(lineWidth: max(0.45, width), lineCap: .round, lineJoin: .round)
        )
    }

    private static func drawSlate(
        isLight: Bool,
        in context: GraphicsContext,
        size: CGSize,
        random: inout StableVisualRandom
    ) {
        fill(size, color: isLight ? visualColor(0xE4EAF0) : visualColor(0x61748A), in: context)
        let side = min(size.width, size.height)
        for _ in 0..<random.int(3...5) {
            let width = side * random.cgFloat(0.28...0.70)
            let height = side * random.cgFloat(0.12...0.37)
            let tint = isLight
                ? (random.bool() ? 0xB8C5D0 : 0xF9FBFD)
                : (random.bool() ? 0x34485C : 0xA7B6C5)
            context.fill(
                Path(ellipseIn: CGRect(
                    x: random.cgFloat(-0.25...0.90) * size.width,
                    y: random.cgFloat(-0.20...0.95) * size.height,
                    width: width,
                    height: height
                )),
                with: .color(visualColor(UInt32(tint), opacity: 0.045))
            )
        }
        let slope = random.cgFloat(-0.06...0.06)
        for _ in 0..<random.int(26...38) {
            let x0 = random.unit() * size.width * 0.75
            let x1 = min(size.width, x0 + size.width * random.cgFloat(0.25...0.90))
            let y0 = random.unit() * size.height
            let y1 = y0 + slope * (x1 - x0) + random.cgFloat(-0.008...0.008) * side
            let dark = random.bool(probability: 0.62)
            let color: Color = switch (isLight, dark) {
            case (true, true): visualColor(0x52677A, opacity: random.double(0.06...0.17))
            case (true, false): Color.white.opacity(random.double(0.055...0.125))
            case (false, true): visualColor(0x243545, opacity: random.double(0.07...0.18))
            case (false, false): visualColor(0xB5C2CE, opacity: random.double(0.05...0.12))
            }
            strokeLine(
                from: CGPoint(x: x0, y: y0),
                to: CGPoint(x: x1, y: y1),
                color: color,
                width: random.bool(probability: 0.2) ? max(0.7, side * 0.008) : max(0.4, side * 0.004),
                in: context
            )
        }
        if random.bool(probability: 0.45) {
            var darkPath = Path()
            var x = side * random.cgFloat(0.15...0.85)
            var y: CGFloat = -2
            darkPath.move(to: CGPoint(x: x, y: y))
            while y < size.height + 2 {
                y += side * random.cgFloat(0.08...0.17)
                x = min(size.width + 3, max(-3, x + side * random.cgFloat(-0.08...0.08)))
                darkPath.addLine(to: CGPoint(x: x, y: y))
            }
            context.stroke(
                darkPath,
                with: .color(visualColor(0x182632, opacity: 0.16)),
                style: StrokeStyle(lineWidth: max(0.7, side * 0.008), lineCap: .round, lineJoin: .round)
            )
        }
        for _ in 0..<random.int(10...20) {
            let sparkle = max(0.5, side / 192)
            fillRect(
                CGRect(x: random.unit() * size.width, y: random.unit() * size.height, width: sparkle, height: sparkle),
                color: Color.white.opacity(random.double(0.12...0.35)),
                in: context
            )
        }
    }

    private static func drawVerdigris(
        isLight: Bool,
        in context: GraphicsContext,
        size: CGSize,
        random: inout StableVisualRandom
    ) {
        let side = min(size.width, size.height)
        if isLight {
            fill(size, color: visualColor(0xECE4D2), in: context)
            for _ in 0..<random.int(5...8) {
                let y = random.unit() * size.height
                fillRect(
                    CGRect(x: 0, y: y, width: size.width, height: side * random.cgFloat(0.025...0.10)),
                    color: visualColor(random.bool() ? 0xD6CAB0 : 0xF8F3E5, opacity: random.double(0.05...0.10)),
                    in: context
                )
            }
            for _ in 0..<random.int(60...100) {
                let dot = max(0.5, side / 192)
                context.fill(
                    Path(ellipseIn: CGRect(
                        x: random.unit() * size.width, y: random.unit() * size.height,
                        width: random.cgFloat(1.5...5) * dot,
                        height: random.cgFloat(0.7...1.6) * dot
                    )),
                    with: .color(visualColor(0x887A60, opacity: random.double(0.065...0.14)))
                )
            }
            for _ in 0..<random.int(40...70) {
                let dot = max(0.45, side / 192)
                fillRect(
                    CGRect(x: random.unit() * size.width, y: random.unit() * size.height, width: dot, height: dot),
                    color: Color.white.opacity(random.double(0.05...0.13)),
                    in: context
                )
            }
            return
        }

        fill(size, color: visualColor(0x356C67), in: context)
        let patina: [UInt32] = [0x173F3B, 0x285A56, 0x4E8179, 0x6A9288, 0x244D4B]
        for _ in 0..<random.int(14...22) {
            let width = side * random.cgFloat(0.18...0.66)
            let height = side * random.cgFloat(0.12...0.52)
            context.fill(
                Path(ellipseIn: CGRect(
                    x: random.cgFloat(-0.30...0.95) * size.width,
                    y: random.cgFloat(-0.30...0.95) * size.height,
                    width: width, height: height
                )),
                with: .color(visualColor(random.element(patina), opacity: random.double(0.025...0.08)))
            )
        }
        for _ in 0..<random.int(45...80) {
            let radius = max(0.35, side / 192) * random.cgFloat(0.35...1.2)
            context.fill(
                Path(ellipseIn: CGRect(
                    x: random.unit() * size.width, y: random.unit() * size.height,
                    width: radius * 2, height: radius * 2
                )),
                with: .color(visualColor(
                    random.bool(probability: 0.82) ? 0x123936 : 0xB87333,
                    opacity: random.double(0.04...0.13)
                ))
            )
        }
        for _ in 0..<random.int(8...14) {
            let start = CGPoint(x: random.unit() * size.width, y: random.unit() * size.height)
            let length = side * random.cgFloat(0.08...0.33)
            let angle = random.cgFloat(0...(2 * .pi))
            let end = CGPoint(x: start.x + cos(angle) * length, y: start.y + sin(angle) * length)
            var scratch = Path()
            scratch.move(to: start)
            scratch.addLine(to: CGPoint(
                x: start.x + (end.x - start.x) * 0.48 + random.cgFloat(-0.01...0.01) * side,
                y: start.y + (end.y - start.y) * 0.48 + random.cgFloat(-0.01...0.01) * side
            ))
            scratch.addLine(to: end)
            context.stroke(
                scratch,
                with: .color(visualColor(
                    random.bool() ? 0x0D302E : 0xB9D0C6,
                    opacity: random.double(0.045...0.10)
                )),
                style: StrokeStyle(lineWidth: max(0.4, side * 0.003), lineCap: .round)
            )
        }
        let sheen = random.cgFloat(-0.15...0.30) * side
        strokeLine(
            from: CGPoint(x: sheen, y: size.height),
            to: CGPoint(x: sheen + side * 0.7, y: 0),
            color: visualColor(0xD9EEE5, opacity: 0.022),
            width: side * random.cgFloat(0.10...0.16),
            in: context
        )
        for _ in 0..<random.int(12...25) {
            fillRect(
                CGRect(
                    x: random.unit() * size.width, y: random.unit() * size.height,
                    width: max(0.5, side / 192) * random.cgFloat(0.8...3),
                    height: max(0.45, side / 192)
                ),
                color: visualColor(0xCB8546, opacity: random.double(0.10...0.27)),
                in: context
            )
        }
    }

    private static func drawCelestial(
        isLight: Bool,
        in context: GraphicsContext,
        size: CGSize,
        random: inout StableVisualRandom
    ) {
        let side = min(size.width, size.height)
        let brass = visualColor(0xB99A5C)
        fill(size, color: isLight ? visualColor(0xDED5C2) : visualColor(0x152A3B), in: context)

        // Soft mineral clouds in the alabaster and a restrained blue enamel glaze. Nothing
        // animates or resembles a move target; the engraving stays below the piece contrast.
        for _ in 0..<6 {
            let ellipse = Path(ellipseIn: CGRect(
                x: random.cgFloat(-0.25...0.85) * size.width,
                y: random.cgFloat(-0.20...0.90) * size.height,
                width: side * random.cgFloat(0.30...0.80),
                height: side * random.cgFloat(0.10...0.32)
            ))
            context.fill(ellipse, with: .color(visualColor(
                isLight ? 0xFFF8E5 : 0x7190AA,
                opacity: random.double(isLight ? 0.045...0.085 : 0.018...0.035)
            )))
        }
        context.fill(
            Path(CGRect(origin: .zero, size: size)),
            with: .linearGradient(
                Gradient(colors: [Color.white.opacity(isLight ? 0.07 : 0.035), .clear, .black.opacity(0.04)]),
                startPoint: .zero,
                endPoint: CGPoint(x: size.width, y: size.height)
            )
        )

        let chartCenter = CGPoint(
            x: side * random.cgFloat(-0.12...0.10),
            y: size.height * random.cgFloat(0.95...1.20)
        )
        let chartRadius = side * random.cgFloat(0.72...1.03)
        for ring in 0..<2 {
            var arc = Path()
            arc.addArc(
                center: chartCenter,
                radius: chartRadius + CGFloat(ring) * side * 0.12,
                startAngle: .degrees(-88), endAngle: .degrees(-3), clockwise: false
            )
            context.stroke(
                arc, with: .color(brass.opacity(isLight ? 0.20 : 0.14)),
                lineWidth: max(0.35, side * 0.004)
            )
        }
        for tick in 0..<5 {
            let angle = CGFloat(-78 + tick * 16) * .pi / 180
            let length = side * (tick.isMultiple(of: 2) ? 0.035 : 0.018)
            strokeLine(
                from: CGPoint(x: chartCenter.x + cos(angle) * chartRadius,
                              y: chartCenter.y + sin(angle) * chartRadius),
                to: CGPoint(x: chartCenter.x + cos(angle) * (chartRadius + length),
                            y: chartCenter.y + sin(angle) * (chartRadius + length)),
                color: brass.opacity(isLight ? 0.22 : 0.17),
                width: max(0.35, side * 0.004), in: context
            )
        }

        if !isLight {
            let points = (0..<4).map { _ in
                CGPoint(x: random.cgFloat(0.16...0.86) * size.width,
                        y: random.cgFloat(0.14...0.78) * size.height)
            }
            var constellation = Path()
            constellation.move(to: points[0])
            for point in points.dropFirst() { constellation.addLine(to: point) }
            context.stroke(
                constellation, with: .color(visualColor(0xAAB5B8, opacity: 0.12)),
                lineWidth: max(0.3, side * 0.0035)
            )
            for point in points {
                let radius = side * random.cgFloat(0.007...0.011)
                context.fill(
                    Path(ellipseIn: CGRect(x: point.x - radius, y: point.y - radius,
                                           width: radius * 2, height: radius * 2)),
                    with: .color(visualColor(0xECD8A1, opacity: 0.34))
                )
            }
        }

        // A shallow lip distinguishes individual inlaid panels at any device scale.
        strokeLine(from: CGPoint(x: 0, y: 0.5), to: CGPoint(x: size.width, y: 0.5),
                   color: Color.white.opacity(isLight ? 0.16 : 0.07),
                   width: max(0.5, side * 0.007), in: context)
        strokeLine(from: CGPoint(x: 0, y: size.height - 0.5),
                   to: CGPoint(x: size.width, y: size.height - 0.5),
                   color: Color.black.opacity(0.13),
                   width: max(0.5, side * 0.007), in: context)
    }

    /// Quiet grain and pores stay below the piece silhouettes and semantic move markers.
    private static func drawEmberwood(
        isLight: Bool,
        in context: GraphicsContext,
        size: CGSize,
        random: inout StableVisualRandom
    ) {
        let side = min(size.width, size.height)
        fill(size, color: visualColor(isLight ? 0xC99658 : 0x4B332A), in: context)
        context.fill(Path(CGRect(origin: .zero, size: size)), with: .linearGradient(
            Gradient(colors: [visualColor(0xF8D194, opacity: 0.08), .clear,
                              visualColor(0x201611, opacity: 0.08)]),
            startPoint: .zero, endPoint: CGPoint(x: size.width, y: size.height)
        ))
        if isLight {
            let center = CGPoint(x: side * random.cgFloat(-0.18...0.18),
                                 y: side * random.cgFloat(-0.12...0.26))
            let phase = random.cgFloat(0...(2 * .pi))
            for ring in 0..<20 {
                let radius = side * (0.07 + CGFloat(ring) * 0.073)
                var path = Path()
                for sample in 0...68 {
                    let angle = CGFloat(sample) / 68 * 2 * .pi
                    let wobble = 1 + 0.024 * sin(angle * 5 + phase)
                        + 0.015 * sin(angle * 9 - phase)
                    let point = CGPoint(x: center.x + cos(angle) * radius * wobble,
                                        y: center.y + sin(angle) * radius * wobble * 0.89)
                    if sample == 0 { path.move(to: point) } else { path.addLine(to: point) }
                }
                context.stroke(path, with: .color(visualColor(0x70431F, opacity: random.double(0.10...0.16))),
                               lineWidth: side * random.cgFloat(0.004...0.008))
            }
        } else {
            let phase = random.cgFloat(0...(2 * .pi))
            for grain in 0..<28 {
                let offset = (CGFloat(grain) / 27 * 1.8 - 0.4) * side
                var path = Path()
                for sample in 0...20 {
                    let y = CGFloat(sample) / 20 * size.height
                    let x = offset + y * 0.48 + sin(y / max(side, 1) * 9 + phase) * side * 0.018
                    let point = CGPoint(x: x, y: y)
                    if sample == 0 { path.move(to: point) } else { path.addLine(to: point) }
                }
                context.stroke(path, with: .color(visualColor(
                    grain.isMultiple(of: 4) ? 0x9A714C : 0x171010,
                    opacity: random.double(0.12...0.20))),
                    lineWidth: side * random.cgFloat(0.006...0.014))
            }
        }
        for _ in 0..<20 {
            let radius = side * random.cgFloat(0.0025...0.006)
            context.fill(Path(ellipseIn: CGRect(
                x: random.unit() * size.width, y: random.unit() * size.height,
                width: radius * 2, height: radius)),
                with: .color(visualColor(isLight ? 0x4B2B18 : 0xC19B6E, opacity: random.double(0.09...0.17))))
        }
        for _ in 0..<2 {
            let start = CGPoint(x: random.unit() * size.width, y: random.unit() * size.height)
            var fissure = Path()
            fissure.move(to: start)
            fissure.addLine(to: CGPoint(x: start.x + side * 0.025, y: start.y + side * 0.055))
            fissure.addLine(to: CGPoint(x: start.x + side * 0.018, y: start.y + side * 0.095))
            context.stroke(fissure, with: .color(visualColor(0x20120E, opacity: 0.19)),
                           lineWidth: side * 0.005)
        }
        drawHalloweenSquareLip(isLight: isLight, glass: false, in: context, size: size)
    }

    /// Satin glass: broad low-contrast clouds, submerged swirls, and sparse edge-biased bubbles.
    private static func drawWitchglass(
        isLight: Bool,
        in context: GraphicsContext,
        size: CGSize,
        random: inout StableVisualRandom
    ) {
        let side = min(size.width, size.height)
        fill(size, color: visualColor(isLight ? 0xA6C2AF : 0x59425D), in: context)
        for cloud in 0..<5 {
            let center = CGPoint(x: random.cgFloat(-0.15...1.05) * size.width,
                                 y: random.cgFloat(-0.10...1.10) * size.height)
            let radius = side * random.cgFloat(0.34...0.70)
            let tint: UInt32 = isLight
                ? (cloud.isMultiple(of: 2) ? 0xE4EDCC : 0x4F8375)
                : (cloud.isMultiple(of: 2) ? 0xBB91BA : 0x271C42)
            context.fill(Path(ellipseIn: CGRect(
                x: center.x - radius, y: center.y - radius * 0.7,
                width: radius * 2, height: radius * 1.4)),
                with: .radialGradient(
                    Gradient(colors: [visualColor(tint, opacity: 0.15), visualColor(tint, opacity: 0)]),
                    center: center, startRadius: 0, endRadius: radius))
        }
        for _ in 0..<4 {
            let y = random.cgFloat(0.04...0.96) * size.height
            var swirl = Path()
            swirl.move(to: CGPoint(x: -side * 0.1, y: y))
            swirl.addCurve(to: CGPoint(x: side * 1.1, y: y + side * random.cgFloat(-0.3...0.3)),
                           control1: CGPoint(x: side * 0.27, y: y - side * 0.40),
                           control2: CGPoint(x: side * 0.65, y: y + side * 0.40))
            for layer in (1...3).reversed() {
                context.stroke(swirl, with: .color(visualColor(isLight ? 0xEDF1D7 : 0xD6A9CD,
                                                              opacity: layer == 1 ? 0.055 : 0.025)),
                               style: StrokeStyle(lineWidth: side * CGFloat(layer) * 0.019,
                                                  lineCap: .round))
            }
        }
        for _ in 0..<random.int(3...6) {
            let edge = random.int(0...3)
            let near = random.cgFloat(0.07...0.21)
            let along = random.cgFloat(0.08...0.92)
            let point: CGPoint = switch edge {
            case 0: CGPoint(x: near * size.width, y: along * size.height)
            case 1: CGPoint(x: (1 - near) * size.width, y: along * size.height)
            case 2: CGPoint(x: along * size.width, y: near * size.height)
            default: CGPoint(x: along * size.width, y: (1 - near) * size.height)
            }
            let radius = side * random.cgFloat(0.006...0.014)
            let bubble = Path(ellipseIn: CGRect(x: point.x - radius, y: point.y - radius,
                                                width: radius * 2, height: radius * 2))
            context.stroke(bubble, with: .color(visualColor(isLight ? 0x3F645E : 0x201B33, opacity: 0.30)),
                           lineWidth: side * 0.004)
            strokeLine(from: CGPoint(x: point.x - radius * 0.45, y: point.y - radius * 0.50),
                       to: CGPoint(x: point.x + radius * 0.20, y: point.y - radius * 0.55),
                       color: visualColor(0xF1EEDD, opacity: 0.31), width: side * 0.004, in: context)
        }
        context.fill(Path(CGRect(origin: .zero, size: size)), with: .linearGradient(
            Gradient(colors: [Color.white.opacity(0.055), .clear, .black.opacity(0.045)]),
            startPoint: .zero, endPoint: CGPoint(x: size.width, y: size.height)))
        drawHalloweenSquareLip(isLight: isLight, glass: true, in: context, size: size)
    }

    private static func drawHalloweenSquareLip(
        isLight: Bool, glass: Bool, in context: GraphicsContext, size: CGSize
    ) {
        let side = min(size.width, size.height)
        let joint = side * (glass ? 0.011 : 0.008)
        context.stroke(Path(CGRect(origin: .zero, size: size).insetBy(dx: joint / 2, dy: joint / 2)),
                       with: .color(visualColor(0x17191B, opacity: glass ? 0.52 : 0.34)),
                       lineWidth: joint)
        let inset = side * 0.016
        let sheen = visualColor(glass ? 0xEDF1D7 : 0xF2C784, opacity: isLight ? 0.22 : 0.16)
        strokeLine(from: CGPoint(x: inset, y: inset), to: CGPoint(x: size.width - inset, y: inset),
                   color: sheen, width: side * 0.007, in: context)
        strokeLine(from: CGPoint(x: inset, y: inset), to: CGPoint(x: inset, y: size.height - inset),
                   color: sheen.opacity(0.75), width: side * 0.007, in: context)
    }

    private static func fill(_ size: CGSize, color: Color, in context: GraphicsContext) {
        fillRect(CGRect(origin: .zero, size: size), color: color, in: context)
    }

    private static func fillRect(_ rect: CGRect, color: Color, in context: GraphicsContext) {
        var path = Path()
        path.addRect(rect)
        context.fill(path, with: .color(color))
    }

    private static func strokeLine(
        from: CGPoint,
        to: CGPoint,
        color: Color,
        width: CGFloat,
        in context: GraphicsContext
    ) {
        var path = Path()
        path.move(to: from)
        path.addLine(to: to)
        context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }
}

private enum CodeNativePiece: UInt8 {
    case pawn = 80
    case knight = 78
    case bishop = 66
    case rook = 82
    case queen = 81
    case king = 75
}

/// High-resolution sculpture atlases are color-keyed at draw time with one GPU matrix. Keeping
/// the two opaque source textures intact avoids twelve duplicate images, while the compact crop
/// table prevents transparent atlas padding from shrinking a piece inside its board square.
private enum SculpturePieceAtlasPainter {
    private final class CroppedImageBox: NSObject {
        let image: CGImage

        init(_ image: CGImage) {
            self.image = image
        }
    }

    // NSCache synchronizes access internally. Canvas may render off the main actor, so this
    // explicitly acknowledges that thread-safe shared cache to Swift 6's concurrency checker.
    nonisolated(unsafe) private static let croppedImageCache = NSCache<NSString, CroppedImageBox>()

    private struct Crop {
        let x: CGFloat
        let y: CGFloat
        let width: CGFloat
        let height: CGFloat

        var rect: CGRect { CGRect(x: x, y: y, width: width, height: height) }
    }

    static func draw(
        type: CodeNativePiece,
        style: BoardVisualTheme.PieceStyle,
        white: Bool,
        in context: GraphicsContext,
        size: CGSize
    ) -> Bool {
        guard style != .classic, size.width > 0, size.height > 0 else { return false }
        guard let image = croppedPieceImage(type: type, style: style, white: white) else { return false }
        let resolved = context.resolve(image)
        guard resolved.size.width > 0, resolved.size.height > 0 else { return false }

        let source = crop(type: type, style: style, white: white).rect
        let heightRatio: CGFloat = if style == .celestial {
            switch type {
            case .pawn: 0.70
            case .rook: 0.82
            case .knight: 0.89
            case .bishop: 0.91
            case .queen: 0.95
            case .king: 0.98
            }
        } else {
            0.96
        }
        let availableHeight = size.height * heightRatio
        let baseScale = availableHeight / source.height
        // Preserve the established All Hallows silhouette sizing; astronomical sculptures
        // retain their natural aspect ratio and their intended pawn-to-king height hierarchy.
        var targetWidth = source.width * baseScale * (style == .allHallows ? 1.28 : 1.0)
        var targetHeight = source.height * baseScale
        let maximumWidth = size.width * (style == .celestial ? 0.94 : 0.98)
        if targetWidth > maximumWidth {
            let correction = maximumWidth / targetWidth
            targetWidth *= correction
            targetHeight *= correction
        }

        let destination = CGRect(
            x: (size.width - targetWidth) / 2,
            y: size.height - targetHeight - size.height * 0.01,
            width: targetWidth,
            height: targetHeight
        )
        var drawing = context
        drawing.clip(to: Path(destination))
        drawing.addFilter(.colorMatrix(chromaKeyMatrix(style: style)))
        drawing.draw(resolved, in: destination)
        return true
    }

    private static func chromaKeyMatrix(style: BoardVisualTheme.PieceStyle) -> ColorMatrix {
        var matrix = ColorMatrix()
        if style == .celestial {
            // Green-screen antialiasing contaminates the green channel of edge pixels. A
            // convex blend of the clean red/blue channels removes that spill while retaining
            // neutral alabaster and warm brass. Alpha still uses the original input channels.
            matrix.g1 = 0.6
            matrix.g2 = 0
            matrix.g3 = 0.4
        }
        // Alpha = clamp(red - 2*green + blue + 1). The generated green backdrop
        // becomes zero while neutral ivory/obsidian and warm ember pixels remain opaque.
        matrix.a1 = 1
        matrix.a2 = -2
        matrix.a3 = 1
        matrix.a4 = 0
        matrix.a5 = 1
        return matrix
    }

    private static func crop(type: CodeNativePiece, style: BoardVisualTheme.PieceStyle, white: Bool) -> Crop {
        if style == .celestial {
            // Tight silhouette bounds include a ten-pixel edge pad so crowns and star points
            // retain their antialiased edges without inheriting the empty atlas background.
            if white {
                return switch type {
                case .pawn: Crop(x: 49, y: 421, width: 208, height: 285)
                case .rook: Crop(x: 295, y: 282, width: 263, height: 421)
                case .knight: Crop(x: 584, y: 301, width: 266, height: 400)
                case .bishop: Crop(x: 882, y: 252, width: 256, height: 444)
                case .queen: Crop(x: 1_169, y: 165, width: 254, height: 539)
                case .king: Crop(x: 1_447, y: 120, width: 268, height: 584)
                }
            }
            return switch type {
            case .pawn: Crop(x: 48, y: 414, width: 205, height: 283)
            case .rook: Crop(x: 290, y: 276, width: 261, height: 417)
            case .knight: Crop(x: 575, y: 295, width: 263, height: 396)
            case .bishop: Crop(x: 869, y: 247, width: 253, height: 439)
            case .queen: Crop(x: 1_151, y: 161, width: 251, height: 533)
            case .king: Crop(x: 1_426, y: 117, width: 265, height: 576)
            }
        }
        if white {
            return switch type {
            case .pawn: Crop(x: 42, y: 375, width: 246, height: 380)
            case .rook: Crop(x: 306, y: 320, width: 252, height: 438)
            case .knight: Crop(x: 591, y: 320, width: 270, height: 438)
            case .bishop: Crop(x: 910, y: 110, width: 250, height: 649)
            case .queen: Crop(x: 1_182, y: 205, width: 296, height: 553)
            case .king: Crop(x: 1_478, y: 173, width: 250, height: 583)
            }
        }
        return switch type {
        case .pawn: Crop(x: 65, y: 325, width: 225, height: 425)
        case .rook: Crop(x: 320, y: 230, width: 256, height: 520)
        case .knight: Crop(x: 593, y: 265, width: 294, height: 486)
        case .bishop: Crop(x: 887, y: 135, width: 270, height: 615)
        case .queen: Crop(x: 1_155, y: 175, width: 285, height: 575)
        case .king: Crop(x: 1_440, y: 135, width: 275, height: 615)
        }
    }

    private static func croppedPieceImage(
        type: CodeNativePiece, style: BoardVisualTheme.PieceStyle, white: Bool
    ) -> Image? {
        let styleKey = style == .celestial ? "celestial" : "all-hallows"
        let cacheKey = "\(styleKey)-\(white ? "ivory" : "dark")-\(type.rawValue)" as NSString
        if let cached = croppedImageCache.object(forKey: cacheKey) {
            return Image(decorative: cached.image, scale: 1)
        }

        guard let atlas = atlasCGImage(style: style, white: white),
              let cropped = atlas.cropping(to: crop(type: type, style: style, white: white).rect) else {
            return nil
        }
        croppedImageCache.setObject(CroppedImageBox(cropped), forKey: cacheKey)
        return Image(decorative: cropped, scale: 1)
    }

    private static func atlasCGImage(style: BoardVisualTheme.PieceStyle, white: Bool) -> CGImage? {
        let celestial = style == .celestial
        let name = celestial
            ? (white ? "CelestialIvoryAtlas" : "CelestialMidnightAtlas")
            : (white ? "AllHallowsIvoryAtlas" : "AllHallowsObsidianAtlas")
#if os(macOS)
        if let root = ProcessInfo.processInfo.environment["DRAWLESS_ASSET_CATALOG_ROOT"] {
            let imageset = "\(name).imageset"
            let filename = celestial
                ? (white ? "celestial-ivory-atlas-chroma.png" : "celestial-midnight-atlas-chroma.png")
                : (white ? "all-hallows-ivory-atlas-chroma.png" : "all-hallows-obsidian-atlas-chroma.png")
            let path = URL(fileURLWithPath: root)
                .appendingPathComponent(imageset)
                .appendingPathComponent(filename)
                .path
            if let image = NSImage(contentsOfFile: path),
               let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) {
                return cgImage
            }
        }
        return nil
#elseif os(iOS)
        return UIImage(named: name)?.cgImage
#else
        return nil
#endif
    }
}

private enum CodeNativePiecePainter {
    static func draw(
        type: CodeNativePiece,
        palette: BoardVisualTheme.PiecePalette,
        style: BoardVisualTheme.PieceStyle,
        white: Bool,
        in context: GraphicsContext,
        size: CGSize
    ) {
        let scale = min(size.width, size.height) / 100
        var drawing = context
        drawing.translateBy(x: (size.width - scale * 100) / 2, y: (size.height - scale * 100) / 2)
        drawing.scaleBy(x: scale, y: scale)

        let fill = white ? palette.whiteFill : palette.blackFill
        let outline = white ? palette.whiteOutline : palette.blackOutline
        let detail = white ? palette.whiteDetail : palette.blackDetail
        let kingAccent = white ? palette.whiteKingAccent : palette.blackKingAccent
        let queenAccent = white ? palette.whiteQueenAccent : palette.blackQueenAccent
        let shape = piecePath(type)

        drawing.stroke(shape, with: .color(outline), style: pieceStroke(7))
        drawing.fill(shape, with: .color(fill))
        if style == .allHallows {
            drawing.fill(
                shape,
                with: .linearGradient(
                    Gradient(colors: [
                        Color.white.opacity(white ? 0.20 : 0.08),
                        Color.clear,
                        Color.black.opacity(white ? 0.15 : 0.30),
                    ]),
                    startPoint: CGPoint(x: 24, y: 12),
                    endPoint: CGPoint(x: 76, y: 76)
                )
            )
        }
        drawing.stroke(shape, with: .color(outline), style: pieceStroke(2.4))

        if style == .allHallows {
            drawAllHallowsDetails(
                type: type,
                fill: fill,
                outline: outline,
                detail: detail,
                kingAccent: kingAccent,
                queenAccent: queenAccent,
                in: drawing
            )
        } else {
            drawClassicDetails(
                type: type,
                fill: fill,
                outline: outline,
                detail: detail,
                kingAccent: kingAccent,
                queenAccent: queenAccent,
                in: drawing
            )
        }

        let base = basePath(bishop: type == .bishop)
        drawing.stroke(base, with: .color(outline), style: pieceStroke(7))
        drawing.fill(base, with: .color(fill))
        if style == .allHallows {
            drawing.fill(
                base,
                with: .linearGradient(
                    Gradient(colors: [Color.white.opacity(0.12), Color.clear, Color.black.opacity(0.25)]),
                    startPoint: CGPoint(x: 20, y: 68),
                    endPoint: CGPoint(x: 80, y: 94)
                )
            )
        }
        drawing.stroke(base, with: .color(outline), style: pieceStroke(2.4))
        line(
            from: CGPoint(x: 23, y: type == .bishop ? 80 : 83),
            to: CGPoint(x: 77, y: type == .bishop ? 80 : 83),
            color: detail,
            width: 2.6,
            in: drawing
        )
        if style == .allHallows {
            for x in [CGFloat(34), 50, 66] {
                drawing.fill(
                    Path(ellipseIn: CGRect(x: x - 1.7, y: 86.3, width: 3.4, height: 3.4)),
                    with: .color(x == 50 ? queenAccent : detail)
                )
            }
        }
    }

    private static func drawClassicDetails(
        type: CodeNativePiece,
        fill: Color,
        outline: Color,
        detail: Color,
        kingAccent: Color,
        queenAccent: Color,
        in drawing: GraphicsContext
    ) {
        switch type {
        case .king:
            drawing.fill(Path(CGRect(x: 25, y: 40, width: 50, height: 12)), with: .color(kingAccent))
            line(from: CGPoint(x: 25, y: 40), to: CGPoint(x: 75, y: 40), color: outline, width: 2.2, in: drawing)
            for center in [CGPoint(x: 35, y: 46), CGPoint(x: 65, y: 46)] {
                drawing.fill(
                    Path(ellipseIn: CGRect(x: center.x - 2.6, y: center.y - 2.6, width: 5.2, height: 5.2)),
                    with: .color(detail)
                )
            }
        case .queen:
            for center in [CGPoint(x: 27, y: 18), CGPoint(x: 42, y: 13), CGPoint(x: 58, y: 13), CGPoint(x: 73, y: 18)] {
                drawing.fill(
                    Path(ellipseIn: CGRect(x: center.x - 4.2, y: center.y - 4.2, width: 8.4, height: 8.4)),
                    with: .color(outline)
                )
                drawing.fill(
                    Path(ellipseIn: CGRect(x: center.x - 2.8, y: center.y - 2.8, width: 5.6, height: 5.6)),
                    with: .color(queenAccent)
                )
            }
        case .bishop:
            let mitre = bishopMitrePath()
            drawing.fill(mitre, with: .color(outline))
            line(from: CGPoint(x: 57, y: 19), to: CGPoint(x: 43, y: 43), color: detail, width: 2.8, in: drawing)
            let collar = bishopCollarPath()
            drawing.stroke(collar, with: .color(outline), style: pieceStroke(6))
            drawing.fill(collar, with: .color(fill))
            drawing.stroke(collar, with: .color(outline), style: pieceStroke(2.4))
            line(from: CGPoint(x: 26, y: 59), to: CGPoint(x: 74, y: 59), color: detail, width: 2.8, in: drawing)
        case .knight:
            drawing.fill(Path(ellipseIn: CGRect(x: 54.4, y: 28.4, width: 5.2, height: 5.2)), with: .color(detail))
            line(from: CGPoint(x: 48, y: 48), to: CGPoint(x: 63, y: 55), color: detail, width: 3, in: drawing)
        case .rook:
            line(from: CGPoint(x: 31, y: 42), to: CGPoint(x: 69, y: 42), color: detail, width: 3, in: drawing)
        case .pawn:
            break
        }
    }

    private static func drawAllHallowsDetails(
        type: CodeNativePiece,
        fill: Color,
        outline: Color,
        detail: Color,
        kingAccent: Color,
        queenAccent: Color,
        in drawing: GraphicsContext
    ) {
        switch type {
        case .pawn:
            drawAllHallowsPawn(detail: detail, ember: queenAccent, in: drawing)
        case .rook:
            drawAllHallowsRook(outline: outline, detail: detail, ember: queenAccent, in: drawing)
        case .knight:
            drawAllHallowsKnight(detail: detail, ember: kingAccent, in: drawing)
        case .bishop:
            drawAllHallowsBishop(fill: fill, outline: outline, detail: detail, ember: kingAccent, in: drawing)
        case .queen:
            drawAllHallowsQueen(outline: outline, detail: detail, ember: queenAccent, in: drawing)
        case .king:
            drawAllHallowsKing(outline: outline, detail: detail, ember: kingAccent, in: drawing)
        }
    }

    private static func drawAllHallowsPawn(
        detail: Color,
        ember: Color,
        in drawing: GraphicsContext
    ) {
        var stem = Path()
        stem.move(to: CGPoint(x: 49, y: 16))
        stem.addQuadCurve(to: CGPoint(x: 55, y: 10), control: CGPoint(x: 51, y: 11))
        drawing.stroke(stem, with: .color(detail), style: pieceStroke(3.4))
        for x in [CGFloat(42), 50, 58] {
            var rib = Path()
            rib.move(to: CGPoint(x: x, y: 23))
            rib.addQuadCurve(
                to: CGPoint(x: x, y: 43),
                control: CGPoint(x: 50 + (x - 50) * 1.25, y: 33)
            )
            drawing.stroke(rib, with: .color(x == 50 ? ember : detail), style: pieceStroke(2.0))
        }
    }

    private static func drawAllHallowsRook(
        outline: Color,
        detail: Color,
        ember: Color,
        in drawing: GraphicsContext
    ) {
        line(from: CGPoint(x: 31, y: 42), to: CGPoint(x: 69, y: 42), color: detail, width: 2.6, in: drawing)
        for x in [CGFloat(39), 57] {
            drawing.fill(Path(CGRect(x: x - 3, y: 29, width: 6, height: 10)), with: .color(outline))
            drawing.fill(Path(CGRect(x: x - 1.6, y: 31, width: 3.2, height: 6.5)), with: .color(ember))
        }
        var door = Path()
        door.move(to: CGPoint(x: 44, y: 68))
        door.addLine(to: CGPoint(x: 44, y: 57))
        door.addQuadCurve(to: CGPoint(x: 56, y: 57), control: CGPoint(x: 50, y: 49))
        door.addLine(to: CGPoint(x: 56, y: 68))
        drawing.stroke(door, with: .color(detail), style: pieceStroke(2.4))
    }

    private static func drawAllHallowsKnight(
        detail: Color,
        ember: Color,
        in drawing: GraphicsContext
    ) {
        drawing.fill(Path(ellipseIn: CGRect(x: 53.5, y: 27.5, width: 7, height: 7)), with: .color(detail))
        drawing.fill(Path(ellipseIn: CGRect(x: 55.2, y: 29.2, width: 3.6, height: 3.6)), with: .color(ember))
        var jaw = Path()
        jaw.move(to: CGPoint(x: 45, y: 47))
        jaw.addLine(to: CGPoint(x: 62, y: 54))
        jaw.addLine(to: CGPoint(x: 55, y: 60))
        drawing.stroke(jaw, with: .color(detail), style: pieceStroke(2.8))
        for offset in [CGFloat(0), 5, 10] {
            line(
                from: CGPoint(x: 48 + offset, y: 50 + offset * 0.38),
                to: CGPoint(x: 46 + offset, y: 55 + offset * 0.38),
                color: detail,
                width: 1.8,
                in: drawing
            )
        }
    }

    private static func drawAllHallowsBishop(
        fill: Color,
        outline: Color,
        detail: Color,
        ember: Color,
        in drawing: GraphicsContext
    ) {
        let mitre = bishopMitrePath()
        drawing.fill(mitre, with: .color(outline))
        var hood = Path()
        hood.move(to: CGPoint(x: 50, y: 17))
        hood.addCurve(
            to: CGPoint(x: 42, y: 42),
            control1: CGPoint(x: 41, y: 25),
            control2: CGPoint(x: 40, y: 36)
        )
        hood.addQuadCurve(to: CGPoint(x: 60, y: 42), control: CGPoint(x: 51, y: 34))
        hood.addCurve(
            to: CGPoint(x: 50, y: 17),
            control1: CGPoint(x: 61, y: 32),
            control2: CGPoint(x: 58, y: 23)
        )
        hood.closeSubpath()
        drawing.stroke(hood, with: .color(detail), style: pieceStroke(2.2))
        drawing.fill(Path(ellipseIn: CGRect(x: 46.8, y: 30, width: 3.2, height: 2.4)), with: .color(ember))
        drawing.fill(Path(ellipseIn: CGRect(x: 52, y: 30, width: 3.2, height: 2.4)), with: .color(ember))
        let collar = bishopCollarPath()
        drawing.stroke(collar, with: .color(outline), style: pieceStroke(6))
        drawing.fill(collar, with: .color(fill))
        drawing.stroke(collar, with: .color(outline), style: pieceStroke(2.4))
        line(from: CGPoint(x: 26, y: 59), to: CGPoint(x: 74, y: 59), color: detail, width: 2.8, in: drawing)
    }

    private static func drawAllHallowsQueen(
        outline: Color,
        detail: Color,
        ember: Color,
        in drawing: GraphicsContext
    ) {
        let jewels = [CGPoint(x: 27, y: 18), CGPoint(x: 42, y: 13), CGPoint(x: 58, y: 13), CGPoint(x: 73, y: 18)]
        for center in jewels {
            var jewel = Path()
            jewel.move(to: CGPoint(x: center.x, y: center.y - 4.5))
            jewel.addLine(to: CGPoint(x: center.x + 4, y: center.y))
            jewel.addLine(to: CGPoint(x: center.x, y: center.y + 5))
            jewel.addLine(to: CGPoint(x: center.x - 4, y: center.y))
            jewel.closeSubpath()
            drawing.fill(jewel, with: .color(outline))
            drawing.fill(Path(ellipseIn: CGRect(x: center.x - 1.8, y: center.y - 1.8, width: 3.6, height: 3.6)), with: .color(ember))
        }
        var gothicArch = Path()
        gothicArch.move(to: CGPoint(x: 36, y: 52))
        gothicArch.addQuadCurve(to: CGPoint(x: 50, y: 39), control: CGPoint(x: 41, y: 40))
        gothicArch.addQuadCurve(to: CGPoint(x: 64, y: 52), control: CGPoint(x: 59, y: 40))
        drawing.stroke(gothicArch, with: .color(detail), style: pieceStroke(2.6))
    }

    private static func drawAllHallowsKing(
        outline: Color,
        detail: Color,
        ember: Color,
        in drawing: GraphicsContext
    ) {
        drawing.fill(Path(CGRect(x: 25, y: 40, width: 50, height: 12)), with: .color(ember))
        line(from: CGPoint(x: 25, y: 40), to: CGPoint(x: 75, y: 40), color: outline, width: 2.2, in: drawing)
        var bat = Path()
        bat.move(to: CGPoint(x: 50, y: 36))
        bat.addLine(to: CGPoint(x: 43, y: 29))
        bat.addLine(to: CGPoint(x: 36, y: 31))
        bat.addLine(to: CGPoint(x: 39, y: 36))
        bat.addLine(to: CGPoint(x: 50, y: 40))
        bat.addLine(to: CGPoint(x: 61, y: 36))
        bat.addLine(to: CGPoint(x: 64, y: 31))
        bat.addLine(to: CGPoint(x: 57, y: 29))
        bat.closeSubpath()
        drawing.fill(bat, with: .color(detail))
        drawing.stroke(bat, with: .color(outline), style: pieceStroke(1.8))
        for center in [CGPoint(x: 35, y: 46), CGPoint(x: 65, y: 46)] {
            drawing.fill(
                Path(ellipseIn: CGRect(x: center.x - 2.4, y: center.y - 2.4, width: 4.8, height: 4.8)),
                with: .color(detail)
            )
        }
    }

    private static func pieceStroke(_ width: CGFloat) -> StrokeStyle {
        StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round)
    }

    private static func line(
        from: CGPoint,
        to: CGPoint,
        color: Color,
        width: CGFloat,
        in context: GraphicsContext
    ) {
        var path = Path()
        path.move(to: from)
        path.addLine(to: to)
        context.stroke(path, with: .color(color), style: pieceStroke(width))
    }

    private static func piecePath(_ type: CodeNativePiece) -> Path {
        switch type {
        case .pawn: pawnPath()
        case .knight: knightPath()
        case .bishop: bishopPath()
        case .rook: rookPath()
        case .queen: queenPath()
        case .king: kingPath()
        }
    }

    private static func basePath(bishop: Bool) -> Path {
        let top: CGFloat = bishop ? 69 : 70
        let bottom: CGFloat = bishop ? 91 : 93
        var path = Path()
        path.move(to: CGPoint(x: 25, y: top))
        path.addLine(to: CGPoint(x: 75, y: top))
        path.addLine(to: CGPoint(x: 82, y: 88))
        path.addQuadCurve(to: CGPoint(x: 77, y: bottom), control: CGPoint(x: 83, y: bottom))
        path.addLine(to: CGPoint(x: 23, y: bottom))
        path.addQuadCurve(to: CGPoint(x: 18, y: 88), control: CGPoint(x: 17, y: bottom))
        path.closeSubpath()
        return path
    }

    private static func pawnPath() -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 50, y: 14))
        path.addCurve(to: CGPoint(x: 33, y: 32), control1: CGPoint(x: 39, y: 14), control2: CGPoint(x: 33, y: 22))
        path.addCurve(to: CGPoint(x: 44, y: 50), control1: CGPoint(x: 33, y: 41), control2: CGPoint(x: 38, y: 47))
        path.addCurve(to: CGPoint(x: 31, y: 74), control1: CGPoint(x: 37, y: 56), control2: CGPoint(x: 32, y: 64))
        path.addLine(to: CGPoint(x: 69, y: 74))
        path.addCurve(to: CGPoint(x: 56, y: 50), control1: CGPoint(x: 68, y: 64), control2: CGPoint(x: 63, y: 56))
        path.addCurve(to: CGPoint(x: 67, y: 32), control1: CGPoint(x: 62, y: 47), control2: CGPoint(x: 67, y: 41))
        path.addCurve(to: CGPoint(x: 50, y: 14), control1: CGPoint(x: 67, y: 22), control2: CGPoint(x: 61, y: 14))
        path.closeSubpath()
        return path
    }

    private static func rookPath() -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 26, y: 15))
        for point in [
            CGPoint(x: 38, y: 15), CGPoint(x: 38, y: 25), CGPoint(x: 46, y: 25),
            CGPoint(x: 46, y: 15), CGPoint(x: 56, y: 15), CGPoint(x: 56, y: 25),
            CGPoint(x: 64, y: 25), CGPoint(x: 64, y: 15), CGPoint(x: 76, y: 15),
            CGPoint(x: 73, y: 39), CGPoint(x: 67, y: 45), CGPoint(x: 70, y: 74),
            CGPoint(x: 30, y: 74), CGPoint(x: 33, y: 45), CGPoint(x: 29, y: 39)
        ] { path.addLine(to: point) }
        path.closeSubpath()
        return path
    }

    private static func knightPath() -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 29, y: 74))
        path.addCurve(to: CGPoint(x: 43, y: 42), control1: CGPoint(x: 30, y: 60), control2: CGPoint(x: 35, y: 49))
        path.addLine(to: CGPoint(x: 36, y: 29))
        path.addLine(to: CGPoint(x: 52, y: 34))
        path.addLine(to: CGPoint(x: 47, y: 18))
        path.addCurve(to: CGPoint(x: 73, y: 50), control1: CGPoint(x: 67, y: 22), control2: CGPoint(x: 76, y: 35))
        path.addCurve(to: CGPoint(x: 64, y: 74), control1: CGPoint(x: 71, y: 62), control2: CGPoint(x: 62, y: 65))
        path.closeSubpath()
        return path
    }

    private static func bishopPath() -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 52, y: 10))
        path.addCurve(to: CGPoint(x: 39, y: 38), control1: CGPoint(x: 44, y: 17), control2: CGPoint(x: 37, y: 28))
        path.addCurve(to: CGPoint(x: 47, y: 52), control1: CGPoint(x: 40, y: 45), control2: CGPoint(x: 45, y: 49))
        path.addLine(to: CGPoint(x: 43, y: 58))
        path.addCurve(to: CGPoint(x: 29, y: 74), control1: CGPoint(x: 38, y: 62), control2: CGPoint(x: 33, y: 68))
        path.addLine(to: CGPoint(x: 71, y: 74))
        path.addCurve(to: CGPoint(x: 57, y: 57), control1: CGPoint(x: 67, y: 68), control2: CGPoint(x: 62, y: 62))
        path.addLine(to: CGPoint(x: 53, y: 52))
        path.addCurve(to: CGPoint(x: 64, y: 37), control1: CGPoint(x: 58, y: 49), control2: CGPoint(x: 63, y: 44))
        path.addCurve(to: CGPoint(x: 52, y: 10), control1: CGPoint(x: 65, y: 27), control2: CGPoint(x: 59, y: 17))
        path.closeSubpath()
        return path
    }

    private static func queenPath() -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 24, y: 23))
        for point in [
            CGPoint(x: 35, y: 38), CGPoint(x: 42, y: 18), CGPoint(x: 50, y: 38),
            CGPoint(x: 58, y: 18), CGPoint(x: 65, y: 38), CGPoint(x: 76, y: 23),
            CGPoint(x: 68, y: 54)
        ] { path.addLine(to: point) }
        path.addCurve(to: CGPoint(x: 70, y: 74), control1: CGPoint(x: 66, y: 62), control2: CGPoint(x: 68, y: 67))
        path.addLine(to: CGPoint(x: 30, y: 74))
        path.addCurve(to: CGPoint(x: 32, y: 54), control1: CGPoint(x: 32, y: 67), control2: CGPoint(x: 34, y: 62))
        path.closeSubpath()
        return path
    }

    private static func kingPath() -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 34, y: 74))
        path.addCurve(to: CGPoint(x: 41, y: 53), control1: CGPoint(x: 33, y: 65), control2: CGPoint(x: 37, y: 58))
        path.addLine(to: CGPoint(x: 24, y: 53))
        path.addLine(to: CGPoint(x: 23, y: 20))
        path.addLine(to: CGPoint(x: 35, y: 36))
        path.addLine(to: CGPoint(x: 50, y: 14))
        path.addLine(to: CGPoint(x: 65, y: 36))
        path.addLine(to: CGPoint(x: 77, y: 20))
        path.addLine(to: CGPoint(x: 76, y: 53))
        path.addLine(to: CGPoint(x: 59, y: 53))
        path.addCurve(to: CGPoint(x: 66, y: 74), control1: CGPoint(x: 63, y: 58), control2: CGPoint(x: 67, y: 65))
        path.closeSubpath()
        return path
    }

    private static func bishopMitrePath() -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 59, y: 16))
        path.addLine(to: CGPoint(x: 47, y: 43))
        path.addQuadCurve(to: CGPoint(x: 40, y: 43), control: CGPoint(x: 44, y: 47))
        path.addLine(to: CGPoint(x: 55, y: 15))
        path.closeSubpath()
        return path
    }

    private static func bishopCollarPath() -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 27, y: 51))
        path.addLine(to: CGPoint(x: 73, y: 51))
        path.addLine(to: CGPoint(x: 78, y: 60))
        path.addQuadCurve(to: CGPoint(x: 73, y: 66), control: CGPoint(x: 80, y: 65))
        path.addLine(to: CGPoint(x: 27, y: 66))
        path.addQuadCurve(to: CGPoint(x: 22, y: 60), control: CGPoint(x: 20, y: 65))
        path.closeSubpath()
        return path
    }
}

private struct StableVisualRandom {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed == 0 ? 0x9E3779B97F4A7C15 : seed
    }

    static func squareSeed(textureId: String, file: Int, rank: Int) -> UInt64 {
        var value: UInt64 = 0xCBF29CE484222325
        for byte in textureId.utf8 {
            value ^= UInt64(byte)
            value &*= 0x100000001B3
        }
        value ^= UInt64(file & 7) &* 0x9E3779B185EBCA87
        value ^= UInt64(rank & 7) &* 0xC2B2AE3D27D4EB4F
        return value
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var value = state
        value = (value ^ (value >> 30)) &* 0xBF58476D1CE4E5B9
        value = (value ^ (value >> 27)) &* 0x94D049BB133111EB
        return value ^ (value >> 31)
    }

    mutating func unit() -> CGFloat {
        CGFloat(Double(next() >> 11) / Double(1 << 53))
    }

    mutating func bool(probability: Double = 0.5) -> Bool {
        Double(unit()) < probability
    }

    mutating func int(_ range: ClosedRange<Int>) -> Int {
        guard range.lowerBound < range.upperBound else { return range.lowerBound }
        return range.lowerBound + Int(next() % UInt64(range.upperBound - range.lowerBound + 1))
    }

    mutating func cgFloat(_ range: ClosedRange<CGFloat>) -> CGFloat {
        range.lowerBound + (range.upperBound - range.lowerBound) * unit()
    }

    mutating func double(_ range: ClosedRange<Double>) -> Double {
        range.lowerBound + (range.upperBound - range.lowerBound) * Double(unit())
    }

    mutating func element<T>(_ values: [T]) -> T {
        values[Int(next() % UInt64(values.count))]
    }
}

private func visualColor(_ rgb: UInt32, opacity: Double = 1) -> Color {
    Color(
        red: Double((rgb >> 16) & 0xFF) / 255,
        green: Double((rgb >> 8) & 0xFF) / 255,
        blue: Double(rgb & 0xFF) / 255,
        opacity: opacity
    )
}
