import AppKit
import SwiftUI

private let previewThemes = [
    "imperial_marble",
    "desert_sandstone",
    "glacier_slate",
    "verdigris_copper",
    "amethyst_geode",
]

private let startingPieces = ["bR", "bN", "bB", "bQ", "bK", "bB", "bN", "bR"]
    + Array(repeating: "bP", count: 8)
    + Array(repeating: "", count: 32)
    + Array(repeating: "wP", count: 8)
    + ["wR", "wN", "wB", "wQ", "wK", "wB", "wN", "wR"]

private struct SnapshotBoard: View {
    let themeId: String

    var body: some View {
        VStack(spacing: 0) {
            ForEach(0..<8, id: \.self) { row in
                HStack(spacing: 0) {
                    ForEach(0..<8, id: \.self) { column in
                        let index = row * 8 + column
                        ZStack {
                            BoardSquareSurface(
                                themeId: themeId,
                                isLight: (row + column).isMultiple(of: 2),
                                file: column,
                                rank: 7 - row
                            )
                            ChessPieceView(pieceCode: startingPieces[index], themeId: themeId)
                        }
                        .frame(width: 34, height: 34)
                    }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 7))
        .overlay(RoundedRectangle(cornerRadius: 7).stroke(Color(red: 0.82, green: 0.71, blue: 0.42), lineWidth: 2))
    }
}

private struct PieceCatalog: View {
    let themeId: String
    private let codes = ["wP", "wN", "wB", "wR", "wQ", "wK", "bP", "bN", "bB", "bR", "bQ", "bK"]

    var body: some View {
        VStack(spacing: 4) {
            ForEach(0..<2, id: \.self) { row in
                HStack(spacing: 4) {
                    ForEach(0..<6, id: \.self) { column in
                        let index = row * 6 + column
                        ZStack {
                            BoardSquareSurface(
                                themeId: themeId,
                                isLight: column.isMultiple(of: 2),
                                file: column,
                                rank: row
                            )
                            ChessPieceView(pieceCode: codes[index], themeId: themeId)
                        }
                        .frame(width: 42, height: 42)
                    }
                }
            }
        }
    }
}

private struct VisualCatalog: View {
    var body: some View {
        HStack(alignment: .top, spacing: 24) {
            ForEach(previewThemes, id: \.self) { themeId in
                VStack(spacing: 12) {
                    Text(themeId.replacingOccurrences(of: "_", with: " ").uppercased())
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundStyle(Color(red: 0.82, green: 0.71, blue: 0.42))
                    SnapshotBoard(themeId: themeId)
                    PieceCatalog(themeId: themeId)
                }
                .padding(14)
                .background(Color(red: 0.115, green: 0.14, blue: 0.12), in: RoundedRectangle(cornerRadius: 14))
            }
        }
        .padding(24)
        .background(Color(red: 0.065, green: 0.075, blue: 0.072))
    }
}

@main
private struct RenderBoardVisualPreview {
    @MainActor
    static func main() throws {
        guard CommandLine.arguments.count == 2 else {
            throw PreviewError.usage
        }
        let renderer = ImageRenderer(content: VisualCatalog().preferredColorScheme(.dark))
        renderer.scale = 2
        guard let image = renderer.cgImage else { throw PreviewError.renderFailed }
        let representation = NSBitmapImageRep(cgImage: image)
        guard let data = representation.representation(using: .png, properties: [:]) else {
            throw PreviewError.renderFailed
        }
        try data.write(to: URL(fileURLWithPath: CommandLine.arguments[1]), options: .atomic)
    }
}

private enum PreviewError: Error {
    case usage
    case renderFailed
}
