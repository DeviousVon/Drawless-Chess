import Combine
import DrawlessShared
import Foundation

@MainActor
final class DrawlessChessModel: ObservableObject {
    enum Route {
        case home
        case setup
        case game
        case review
        case options
        case statistics
    }

    struct Setup {
        var presetId = "drawless"
        var humanSideId = "random"
        var botLevelId = "casual"
        var clockMinutes = 0
        var incrementSeconds = 0
        var threatIndicationEnabled = false
    }

    struct BotLevel {
        let id: String
        let name: String
        let elo: Int
        let epithet: String
        let personality: String
        let portraitName: String
    }

    struct Preferences {
        var soundEnabled: Bool
        var soundVolumePercent: Int
        var hapticsEnabled: Bool
        var coordinatesEnabled: Bool
        var celebrationsEnabled: Bool
        var threatIndicationEnabled: Bool
        var boardThemeId: String
    }

    struct OpponentStatistics: Identifiable {
        let id: String
        let name: String
        let elo: Int
        let games: Int
        let wins: Int
        let losses: Int
        let averageScore: Double
        let winPercentage: Double
    }

    struct Statistics {
        var games: Int
        var wins: Int
        var losses: Int
        var totalScore: Int
        var averageScore: Double?
        var winPercentage: Double?
        var currentWinStreak: Int
        var bestWinStreak: Int
        var unassistedWins: Int
        var opponents: [OpponentStatistics]
        var adaptiveRating: Int
        var adaptiveGamesPlayed: Int
    }

    static let botLevels = [
        BotLevel(id: "adaptive", name: "Vesper", elo: 800, epithet: "Your Nemesis", personality: "Vesper watches, remembers, and always returns prepared.", portraitName: "opponent_adaptive"),
        BotLevel(id: "learner", name: "Mira", elo: 550, epithet: "Curious newcomer", personality: "Bright and fearless, Mira is happy to try any idea once.", portraitName: "opponent_learner"),
        BotLevel(id: "casual", name: "Theo", elo: 800, epithet: "Easygoing regular", personality: "Warm and observant, Theo enjoys a clever move and never takes a loss personally.", portraitName: "opponent_casual"),
        BotLevel(id: "challenger", name: "Rhea", elo: 1_000, epithet: "Playful competitor", personality: "Rhea meets every position like a dare—and loves when you push back.", portraitName: "opponent_challenger"),
        BotLevel(id: "club", name: "Mateo", elo: 1_300, epithet: "Club storyteller", personality: "Patient and good-humored, Mateo always has a story ready after the game.", portraitName: "opponent_club"),
        BotLevel(id: "expert", name: "Yuna", elo: 1_675, epithet: "Quiet analyst", personality: "Precise and dryly funny, Yuna lets the board do most of the talking.", portraitName: "opponent_expert"),
        BotLevel(id: "master", name: "Amara", elo: 2_100, epithet: "Unshakable strategist", personality: "Disciplined, gracious, and completely at home under pressure.", portraitName: "opponent_master"),
        BotLevel(id: "grandmaster", name: "Lucian", elo: 2_550, epithet: "Courteous grandmaster", personality: "Sparse with words, generous in victory, and focused from the first move.", portraitName: "opponent_grandmaster"),
    ]

    static let boardThemes = [
        (id: "imperial_marble", name: "Imperial Marble"),
        (id: "desert_sandstone", name: "Desert Sandstone"),
        (id: "glacier_slate", name: "Glacier Slate"),
        (id: "verdigris_copper", name: "Verdigris Copper"),
        (id: "amethyst_geode", name: "Amethyst Geode"),
    ]

    @Published var route: Route = .home
    @Published var setup = Setup()
    @Published private(set) var game: SharedGameView?
    @Published private(set) var hintText: String?
    @Published var preferences: Preferences
    @Published private(set) var statistics: Statistics
    @Published private(set) var hasResumableGame: Bool

    private var runtime: SharedGameRuntime?
    private var recordedGameRevision: Int64?
    private var persistedCheckpointRevision: Int64?
    private var automaticReviewGameId: String?
    private var completedGames: [CompletedGameRecord]
    private let legacyStatistics: LegacyStatistics
    private let feedback = GameFeedback()
    private let defaults = UserDefaults.standard
    private let checkpointKey = "activeGame.checkpoint.v1"
    private let completedGamesKey = "completedGames.history.v1"

    init() {
        preferences = Preferences(
            soundEnabled: defaults.object(forKey: "soundEnabled") as? Bool ?? true,
            soundVolumePercent: defaults.object(forKey: "soundVolumePercent") as? Int ?? 50,
            hapticsEnabled: defaults.object(forKey: "hapticsEnabled") as? Bool ?? true,
            coordinatesEnabled: defaults.object(forKey: "coordinatesEnabled") as? Bool ?? true,
            celebrationsEnabled: defaults.object(forKey: "celebrationsEnabled") as? Bool ?? true,
            threatIndicationEnabled: defaults.object(forKey: "threatIndicationEnabled") as? Bool ?? false,
            boardThemeId: defaults.string(forKey: "boardThemeId") ?? "imperial_marble"
        )
        completedGames = Self.loadCompletedGames(defaults: defaults)
        legacyStatistics = Self.loadOrCreateLegacyStatistics(
            defaults: defaults,
            records: completedGames
        )
        statistics = Self.calculateStatistics(
            records: completedGames,
            legacy: legacyStatistics
        )
        hasResumableGame = defaults.string(forKey: checkpointKey) != nil
        if let savedOpponent = defaults.string(forKey: "quickPlayOpponentId"),
           Self.botLevels.contains(where: { $0.id == savedOpponent }) {
            setup.botLevelId = savedOpponent
        }
    }

    var opponentName: String {
        Self.botLevels.first(where: { $0.id == setup.botLevelId })?.name ?? "Opponent"
    }

    var opponentLevels: [BotLevel] {
        Self.botLevels.map { level in
            guard level.id == "adaptive" else { return level }
            return BotLevel(
                id: level.id,
                name: level.name,
                elo: statistics.adaptiveRating,
                epithet: level.epithet,
                personality: level.personality,
                portraitName: level.portraitName
            )
        }
    }

    var rulesName: String { setup.presetId == "escape" ? "Escape rules" : "Drawless rules" }

    func startQuickPlay() {
        setup = Setup(
            presetId: "drawless",
            humanSideId: Bool.random() ? "white" : "black",
            botLevelId: setup.botLevelId,
            clockMinutes: 0,
            incrementSeconds: 0,
            threatIndicationEnabled: preferences.threatIndicationEnabled
        )
        startConfiguredGame()
    }

    func startConfiguredGame() {
        runtime?.close()
        defaults.set(setup.botLevelId, forKey: "quickPlayOpponentId")
        let resolvedSide = setup.humanSideId == "random"
            ? (Bool.random() ? "white" : "black")
            : setup.humanSideId
        let created = SharedGameRuntime(
            presetId: setup.presetId,
            humanSideId: resolvedSide,
            botLevelId: setup.botLevelId,
            initialMillis: Int64(setup.clockMinutes * 60_000),
            incrementMillis: Int64(setup.incrementSeconds * 1_000),
            threatIndicationEnabled: setup.threatIndicationEnabled,
            checkpointJson: nil,
            boardThemeId: preferences.boardThemeId,
            adaptiveElo: Int32(statistics.adaptiveRating)
        )
        runtime = created
        recordedGameRevision = nil
        persistedCheckpointRevision = nil
        automaticReviewGameId = nil
        hintText = nil
        created.setGameForeground(foreground: true)
        apply(created.view())
        route = .game
    }

    func resumeSavedGame() {
        guard let payload = defaults.string(forKey: checkpointKey) else {
            hasResumableGame = false
            return
        }
        runtime?.close()
        let created = SharedGameRuntime(
            presetId: "drawless",
            humanSideId: "white",
            botLevelId: "casual",
            initialMillis: 0,
            incrementMillis: 0,
            threatIndicationEnabled: false,
            checkpointJson: payload,
            boardThemeId: preferences.boardThemeId,
            adaptiveElo: Int32(statistics.adaptiveRating)
        )
        runtime = created
        recordedGameRevision = nil
        persistedCheckpointRevision = nil
        automaticReviewGameId = nil
        hintText = nil
        created.setGameForeground(foreground: true)
        let restored = created.view()
        setup.presetId = restored.presetId.lowercased()
        setup.humanSideId = restored.humanSide.lowercased()
        setup.botLevelId = restored.opponentLevelId
        setup.clockMinutes = Int(restored.initialMillis / 60_000)
        setup.incrementSeconds = Int(restored.incrementMillis / 1_000)
        apply(restored)
        route = .game
    }

    func discardSavedGame() {
        defaults.removeObject(forKey: checkpointKey)
        hasResumableGame = false
    }

    func forfeitSavedGame() {
        guard let payload = defaults.string(forKey: checkpointKey) else { return }
        runtime?.close()
        let forfeitedRuntime = SharedGameRuntime(
            presetId: "drawless",
            humanSideId: "white",
            botLevelId: "casual",
            initialMillis: 0,
            incrementMillis: 0,
            threatIndicationEnabled: false,
            checkpointJson: payload,
            boardThemeId: preferences.boardThemeId,
            adaptiveElo: Int32(statistics.adaptiveRating)
        )
        let result = forfeitedRuntime.resign()
        recordCompletedGame(result)
        forfeitedRuntime.close()
        defaults.removeObject(forKey: checkpointKey)
        defaults.set(statistics.games, forKey: "stats.games")
        defaults.set(statistics.wins, forKey: "stats.wins")
        defaults.set(statistics.losses, forKey: "stats.losses")
        defaults.set(statistics.totalScore, forKey: "stats.totalScore")
        hasResumableGame = false
        game = nil
        runtime = nil
    }

    func retryOpponent() {
        guard let runtime else { return }
        let payload = runtime.checkpointJson()
        runtime.close()
        let recovered = SharedGameRuntime(
            presetId: "drawless",
            humanSideId: "white",
            botLevelId: "casual",
            initialMillis: 0,
            incrementMillis: 0,
            threatIndicationEnabled: false,
            checkpointJson: payload,
            boardThemeId: preferences.boardThemeId,
            adaptiveElo: Int32(statistics.adaptiveRating)
        )
        self.runtime = recovered
        persistedCheckpointRevision = nil
        automaticReviewGameId = nil
        recovered.setGameForeground(foreground: true)
        apply(recovered.view())
    }

    func setGameForeground(_ foreground: Bool) {
        runtime?.setGameForeground(foreground: foreground)
    }

    func startReview() {
        guard let runtime else { return }
        apply(runtime.startReview())
    }

    func showReview() {
        guard game?.reviewAvailable == true else { return }
        if game?.reviewMoves.isEmpty != false && game?.reviewInProgress != true {
            startReview()
        }
        route = .review
    }

    func leaveReview() {
        route = .game
    }

    func tap(_ index: Int32) {
        guard let runtime else { return }
        hintText = nil
        apply(runtime.tap(displayIndex: index))
    }

    func choosePromotion(_ choice: String) {
        guard let runtime else { return }
        apply(runtime.choosePromotion(pieceType: choice))
    }

    func requestHint() {
        guard let runtime else { return }
        apply(runtime.requestHint())
    }

    func refreshGame() {
        guard route == .game || route == .review, let runtime else { return }
        apply(runtime.view())
    }

    func undo() {
        guard let runtime else { return }
        hintText = nil
        apply(runtime.undo())
    }

    func pause() {
        guard let runtime else { return }
        apply(runtime.pause())
    }

    func resume() {
        guard let runtime else { return }
        apply(runtime.resume())
    }

    func resign() {
        guard let runtime else { return }
        apply(runtime.resign())
    }

    func flipBoard() {
        guard let runtime else { return }
        apply(runtime.flipBoard())
    }

    func exitGame() {
        runtime?.close()
        runtime = nil
        game = nil
        automaticReviewGameId = nil
        hintText = nil
        route = .home
    }

    func savePreferencesAndGoHome() {
        defaults.set(preferences.soundEnabled, forKey: "soundEnabled")
        defaults.set(preferences.soundVolumePercent, forKey: "soundVolumePercent")
        defaults.set(preferences.hapticsEnabled, forKey: "hapticsEnabled")
        defaults.set(preferences.coordinatesEnabled, forKey: "coordinatesEnabled")
        defaults.set(preferences.celebrationsEnabled, forKey: "celebrationsEnabled")
        defaults.set(preferences.threatIndicationEnabled, forKey: "threatIndicationEnabled")
        defaults.set(preferences.boardThemeId, forKey: "boardThemeId")
        setup.threatIndicationEnabled = preferences.threatIndicationEnabled
        route = .home
    }

    func previewSound() {
        feedback.preview(preferences: preferences)
    }

    private func apply(_ next: SharedGameView) {
        let previous = game
        game = next
        hintText = next.hintMove
        feedback.process(previous: previous, next: next, preferences: preferences)
        guard let runtime else { return }
        let revision = runtime.checkpointRevision()
        if next.phase == "COMPLETED" {
            defaults.removeObject(forKey: checkpointKey)
            hasResumableGame = false
            persistedCheckpointRevision = nil
        } else if persistedCheckpointRevision != revision {
            defaults.set(runtime.checkpointJson(), forKey: checkpointKey)
            hasResumableGame = true
            persistedCheckpointRevision = revision
        }
        guard next.phase == "COMPLETED" else { return }
        guard recordedGameRevision != revision else { return }
        recordedGameRevision = revision
        recordCompletedGame(next)
        defaults.set(statistics.games, forKey: "stats.games")
        defaults.set(statistics.wins, forKey: "stats.wins")
        defaults.set(statistics.losses, forKey: "stats.losses")
        defaults.set(statistics.totalScore, forKey: "stats.totalScore")
        if next.reviewAvailable, automaticReviewGameId != next.gameId {
            automaticReviewGameId = next.gameId
            game = runtime.startReview()
        }
    }

    private func recordCompletedGame(_ game: SharedGameView) {
        guard !completedGames.contains(where: { $0.gameId == game.gameId }) else { return }
        let level = Self.botLevels.first(where: { $0.id == game.opponentLevelId }) ?? Self.botLevels[2]
        let eligibleAdaptiveResult = level.id == "adaptive" &&
            game.hintCount == 0 && game.undoCount == 0 && game.pauseCount == 0 &&
            !game.threatIndicationEnabled
        let ratingBefore = eligibleAdaptiveResult ? statistics.adaptiveRating : nil
        let ratingAfter = ratingBefore.map {
            Self.updatedAdaptiveRating(
                rating: $0,
                gamesPlayed: statistics.adaptiveGamesPlayed,
                opponentElo: Int(game.opponentElo),
                playerWon: game.winner == game.humanSide
            )
        }
        completedGames.append(CompletedGameRecord(
            gameId: game.gameId,
            completedAtEpochMillis: Int64(Date().timeIntervalSince1970 * 1_000),
            opponentId: level.id,
            opponentName: level.name,
            opponentElo: Int(game.opponentElo),
            rulesPreset: game.presetId,
            humanSide: game.humanSide,
            winnerSide: game.winner ?? "",
            endReason: game.endReason ?? "UNKNOWN",
            plyCount: Int(game.plyCount),
            score: Int(game.score),
            hints: Int(game.hintCount),
            undos: Int(game.undoCount),
            pauses: Int(game.pauseCount),
            threatIndication: game.threatIndicationEnabled,
            playerRatingBefore: ratingBefore,
            playerRatingAfter: ratingAfter
        ))
        if let payload = try? JSONEncoder().encode(completedGames) {
            defaults.set(payload, forKey: completedGamesKey)
        }
        statistics = Self.calculateStatistics(records: completedGames, legacy: legacyStatistics)
    }

    private static func loadCompletedGames(defaults: UserDefaults) -> [CompletedGameRecord] {
        guard let payload = defaults.data(forKey: "completedGames.history.v1") else { return [] }
        return (try? JSONDecoder().decode([CompletedGameRecord].self, from: payload)) ?? []
    }

    private static func loadOrCreateLegacyStatistics(
        defaults: UserDefaults,
        records: [CompletedGameRecord]
    ) -> LegacyStatistics {
        let baselineKey = "stats.legacyBaseline.v1"
        if let payload = defaults.data(forKey: baselineKey),
           let decoded = try? JSONDecoder().decode(LegacyStatistics.self, from: payload) {
            return decoded
        }

        let recordedWins = records.filter(\.playerWon).count
        let recordedScore = records.map(\.score).reduce(0, +)
        let baseline = LegacyStatistics(
            games: max(0, defaults.integer(forKey: "stats.games") - records.count),
            wins: max(0, defaults.integer(forKey: "stats.wins") - recordedWins),
            losses: max(0, defaults.integer(forKey: "stats.losses") - (records.count - recordedWins)),
            totalScore: max(0, defaults.integer(forKey: "stats.totalScore") - recordedScore)
        )
        if let payload = try? JSONEncoder().encode(baseline) {
            defaults.set(payload, forKey: baselineKey)
        }
        return baseline
    }

    private static func calculateStatistics(
        records: [CompletedGameRecord],
        legacy: LegacyStatistics
    ) -> Statistics {
        let recordWins = records.filter(\.playerWon).count
        var currentStreak = 0
        var runningStreak = 0
        var bestStreak = 0
        records.forEach { record in
            if record.playerWon {
                runningStreak += 1
                bestStreak = max(bestStreak, runningStreak)
            } else {
                runningStreak = 0
            }
        }
        currentStreak = runningStreak
        let opponents = Dictionary(grouping: records, by: \.opponentId).map { id, games in
            let wins = games.filter(\.playerWon).count
            return OpponentStatistics(
                id: id,
                name: games.last?.opponentName ?? id,
                elo: games.last?.opponentElo ?? 0,
                games: games.count,
                wins: wins,
                losses: games.count - wins,
                averageScore: games.map { Double($0.score) }.reduce(0, +) / Double(games.count),
                winPercentage: Double(wins) * 100 / Double(games.count)
            )
        }.sorted { $0.elo < $1.elo }
        let games = records.count + legacy.games
        let wins = recordWins + legacy.wins
        let losses = records.count - recordWins + legacy.losses
        let score = records.map(\.score).reduce(0, +) + legacy.totalScore
        var adaptiveRating = 800
        var adaptiveGamesPlayed = 0
        records.forEach { record in
            guard record.opponentId == "adaptive", record.unassisted else { return }
            let before = record.playerRatingBefore ?? adaptiveRating
            adaptiveRating = record.playerRatingAfter ?? updatedAdaptiveRating(
                rating: before,
                gamesPlayed: adaptiveGamesPlayed,
                opponentElo: record.opponentElo,
                playerWon: record.playerWon
            )
            adaptiveGamesPlayed += 1
        }
        return Statistics(
            games: games,
            wins: wins,
            losses: losses,
            totalScore: score,
            averageScore: games > 0 ? Double(score) / Double(games) : nil,
            winPercentage: games > 0 ? Double(wins) * 100 / Double(games) : nil,
            currentWinStreak: currentStreak,
            bestWinStreak: bestStreak,
            unassistedWins: records.filter { $0.playerWon && $0.unassisted }.count,
            opponents: opponents,
            adaptiveRating: adaptiveRating,
            adaptiveGamesPlayed: adaptiveGamesPlayed
        )
    }

    private static func updatedAdaptiveRating(
        rating: Int,
        gamesPlayed: Int,
        opponentElo: Int,
        playerWon: Bool
    ) -> Int {
        let expected = 1.0 / (1.0 + pow(10.0, Double(opponentElo - rating) / 400.0))
        let k: Double = gamesPlayed < 10 ? 48.0 : (gamesPlayed < 30 ? 32.0 : 20.0)
        let result = playerWon ? 1.0 : 0.0
        return min(2_850, max(500, Int(floor(Double(rating) + k * (result - expected) + 0.5))))
    }

    private struct LegacyStatistics: Codable {
        let games: Int
        let wins: Int
        let losses: Int
        let totalScore: Int
    }

    private struct CompletedGameRecord: Codable {
        let gameId: String
        let completedAtEpochMillis: Int64
        let opponentId: String
        let opponentName: String
        let opponentElo: Int
        let rulesPreset: String
        let humanSide: String
        let winnerSide: String
        let endReason: String
        let plyCount: Int
        let score: Int
        let hints: Int
        let undos: Int
        let pauses: Int
        let threatIndication: Bool
        let playerRatingBefore: Int?
        let playerRatingAfter: Int?

        var playerWon: Bool { winnerSide == humanSide }
        var unassisted: Bool { hints == 0 && undos == 0 && pauses == 0 && !threatIndication }
    }
}
