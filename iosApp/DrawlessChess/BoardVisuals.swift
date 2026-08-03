import SwiftUI

/// Apple rendering for the original code-native pieces and procedural board materials used by
/// the Android product. Everything is drawn locally; no font glyphs or licensed piece assets are
/// required, and the square seed keeps every material cut stable across redraws.
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

struct ChessPieceView: View {
    let pieceCode: String
    let themeId: String

    var body: some View {
        let bytes = Array(pieceCode.utf8)
        if bytes.count == 2,
           let type = CodeNativePiece(rawValue: bytes[1]) {
            let white = bytes[0] == Character("w").asciiValue
            let palette = BoardVisualTheme.resolve(themeId).pieces
            Canvas(opaque: false, colorMode: .nonLinear, rendersAsynchronously: true) { context, size in
                CodeNativePiecePainter.draw(
                    type: type,
                    palette: palette,
                    white: white,
                    in: context,
                    size: size
                )
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
        case amethyst
    }

    struct PiecePalette {
        let whiteFill: Color
        let whiteOutline: Color
        let whiteDetail: Color
        let whiteKingAccent: Color
        let blackFill: Color
        let blackOutline: Color
        let blackDetail: Color
        let blackKingAccent: Color
    }

    let id: String
    let texture: Texture
    let lightSquare: Color
    let darkSquare: Color
    let pieces: PiecePalette

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
                    blackFill: visualColor(0x241710), blackOutline: visualColor(0xFFE4C7),
                    blackDetail: visualColor(0xC89B78), blackKingAccent: visualColor(0x4DD8BD)
                )
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
                    blackFill: visualColor(0x102630), blackOutline: visualColor(0xD9F3FF),
                    blackDetail: visualColor(0x89A9B8), blackKingAccent: visualColor(0x45D7D9)
                )
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
                    blackFill: visualColor(0x102724), blackOutline: visualColor(0xF1E5CB),
                    blackDetail: visualColor(0xA5BBB4), blackKingAccent: visualColor(0xE5A45D)
                )
            )
        case "amethyst_geode":
            BoardVisualTheme(
                id: id,
                texture: .amethyst,
                lightSquare: visualColor(0xE3D9F0),
                darkSquare: visualColor(0x54406E),
                pieces: PiecePalette(
                    whiteFill: visualColor(0xFCF5E6), whiteOutline: visualColor(0x2B1D38),
                    whiteDetail: visualColor(0x77648A), whiteKingAccent: visualColor(0xC43E5C),
                    blackFill: visualColor(0x21162F), blackOutline: visualColor(0xEDE0F7),
                    blackDetail: visualColor(0xBCA4D0), blackKingAccent: visualColor(0xFFD166)
                )
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
                    blackFill: visualColor(0x111A16), blackOutline: visualColor(0xEAF1EC),
                    blackDetail: visualColor(0x9FB0A6), blackKingAccent: visualColor(0xE9C349)
                )
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
        case .amethyst: drawAmethyst(isLight: isLight, in: context, size: size, random: &random)
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

    private static func drawAmethyst(
        isLight: Bool,
        in context: GraphicsContext,
        size: CGSize,
        random: inout StableVisualRandom
    ) {
        fill(size, color: isLight ? visualColor(0xE3D9F0) : visualColor(0x54406E), in: context)
        let side = min(size.width, size.height)
        let facetCount = random.int(7...10)
        for _ in 0..<facetCount {
            let center = CGPoint(
                x: random.cgFloat(-0.05...1.05) * size.width,
                y: random.cgFloat(-0.05...1.05) * size.height
            )
            let radius = side * random.cgFloat(0.22...0.48)
            let vertices = random.int(5...8)
            var facet = Path()
            for index in 0..<vertices {
                let angle = CGFloat(index) / CGFloat(vertices) * 2 * .pi + random.cgFloat(-0.16...0.16)
                let point = CGPoint(
                    x: center.x + cos(angle) * radius * random.cgFloat(0.62...1.0),
                    y: center.y + sin(angle) * radius * random.cgFloat(0.62...1.0)
                )
                if index == 0 { facet.move(to: point) } else { facet.addLine(to: point) }
            }
            facet.closeSubpath()
            let tint: UInt32 = isLight
                ? random.element([0xF0E8FA, 0xC9B7E1, 0xD8C8EC, 0xF5ECFF])
                : random.element([0x3C2A52, 0x70558E, 0x49335F, 0x8064A0])
            context.fill(facet, with: .color(visualColor(tint, opacity: random.double(0.08...0.20))))
            context.stroke(
                facet,
                with: .color(visualColor(isLight ? 0x77648A : 0xBCA4D0, opacity: random.double(0.12...0.25))),
                style: StrokeStyle(lineWidth: max(0.45, side * 0.006), lineJoin: .round)
            )
        }
        for _ in 0..<random.int(3...6) {
            let center = CGPoint(x: random.unit() * size.width, y: random.unit() * size.height)
            let length = side * random.cgFloat(0.012...0.03)
            let color = Color.white.opacity(random.double(0.20...0.43))
            strokeLine(
                from: CGPoint(x: center.x - length, y: center.y),
                to: CGPoint(x: center.x + length, y: center.y),
                color: color,
                width: max(0.55, side * 0.005),
                in: context
            )
            strokeLine(
                from: CGPoint(x: center.x, y: center.y - length),
                to: CGPoint(x: center.x, y: center.y + length),
                color: color,
                width: max(0.55, side * 0.005),
                in: context
            )
        }
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

private enum CodeNativePiecePainter {
    static func draw(
        type: CodeNativePiece,
        palette: BoardVisualTheme.PiecePalette,
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
        let shape = piecePath(type)

        drawing.stroke(shape, with: .color(outline), style: pieceStroke(7))
        drawing.fill(shape, with: .color(fill))
        drawing.stroke(shape, with: .color(outline), style: pieceStroke(2.4))

        switch type {
        case .king:
            line(from: CGPoint(x: 50, y: 5), to: CGPoint(x: 50, y: 29), color: outline, width: 8, in: drawing)
            line(from: CGPoint(x: 40, y: 14), to: CGPoint(x: 60, y: 14), color: outline, width: 8, in: drawing)
            line(from: CGPoint(x: 50, y: 5), to: CGPoint(x: 50, y: 29), color: kingAccent, width: 4.5, in: drawing)
            line(from: CGPoint(x: 40, y: 14), to: CGPoint(x: 60, y: 14), color: kingAccent, width: 4.5, in: drawing)
        case .queen:
            for center in [CGPoint(x: 27, y: 18), CGPoint(x: 42, y: 13), CGPoint(x: 58, y: 13), CGPoint(x: 73, y: 18)] {
                drawing.fill(Path(ellipseIn: CGRect(x: center.x - 3.4, y: center.y - 3.4, width: 6.8, height: 6.8)), with: .color(detail))
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

        let base = basePath(bishop: type == .bishop)
        drawing.stroke(base, with: .color(outline), style: pieceStroke(7))
        drawing.fill(base, with: .color(fill))
        drawing.stroke(base, with: .color(outline), style: pieceStroke(2.4))
        line(
            from: CGPoint(x: 23, y: type == .bishop ? 80 : 83),
            to: CGPoint(x: 77, y: type == .bishop ? 80 : 83),
            color: detail,
            width: 2.6,
            in: drawing
        )
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
        path.move(to: CGPoint(x: 50, y: 25))
        path.addCurve(to: CGPoint(x: 34, y: 47), control1: CGPoint(x: 36, y: 25), control2: CGPoint(x: 29, y: 35))
        path.addCurve(to: CGPoint(x: 36, y: 64), control1: CGPoint(x: 37, y: 54), control2: CGPoint(x: 40, y: 58))
        path.addLine(to: CGPoint(x: 31, y: 74))
        path.addLine(to: CGPoint(x: 69, y: 74))
        path.addLine(to: CGPoint(x: 64, y: 64))
        path.addCurve(to: CGPoint(x: 66, y: 47), control1: CGPoint(x: 60, y: 58), control2: CGPoint(x: 63, y: 54))
        path.addCurve(to: CGPoint(x: 50, y: 25), control1: CGPoint(x: 71, y: 35), control2: CGPoint(x: 64, y: 25))
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
