import AVFoundation
import DrawlessShared
import UIKit

enum GameCompletionTimeline {
    static let victoryDuration: TimeInterval = 2.6
    static let defeatDuration: TimeInterval = 2.2
    static let victoryCueProgress: [Double] = [0.08, 0.30, 0.52]
    static let defeatCueProgress: [Double] = [0.04, 0.05, 0.36]
    static let checkmateFollowupDelay: TimeInterval = 1.0
    static let checkmateAudioSettleWithoutEffect: TimeInterval = 1.9
    static let completionAudioEndSafety: TimeInterval = 0.05

    // Keep the game route alive until the authored samples have finished, not merely until the
    // visual timeline ends. These durations match Android's 1.0.2 completion timeline.
    private static let victoryCueDurations: [Double] = [0.805, 2.175, 1.251]
    private static let defeatCueDurations: [Double] = [0.420, 0.840, 1.420]

    static func presentationDelay(
        won: Bool,
        isCheckmate: Bool,
        soundEnabled: Bool,
        celebrationsEnabled: Bool
    ) -> TimeInterval {
        guard isCheckmate, soundEnabled, celebrationsEnabled else { return 0 }
        let duration = won ? victoryDuration : defeatDuration
        let firstCue = won
            ? (victoryCueProgress.first ?? 0)
            : (defeatCueProgress.first ?? 0)
        return max(0, checkmateFollowupDelay - duration * firstCue)
    }

    static func effectStartUptime(
        completionDetectedAtUptime: TimeInterval,
        won: Bool,
        isCheckmate: Bool,
        soundEnabled: Bool,
        celebrationsEnabled: Bool
    ) -> TimeInterval {
        completionDetectedAtUptime + presentationDelay(
            won: won,
            isCheckmate: isCheckmate,
            soundEnabled: soundEnabled,
            celebrationsEnabled: celebrationsEnabled
        )
    }

    static func reviewNotBeforeUptime(
        completionDetectedAtUptime: TimeInterval,
        effectStartUptime: TimeInterval,
        won: Bool,
        isCheckmate: Bool,
        soundEnabled: Bool,
        celebrationsEnabled: Bool
    ) -> TimeInterval {
        guard celebrationsEnabled else {
            return completionDetectedAtUptime + (
                isCheckmate && soundEnabled ? checkmateAudioSettleWithoutEffect : 0
            )
        }

        let duration = won ? victoryDuration : defeatDuration
        let visualEnd = effectStartUptime + duration
        guard soundEnabled else { return visualEnd }

        let progress = won ? victoryCueProgress : defeatCueProgress
        let cueDurations = won ? victoryCueDurations : defeatCueDurations
        let audioEnd = zip(progress, cueDurations)
            .map { cueProgress, cueDuration in
                effectStartUptime + duration * cueProgress + cueDuration + completionAudioEndSafety
            }
            .max() ?? visualEnd
        return max(visualEnd, audioEnd)
    }
}

@MainActor
final class GameFeedback {
    private let audio = AudioFeedbackWorker()

    func process(
        previous: SharedGameView?,
        next: SharedGameView,
        preferences: DrawlessChessModel.Preferences,
        completionDetectedAtUptime: TimeInterval? = nil,
        minimumCompletionEffectStartUptime: TimeInterval? = nil
    ) {
        let newGame = previous?.gameId != next.gameId
        if newGame {
            audio.cancelCompletion()
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
            guard preferences.celebrationsEnabled,
                  preferences.soundEnabled,
                  preferences.soundVolumePercent > 0 else { return }
            let cues = won
                ? ["chess_firework_low", "chess_firework_mid", "chess_firework_high"]
                : ["chess_glass_impact", "chess_glass_fracture", "chess_glass_shards"]
            let duration = won
                ? GameCompletionTimeline.victoryDuration
                : GameCompletionTimeline.defeatDuration
            let cueProgress = won
                ? GameCompletionTimeline.victoryCueProgress
                : GameCompletionTimeline.defeatCueProgress
            let authoredEffectStartUptime = GameCompletionTimeline.effectStartUptime(
                completionDetectedAtUptime: completionDetectedAtUptime
                    ?? ProcessInfo.processInfo.systemUptime,
                won: won,
                isCheckmate: next.endReason == "CHECKMATE",
                soundEnabled: true,
                celebrationsEnabled: preferences.celebrationsEnabled
            )
            let effectStartUptime = max(
                authoredEffectStartUptime,
                minimumCompletionEffectStartUptime ?? authoredEffectStartUptime
            )
            audio.scheduleCompletion(
                cues: zip(cues, cueProgress).map { pair in
                    (prefix: pair.0, targetUptime: effectStartUptime + duration * pair.1)
                },
                volume: Float(preferences.soundVolumePercent) / 100
            )
        }
    }

    func cancelCompletion() {
        audio.cancelCompletion()
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
        audio.play(prefix: prefix, volume: Float(preferences.soundVolumePercent) / 100)
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

/// AVAudioSession and AVAudioPlayer setup can block for seconds when the audio route is changing
/// (and is especially slow in a virtualized simulator). Keep all audio ownership and work on one
/// serial queue so game state and board input never wait for the audio subsystem.
private final class AudioFeedbackWorker: @unchecked Sendable {
    private final class PreparedPlayer: @unchecked Sendable {
        let player: AVAudioPlayer

        init(_ player: AVAudioPlayer) {
            self.player = player
        }
    }

    private let queue = DispatchQueue(
        label: ["com.drawlesschess", "audio-feedback"].joined(separator: "."),
        qos: .userInitiated
    )
    private var players: [AVAudioPlayer] = []
    private var nextVariant: [String: Int] = [:]
    private var resourcesByPrefix: [String: [URL]] = [:]
    private var completionGeneration = 0
    private var configuredSession = false

    func play(prefix: String, volume: Float) {
        queue.async { [weak self] in
            self?.playOnQueue(prefix: prefix, volume: volume)
        }
    }

    func scheduleCompletion(
        cues: [(prefix: String, targetUptime: TimeInterval)],
        volume: Float
    ) {
        guard volume > 0 else { return }
        queue.async { [weak self] in
            guard let self else { return }
            completionGeneration &+= 1
            let generation = completionGeneration
            configureSessionIfNeeded()
            players.removeAll { !$0.isPlaying }
            // Resolve, decode, and prepare every cue now. The deadline block then contains only
            // the generation check and play(), avoiding first-use bundle/audio latency at the
            // exact firework or glass marker.
            let prepared = cues.compactMap { cue -> (PreparedPlayer, TimeInterval)? in
                guard let player = makePlayerOnQueue(prefix: cue.prefix, volume: volume) else {
                    return nil
                }
                return (PreparedPlayer(player), cue.targetUptime)
            }
            for (preparedPlayer, targetUptime) in prepared {
                let delay = max(0, targetUptime - ProcessInfo.processInfo.systemUptime)
                queue.asyncAfter(deadline: .now() + delay) { [weak self] in
                    guard let self, self.completionGeneration == generation else { return }
                    players.removeAll { !$0.isPlaying }
                    preparedPlayer.player.play()
                    players.append(preparedPlayer.player)
                }
            }
        }
    }

    func cancelCompletion() {
        queue.async { [weak self] in
            guard let self else { return }
            completionGeneration &+= 1
        }
    }

    private func playOnQueue(prefix: String, volume: Float) {
        configureSessionIfNeeded()
        players.removeAll { !$0.isPlaying }
        guard let player = makePlayerOnQueue(prefix: prefix, volume: volume) else { return }
        player.play()
        players.append(player)
    }

    private func makePlayerOnQueue(prefix: String, volume: Float) -> AVAudioPlayer? {
        let resources: [URL]
        if let cached = resourcesByPrefix[prefix] {
            resources = cached
        } else {
            let discovered = (Bundle.main.urls(forResourcesWithExtension: "m4a", subdirectory: nil) ?? [])
                .filter { $0.deletingPathExtension().lastPathComponent.hasPrefix(prefix) }
                .sorted { $0.lastPathComponent < $1.lastPathComponent }
            resourcesByPrefix[prefix] = discovered
            resources = discovered
        }
        guard !resources.isEmpty else { return nil }
        let index = nextVariant[prefix, default: 0] % resources.count
        nextVariant[prefix] = index + 1
        guard let player = try? AVAudioPlayer(contentsOf: resources[index]) else { return nil }
        player.volume = volume
        player.prepareToPlay()
        return player
    }

    private func configureSessionIfNeeded() {
        guard !configuredSession else { return }
        configuredSession = true
        try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
    }
}
