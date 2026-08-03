import AVFoundation
import DrawlessShared
import UIKit

@MainActor
final class GameFeedback {
    private var players: [AVAudioPlayer] = []
    private var nextVariant: [String: Int] = [:]
    private var configuredSession = false

    func process(
        previous: SharedGameView?,
        next: SharedGameView,
        preferences: DrawlessChessModel.Preferences
    ) {
        let newGame = previous?.gameId != next.gameId
        if newGame {
            play("chess_game_start", preferences: preferences)
            impact(.medium, preferences: preferences)
            return
        }

        if previous?.hintMove != next.hintMove, next.hintMove != nil {
            play("chess_hint", preferences: preferences)
            impact(.light, preferences: preferences)
        }

        if let previous, next.plyCount < previous.plyCount {
            play("chess_undo", preferences: preferences)
            impact(.soft, preferences: preferences)
        } else if let previous, next.plyCount > previous.plyCount {
            let previousPieces = previous.cells.filter { !$0.pieceCode.isEmpty }.count
            let nextPieces = next.cells.filter { !$0.pieceCode.isEmpty }.count
            let notation = next.lastMoveNotation ?? ""
            let primary: String
            if notation.hasSuffix("#") {
                primary = "chess_checkmate_stone"
            } else if next.lastMoveEnPassant {
                primary = "chess_en_passant_brick"
            } else if notation.hasSuffix("+") {
                primary = "chess_check_mechanical"
            } else if notation.hasPrefix("O-O") {
                primary = "chess_castle_wood"
            } else if notation.contains("x") || nextPieces < previousPieces {
                primary = "chess_capture_crush"
            } else {
                primary = "chess_move_wood"
            }
            play(primary, preferences: preferences)
            impact(nextPieces < previousPieces ? .rigid : .light, preferences: preferences)
        } else if let previous {
            let wasSelected = previous.cells.contains(where: { $0.selected })
            let isSelected = next.cells.contains(where: { $0.selected })
            if wasSelected != isSelected { impact(.light, preferences: preferences) }
        }

        if let previous, crossedLowTime(previous: previous, next: next) {
            play("chess_low_time", preferences: preferences)
            notification(.warning, preferences: preferences)
        }

        if previous?.phase != "COMPLETED", next.phase == "COMPLETED" {
            let won = next.winner == next.humanSide
            notification(won ? .success : .error, preferences: preferences)
            guard preferences.celebrationsEnabled else { return }
            let cues = won
                ? ["chess_firework_low", "chess_firework_mid", "chess_firework_high"]
                : ["chess_glass_impact", "chess_glass_fracture", "chess_glass_shards"]
            for (index, cue) in cues.enumerated() {
                DispatchQueue.main.asyncAfter(deadline: .now() + Double(index) * 0.24) { [weak self] in
                    self?.play(cue, preferences: preferences)
                }
            }
        }
    }

    func preview(preferences: DrawlessChessModel.Preferences) {
        play("chess_move_wood", preferences: preferences)
    }

    private func crossedLowTime(previous: SharedGameView, next: SharedGameView) -> Bool {
        let previousMillis = previous.humanSide == "WHITE"
            ? previous.whiteRemainingMillis : previous.blackRemainingMillis
        let nextMillis = next.humanSide == "WHITE"
            ? next.whiteRemainingMillis : next.blackRemainingMillis
        return previousMillis > 10_000 && (0...10_000).contains(nextMillis)
    }

    private func play(_ prefix: String, preferences: DrawlessChessModel.Preferences) {
        guard preferences.soundEnabled, preferences.soundVolumePercent > 0 else { return }
        configureSessionIfNeeded()
        let resources = (Bundle.main.urls(forResourcesWithExtension: "m4a", subdirectory: nil) ?? [])
            .filter { $0.deletingPathExtension().lastPathComponent.hasPrefix(prefix) }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        guard !resources.isEmpty else { return }
        let index = nextVariant[prefix, default: 0] % resources.count
        nextVariant[prefix] = index + 1
        players.removeAll { !$0.isPlaying }
        guard let player = try? AVAudioPlayer(contentsOf: resources[index]) else { return }
        player.volume = Float(preferences.soundVolumePercent) / 100
        player.prepareToPlay()
        player.play()
        players.append(player)
    }

    private func configureSessionIfNeeded() {
        guard !configuredSession else { return }
        configuredSession = true
        try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    private func impact(
        _ style: UIImpactFeedbackGenerator.FeedbackStyle,
        preferences: DrawlessChessModel.Preferences
    ) {
        guard preferences.hapticsEnabled else { return }
        let generator = UIImpactFeedbackGenerator(style: style)
        generator.prepare()
        generator.impactOccurred()
    }

    private func notification(
        _ type: UINotificationFeedbackGenerator.FeedbackType,
        preferences: DrawlessChessModel.Preferences
    ) {
        guard preferences.hapticsEnabled else { return }
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(type)
    }
}
