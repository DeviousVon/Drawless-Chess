import Combine
import DrawlessShared
import Foundation
#if DEBUG
import Darwin
import os
#endif

@MainActor
final class DrawlessChessModel: ObservableObject {
    enum Route {
        case home
        case setup
        case game
#if DRAWLESS_IOS_GAME_REVIEW
        case review
#endif
        case options
        case statistics
    }

    struct Setup {
        var presetId = "drawless"
        var deadPositionId = "material"
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

    struct CompletionPresentation: Equatable, Identifiable {
        let id: String
        let won: Bool
        let humanSide: String
        let opponentLevelId: String
        let effectStartUptime: TimeInterval
        let reviewNotBeforeUptime: TimeInterval
    }

    struct BotMovePresentation: Equatable, Identifiable {
        struct Piece: Equatable, Identifiable {
            let fromSquare: String
            let toSquare: String
            let pieceCode: String

            var id: String { "\(fromSquare)-\(toSquare)-\(pieceCode)" }
        }

        let id: String
        let gameId: String
        let ply: Int32
        let pieces: [Piece]
        let startedAtUptime: TimeInterval
        let duration: TimeInterval

        var endsAtUptime: TimeInterval { startedAtUptime + duration }

        func hidesDestination(_ square: String) -> Bool {
            pieces.contains { $0.toSquare == square }
        }
    }

    struct OpponentStatistics: Identifiable, Sendable {
        let id: String
        let name: String
        let elo: Int
        let games: Int
        let wins: Int
        let losses: Int
        let averageScore: Double
        let winPercentage: Double
    }

    struct Statistics: Sendable {
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

    private static let sharedDifficultyCatalog = BotDifficultyCatalog.shared

    private static func sharedOpponentElo(_ id: String) -> Int {
        if id == sharedDifficultyCatalog.ADAPTIVE_LEVEL_ID {
            return Int(sharedDifficultyCatalog.ADAPTIVE_STARTING_ELO)
        }
        return Int(sharedDifficultyCatalog.named(id: id).approximateElo)
    }

    static let botLevels = [
        BotLevel(id: "adaptive", name: "Vesper", elo: sharedOpponentElo("adaptive"), epithet: "Your Nemesis", personality: "Vesper watches, remembers, and always returns prepared.", portraitName: "opponent_adaptive"),
        BotLevel(id: "learner", name: "Mira", elo: sharedOpponentElo("learner"), epithet: "Curious newcomer", personality: "Bright and fearless, Mira is happy to try any idea once.", portraitName: "opponent_learner"),
        BotLevel(id: "casual", name: "Theo", elo: sharedOpponentElo("casual"), epithet: "Easygoing regular", personality: "Warm and observant, Theo enjoys a clever move and never takes a loss personally.", portraitName: "opponent_casual"),
        BotLevel(id: "challenger", name: "Rhea", elo: sharedOpponentElo("challenger"), epithet: "Playful competitor", personality: "Rhea meets every position like a dare—and loves when you push back.", portraitName: "opponent_challenger"),
        BotLevel(id: "club", name: "Mateo", elo: sharedOpponentElo("club"), epithet: "Club storyteller", personality: "Patient and good-humored, Mateo always has a story ready after the game.", portraitName: "opponent_club"),
        BotLevel(id: "expert", name: "Yuna", elo: sharedOpponentElo("expert"), epithet: "Quiet analyst", personality: "Precise and dryly funny, Yuna lets the board do most of the talking.", portraitName: "opponent_expert"),
        BotLevel(id: "master", name: "Amara", elo: sharedOpponentElo("master"), epithet: "Unshakable strategist", personality: "Disciplined, gracious, and completely at home under pressure.", portraitName: "opponent_master"),
        BotLevel(id: "grandmaster", name: "Lucian", elo: sharedOpponentElo("grandmaster"), epithet: "Courteous grandmaster", personality: "Sparse with words, generous in victory, and focused from the first move.", portraitName: "opponent_grandmaster"),
    ]

    static let boardThemes = [
        (id: "imperial_marble", name: "Imperial Marble"),
        (id: "desert_sandstone", name: "Desert Sandstone"),
        (id: "glacier_slate", name: "Glacier Slate"),
        (id: "verdigris_copper", name: "Verdigris Copper"),
        (id: "celestial_observatory", name: "Celestial Observatory"),
        (id: "halloween_emberwood", name: "Emberwood Court"),
        (id: "halloween_witchglass", name: "Witchglass"),
    ]

    @Published var route: Route = .home {
        didSet { updateGameForegroundState() }
    }
    @Published var setup = Setup()
    @Published private(set) var game: SharedGameView?
    @Published private(set) var hintText: String?
    @Published var preferences: Preferences
    @Published private(set) var statistics: Statistics
    @Published private(set) var hasResumableGame: Bool
    @Published private(set) var completionPresentation: CompletionPresentation?
    @Published private(set) var botMovePresentation: BotMovePresentation?
    @Published private(set) var quickPlayOpponentId = "casual"
    @Published private(set) var reviewReady = false
    @Published private(set) var reviewInProgress = false
    @Published private(set) var reviewPreparationPhase = "IDLE"
    @Published private(set) var postGameReviewTapReady = false
#if DEBUG
    @Published private(set) var latencyProbeAccessibilityValue = ""
    @Published private(set) var botMoveAnimationAccessibilityValue = "state=idle"
    @Published private(set) var botMoveResultOrderingAccessibilityValue =
        "resultSurfaces=none;resultOverlap=0"
#endif
    private(set) var gameSnapshotDate = Date()

    private var runtime: SharedGameRuntime? {
        didSet { runtimeGameForeground = nil }
    }
    private var runtimeGameForeground: Bool?
    private var recordedGameRevision: Int64?
    private var persistedCheckpointRevision: Int64?
    private var preservedCompletedCheckpointRevision: Int64?
    private var presentedRuntimeRevision: Int64?
    private var automaticReviewGameId: String?
    private var postGameReviewHandledGameId: String?
    private var postGameReviewGateTask: Task<Void, Never>?
    private var botMovePresentationTask: Task<Void, Never>?
    private var botMoveRenderSampleCount = 0
    private var botMoveRenderSawMidpoint = false
    private var botMoveRenderMonotonic = true
    private var botMoveRenderLastRawProgress = -1.0
    private var botMoveTaskStartedMillis: Int?
    private var botMoveFirstFrameMillis: Int?
    private var botMoveRawOneMillis: Int?
    private var botMoveMaxFrameGapMillis = 0
    private var botMoveLastFrameUptime: TimeInterval?
    private var sceneAllowsGameWork = false
    private var gameVisible = false
    private var completedGames: [CompletedGameRecord]
    private let legacyStatistics: LegacyStatistics
    private let feedback = GameFeedback()
    private let defaults: UserDefaults
    private let checkpointPersistence: ActiveCheckpointPersistence
    private static let checkpointKey = "activeGame.checkpoint.v1"
    private static let checkpointClearTombstoneKey = "activeGame.checkpoint.v1.cleared"
    private static let completedReviewCheckpointKey = "completedReview.checkpoint.v1"
    private let completedGamesKey = "completedGames.history.v1"
    private static let completedGamePersistenceQueue = DispatchQueue(
        label: "com.drawlesschess.completed-game-persistence",
        qos: .utility
    )
    private static let botMoveAnimationDuration: TimeInterval = 0.5
#if DEBUG
    private static let liveReviewDiagnosticsQueue = DispatchQueue(
        label: "com.drawlesschess.live-review-diagnostics",
        qos: .utility
    )
    private static let reviewAuditPersistenceQueue = DispatchQueue(
        label: "com.drawlesschess.review-audit-persistence",
        qos: .utility
    )
    private let moveLatencyProbe = MoveLatencyProbe()
    private var latencyProbeMetricsValue = ""
    private var latencyProbeEngineActivity = ""
    private var latencyProbeReviewPreparationActivity = ""
    private var latencyProbeCheckpointActivity = ""
    private var latencyProbeReviewReuse = ""
    private var latencyProbePlyCount = 0
    private var latencyProbePublicationDeferred = false
    private var lastLiveReviewDiagnosticsState = ""
    private var botMoveResultSurfaces = Set<String>()
    private var botMoveResultOverlap = false
#endif

    init() {
        let defaults = UserDefaults.standard
        if defaults.string(forKey: ReleaseChannel.markerPreferenceKey) != ReleaseChannel.marker {
            defaults.set(ReleaseChannel.marker, forKey: ReleaseChannel.markerPreferenceKey)
        }
        // A terminal clear writes this tiny tombstone synchronously before its potentially large
        // checkpoint removal is serialized. Recovering it here prevents an abrupt termination in
        // that interval from resurrecting a completed game.
        if defaults.bool(forKey: Self.checkpointClearTombstoneKey) {
            defaults.removeObject(forKey: Self.checkpointKey)
            defaults.removeObject(forKey: Self.checkpointClearTombstoneKey)
        }
        if ReleaseChannel.gameReviewEnabled {
            // A completed game's evidence remains resumable until the player leaves Review. This
            // prevents a suspension or process restart after checkmate from discarding all
            // foreground work and forcing a new final analysis.
            if defaults.string(forKey: Self.checkpointKey) == nil,
               let completedReview = defaults.string(forKey: Self.completedReviewCheckpointKey) {
                defaults.set(completedReview, forKey: Self.checkpointKey)
            }
        } else {
            // A Debug/private Review checkpoint must never surface as a resumable game after the
            // App Store core-only channel is installed over a test build.
            CoreReleaseCheckpointMigration.apply(
                defaults: defaults,
                activeKey: Self.checkpointKey,
                completedReviewKey: Self.completedReviewCheckpointKey
            )
        }
#if DEBUG
        // XCTest's command-line defaults parser treats a JSON object as a property-list value,
        // so an exact checkpoint supplied as an argument is not reliably returned by
        // string(forKey:). Use an explicit debug-only environment seam for deterministic
        // resume positions; production launches never set or read it.
        if let testCheckpoint = ProcessInfo.processInfo.environment[
            "DRAWLESS_XCTEST_ACTIVE_CHECKPOINT_JSON"
        ] {
            defaults.set(testCheckpoint, forKey: Self.checkpointKey)
        }
#endif
        self.defaults = defaults
        checkpointPersistence = ActiveCheckpointPersistence(
            defaults: defaults,
            key: Self.checkpointKey,
            clearTombstoneKey: Self.checkpointClearTombstoneKey
        )
        let savedBoardThemeId = defaults.string(forKey: "boardThemeId") ?? "imperial_marble"
        let boardThemeId = Self.normalizedBoardThemeId(savedBoardThemeId)
        if boardThemeId != savedBoardThemeId {
            defaults.set(boardThemeId, forKey: "boardThemeId")
        }
        preferences = Preferences(
            soundEnabled: defaults.object(forKey: "soundEnabled") as? Bool ?? true,
            soundVolumePercent: defaults.object(forKey: "soundVolumePercent") as? Int ?? 50,
            hapticsEnabled: defaults.object(forKey: "hapticsEnabled") as? Bool ?? true,
            coordinatesEnabled: defaults.object(forKey: "coordinatesEnabled") as? Bool ?? true,
            celebrationsEnabled: defaults.object(forKey: "celebrationsEnabled") as? Bool ?? true,
            threatIndicationEnabled: defaults.object(forKey: "threatIndicationEnabled") as? Bool ?? false,
            boardThemeId: boardThemeId
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
        hasResumableGame = defaults.string(forKey: Self.checkpointKey) != nil
        if let savedOpponent = defaults.string(forKey: "quickPlayOpponentId"),
           Self.botLevels.contains(where: { $0.id == savedOpponent }) {
            quickPlayOpponentId = savedOpponent
        }
        setup.botLevelId = quickPlayOpponentId
#if DEBUG
        if ProcessInfo.processInfo.environment["DRAWLESS_PHYSICAL_REVIEW_PROBE"] == "1" {
            Task.detached(priority: .userInitiated) {
                await Self.runPhysicalReviewProbe()
            }
        }
#endif
    }

#if DEBUG
    /**
     * Executes the realistic multi-move review handoff on a physical device when its newer iOS
     * version cannot be selected as an XCTest destination by this recovery VM's Xcode. The probe
     * owns a separate in-memory runtime and never reads or writes the player's saved game or stats.
     */
    nonisolated private static func runPhysicalReviewProbe() async {
        let runtime = SharedGameRuntime(
            presetId: "drawless",
            deadPositionId: "material",
            humanSideId: "white",
            botLevelId: "casual",
            initialMillis: 0,
            incrementMillis: 0,
            threatIndicationEnabled: false,
            checkpointJson: physicalReviewProbeCheckpoint,
            boardThemeId: "imperial_marble",
            adaptiveElo: 800
        )
        func finish(_ verdict: String, _ detail: String, code: Int32) -> Never {
            let report = "DRAWLESS_PHYSICAL_REVIEW_PROBE \(verdict) \(detail)"
            if let documents = FileManager.default.urls(
                for: .documentDirectory,
                in: .userDomainMask
            ).first {
                try? report.write(
                    to: documents.appendingPathComponent("physical-review-probe.txt"),
                    atomically: true,
                    encoding: .utf8
                )
            }
            runtime.close()
            NSLog("%@", report)
            print(report)
            fflush(stdout)
            Darwin.exit(code)
        }

        runtime.setGameForeground(foreground: true)
        let prefetchDeadline = Date().addingTimeInterval(30)
        var diagnostics = runtime.reviewReuseDiagnosticsForTesting()
        while Date() < prefetchDeadline {
            _ = runtime.presentationRevision()
            diagnostics = runtime.reviewReuseDiagnosticsForTesting()
            let fields = physicalProbeFields(diagnostics)
            if fields["prefetchRoots"] == "4" && fields["prefetchPending"] == "0" {
                break
            }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        var fields = physicalProbeFields(diagnostics)
        guard fields["prefetchRoots"] == "4", fields["prefetchPending"] == "0" else {
            finish("FAIL", "backlog_not_drained;\(diagnostics)", code: 2)
        }

        let completed = runtime.resign()
        guard completed.phase == "COMPLETED" else {
            finish("FAIL", "game_not_completed;\(diagnostics)", code: 3)
        }
        _ = runtime.ensureReviewStarted()

        let reviewDeadline = Date().addingTimeInterval(15)
        var status = runtime.reviewStatus()
        while Date() < reviewDeadline && !status.ready && status.error == nil {
            try? await Task.sleep(nanoseconds: 50_000_000)
            status = runtime.reviewStatus()
        }
        diagnostics = runtime.reviewReuseDiagnosticsForTesting()
        fields = physicalProbeFields(diagnostics)
        guard status.ready,
              fields["reviewGeneration"] == "1",
              fields["reviewPlanRoots"] == "3",
              fields["reviewSeededRoots"] == "3",
              fields["reviewPostGameSearches"] == "0",
              fields["reviewReady"] == "true" else {
            finish("FAIL", "review_not_ready;\(diagnostics)", code: 4)
        }
        finish("PASS", diagnostics, code: 0)
    }

    nonisolated private static func physicalProbeFields(_ value: String) -> [String: String] {
        Dictionary(uniqueKeysWithValues: value.split(separator: ";").compactMap { field in
            let parts = field.split(separator: "=", maxSplits: 1)
            guard parts.count == 2 else { return nil }
            return (String(parts[0]), String(parts[1]))
        })
    }

    nonisolated private static let physicalReviewProbeCheckpoint = #"{"formatVersion":1,"revision":6,"config":{"gameId":"ios-physical-multi-move-review-backlog","initialFen":"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1","rules":{"schemaVersion":1,"preset":"DRAWLESS","stalemate":"TRAPPED_PLAYER_LOSES","deadPosition":"MATERIAL_VICTORY","bareKing":"BARE_KING_LOSES","fiftyMove":"MATERIAL_VICTORY","repetitionThreshold":3,"completingPlayerLosesRepetition":true,"forcedRepetitionException":true,"materialValues":{"pawn":1,"knight":3,"bishop":3,"rook":5,"queen":9}},"mode":"CASUAL","timeControl":{"kind":"UNTIMED"},"humanSide":"WHITE","engineStrength":{"kind":"APPROXIMATE_ELO","value":800},"opponentLevelId":"casual","engineLimits":{"moveTimeMillis":350,"multiPv":1}},"moves":["e2e4","e7e5","g1f3","b8c6","f1b5","a7a6"],"currentFen":"r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4","outcome":null,"clock":{"whiteRemainingMillis":null,"blackRemainingMillis":null,"runningSide":null,"startedAtMonotonicMillis":null,"startedAtEpochMillis":null,"paused":false},"moveClocks":[{"ply":1,"whiteRemainingMillis":null,"blackRemainingMillis":null},{"ply":2,"whiteRemainingMillis":null,"blackRemainingMillis":null},{"ply":3,"whiteRemainingMillis":null,"blackRemainingMillis":null},{"ply":4,"whiteRemainingMillis":null,"blackRemainingMillis":null},{"ply":5,"whiteRemainingMillis":null,"blackRemainingMillis":null},{"ply":6,"whiteRemainingMillis":null,"blackRemainingMillis":null}],"assistance":{"hints":0,"undos":0,"pauses":0,"threatIndication":false}}"#
#endif

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

    func selectQuickPlayOpponent(_ id: String) {
        guard Self.botLevels.contains(where: { $0.id == id }) else { return }
        quickPlayOpponentId = id
        defaults.set(id, forKey: "quickPlayOpponentId")
    }

    func showCustomGameSetup() {
        setup = Setup(
            presetId: "drawless",
            deadPositionId: "material",
            humanSideId: "random",
            botLevelId: quickPlayOpponentId,
            clockMinutes: 0,
            incrementSeconds: 0,
            threatIndicationEnabled: preferences.threatIndicationEnabled
        )
        route = .setup
    }

    func startQuickPlay() {
        setup = Setup(
            presetId: "drawless",
            deadPositionId: "material",
            humanSideId: Bool.random() ? "white" : "black",
            botLevelId: quickPlayOpponentId,
            clockMinutes: 0,
            incrementSeconds: 0,
            threatIndicationEnabled: preferences.threatIndicationEnabled
        )
        startConfiguredGame()
    }

    func startConfiguredGame() {
        dismissCompletionPresentation()
        cancelBotMovePresentation()
        resetPostGameReviewFlow()
#if DEBUG
        if let runtime {
            drainReviewAuditEvents(runtime, modelEvent: "game-replaced", forceFlush: true)
        }
#endif
        runtime?.close()
        checkpointPersistence.flush()
        defaults.removeObject(forKey: Self.completedReviewCheckpointKey)
        let resolvedSide = setup.humanSideId == "random"
            ? (Bool.random() ? "white" : "black")
            : setup.humanSideId
        let created = SharedGameRuntime(
            presetId: setup.presetId,
            deadPositionId: setup.deadPositionId,
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
        checkpointPersistence.beginSession()
        recordedGameRevision = nil
        persistedCheckpointRevision = nil
        preservedCompletedCheckpointRevision = nil
        presentedRuntimeRevision = nil
        automaticReviewGameId = nil
        hintText = nil
        apply(created.view())
        route = .game
        updateGameForegroundState()
    }

    func resumeSavedGame() {
        checkpointPersistence.flush()
        guard let payload = defaults.string(forKey: Self.checkpointKey) else {
            hasResumableGame = false
            return
        }
#if DEBUG
        if let runtime {
            drainReviewAuditEvents(runtime, modelEvent: "game-replaced", forceFlush: true)
        }
#endif
        runtime?.close()
        let created = SharedGameRuntime(
            presetId: "drawless",
            deadPositionId: "material",
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
        checkpointPersistence.beginSession()
        recordedGameRevision = nil
        persistedCheckpointRevision = nil
        preservedCompletedCheckpointRevision = nil
        presentedRuntimeRevision = nil
        automaticReviewGameId = nil
        hintText = nil
        let restored = created.view()
        setup.presetId = restored.presetId.lowercased()
        setup.deadPositionId = restored.deadPositionId
        setup.humanSideId = restored.humanSide.lowercased()
        setup.botLevelId = restored.opponentLevelId
        setup.clockMinutes = Int(restored.initialMillis / 60_000)
        setup.incrementSeconds = Int(restored.incrementMillis / 1_000)
        setup.threatIndicationEnabled = restored.threatIndicationEnabled
        apply(restored)
        route = .game
        updateGameForegroundState()
    }

    func discardSavedGame() {
        checkpointPersistence.clear()
        checkpointPersistence.flush()
        defaults.removeObject(forKey: Self.completedReviewCheckpointKey)
        hasResumableGame = false
    }

    func forfeitSavedGame() {
        checkpointPersistence.flush()
        guard let payload = defaults.string(forKey: Self.checkpointKey) else { return }
        dismissCompletionPresentation()
        resetPostGameReviewFlow()
        cancelBotMovePresentation()
#if DEBUG
        if let runtime {
            drainReviewAuditEvents(runtime, modelEvent: "game-replaced", forceFlush: true)
        }
#endif
        runtime?.close()
        let forfeitedRuntime = SharedGameRuntime(
            presetId: "drawless",
            deadPositionId: "material",
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
        checkpointPersistence.clear()
        checkpointPersistence.flush()
        defaults.removeObject(forKey: Self.completedReviewCheckpointKey)
        hasResumableGame = false
        game = nil
        runtime = nil
    }

    func retryOpponent() {
        guard let runtime else { return }
        let payload = checkpointPersistence.encodeAndWait(runtime.checkpointSnapshot())
        dismissCompletionPresentation()
        resetPostGameReviewFlow()
        runtime.close()
        let recovered = SharedGameRuntime(
            presetId: "drawless",
            deadPositionId: "material",
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
        checkpointPersistence.beginSession()
        persistedCheckpointRevision = nil
        preservedCompletedCheckpointRevision = nil
        presentedRuntimeRevision = nil
        automaticReviewGameId = nil
        apply(recovered.view())
        updateGameForegroundState()
    }

    func startRematch() {
        guard let completedGame = game, completedGame.phase == "COMPLETED" else { return }
        setup = Setup(
            presetId: completedGame.presetId.lowercased(),
            deadPositionId: completedGame.deadPositionId,
            humanSideId: completedGame.humanSide.lowercased(),
            botLevelId: completedGame.opponentLevelId,
            clockMinutes: Int(completedGame.initialMillis / 60_000),
            incrementSeconds: Int(completedGame.incrementMillis / 1_000),
            threatIndicationEnabled: completedGame.threatIndicationEnabled
        )
        startConfiguredGame()
    }

    func setSceneActive(_ active: Bool) {
        sceneAllowsGameWork = active
        updateGameForegroundState()
        if !active {
            persistActiveCheckpointIfChanged()
            checkpointPersistence.flush()
            Self.completedGamePersistenceQueue.sync {}
#if DEBUG
            if let runtime {
                drainReviewAuditEvents(
                    runtime,
                    modelEvent: "scene-background",
                    forceFlush: true
                )
            } else {
                Self.flushReviewAuditPersistence()
            }
#endif
        }
    }

    func setGameVisible(_ visible: Bool) {
        gameVisible = visible
        updateGameForegroundState()
        if !visible { persistActiveCheckpointIfChanged() }
    }

    func startReview() {
        guard ReleaseChannel.gameReviewEnabled, let runtime else { return }
        apply(runtime.startReview())
        updateReviewStatus(runtime.reviewStatus(), runtime: runtime)
    }

    var postGameReviewPending: Bool {
        guard ReleaseChannel.gameReviewEnabled,
              let game,
              game.phase == "COMPLETED" else { return false }
        return postGameReviewHandledGameId != game.gameId
    }

#if DRAWLESS_IOS_GAME_REVIEW
    func enterPostGameReview(expectedGameId: String) {
        guard let runtime,
              let game,
              game.gameId == expectedGameId,
              game.phase == "COMPLETED",
              postGameReviewHandledGameId != expectedGameId,
              postGameReviewTapReady else { return }
        postGameReviewHandledGameId = expectedGameId
        postGameReviewGateTask?.cancel()
        postGameReviewGateTask = nil
        // The result screen intentionally avoids projecting the full review timeline. Pull the
        // current runtime-owned attempt exactly once before navigating so a completed background
        // result never appears to start when the acknowledgement is tapped. ensureReviewStarted is
        // idempotent and cannot discard a completed result even when Swift's last game view is stale.
        let revision = runtime.presentationRevision()
        apply(runtime.ensureReviewStarted(), observedPresentationRevision: revision)
        updateReviewStatus(runtime.reviewStatus(), runtime: runtime)
#if DEBUG
        drainReviewAuditEvents(runtime, modelEvent: "review-opened", forceFlush: true)
#endif
        dismissCompletionPresentation()
        route = .review
    }

    func leaveReview() {
        exitGame()
    }
#endif

    func completionPresentationDidFinish(id: String) {
        guard let presentation = completionPresentation, presentation.id == id else { return }
        completionPresentation = nil
        guard ReleaseChannel.gameReviewEnabled else { return }
        schedulePostGameReviewTapReady(
            gameId: id,
            notBeforeUptime: presentation.reviewNotBeforeUptime
        )
    }

    func dismissCompletionPresentation(id: String? = nil) {
        if let id, completionPresentation?.id != id { return }
        let dismissedGameId = completionPresentation?.id
        completionPresentation = nil
        feedback.cancelCompletion()
        if ReleaseChannel.gameReviewEnabled,
           let game,
           game.phase == "COMPLETED",
           postGameReviewHandledGameId != game.gameId,
           id == nil || dismissedGameId == game.gameId {
            schedulePostGameReviewTapReady(
                gameId: game.gameId,
                notBeforeUptime: ProcessInfo.processInfo.systemUptime
            )
        }
    }

    func tap(_ index: Int32) {
        guard let runtime else { return }
        if botMovePresentation != nil {
            apply(runtime.preselect(displayIndex: index))
            return
        }
        hintText = nil
#if DEBUG
        let previousPly = game?.plyCount ?? 0
        let started = moveLatencyProbe.start()
        let next = runtime.tap(displayIndex: index)
        let runtimeFinished = DispatchTime.now().uptimeNanoseconds
        let recordsCompletedMove = next.plyCount == previousPly + 1 && started != nil
        latencyProbePublicationDeferred = recordsCompletedMove
        apply(next)
        if next.plyCount == previousPly + 1, let started {
            updateLatencyProbeMetrics(moveLatencyProbe.record(
                started: started,
                runtimeFinished: runtimeFinished,
                published: DispatchTime.now().uptimeNanoseconds,
                onMainYield: { [weak self] value in
                    guard let self else { return }
                    self.latencyProbePublicationDeferred = false
                    self.updateLatencyProbeMetrics(value)
                }
            ))
        }
#else
        apply(runtime.tap(displayIndex: index))
#endif
    }

    func choosePromotion(_ choice: String) {
        guard let runtime else { return }
        apply(runtime.choosePromotion(pieceType: choice))
    }

    func cancelPromotion() {
        guard let runtime else { return }
        apply(runtime.cancelPromotion())
    }

    func requestHint() {
        guard let runtime else { return }
        apply(runtime.requestHint())
    }

    func refreshGame() {
#if DRAWLESS_IOS_GAME_REVIEW
        guard route == .game || route == .review, let runtime else { return }
#else
        guard route == .game, let runtime else { return }
#endif
#if DEBUG
        updateLatencyProbeActivity(runtime)
#endif
        // Review preparation continues on its worker after completion. The game/result screen
        // does not consume review frames, so never project that growing timeline onto the main
        // actor until the player actually opens Review. This keeps both the celebration and the
        // post-game controls smooth for long games.
        if route == .game, game?.phase == "COMPLETED" {
            if ReleaseChannel.gameReviewEnabled {
                updateReviewStatus(runtime.reviewStatus(), runtime: runtime)
            }
#if DEBUG
            drainReviewAuditEvents(runtime)
#endif
            return
        }
#if DEBUG
        drainReviewAuditEvents(runtime)
#endif
        persistActiveCheckpointIfChanged()
        let revision = runtime.presentationRevision()
        guard presentedRuntimeRevision != revision else { return }
        apply(runtime.view(), observedPresentationRevision: revision)
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
        let completedReviewWasHandled = game?.phase == "COMPLETED"
        dismissCompletionPresentation()
        cancelBotMovePresentation()
        resetPostGameReviewFlow()
        gameVisible = false
        route = .home
        persistActiveCheckpointIfChanged()
        checkpointPersistence.flush()
        if completedReviewWasHandled {
            defaults.removeObject(forKey: Self.completedReviewCheckpointKey)
        }
#if DEBUG
        if let runtime {
            drainReviewAuditEvents(runtime, modelEvent: "runtime-closing", forceFlush: true)
        } else {
            Self.flushReviewAuditPersistence()
        }
#endif
        runtime?.close()
        runtime = nil
        game = nil
        preservedCompletedCheckpointRevision = nil
        presentedRuntimeRevision = nil
        automaticReviewGameId = nil
        hintText = nil
    }

    func savePreferencesAndGoHome() {
        persistPreferences()
        route = .home
    }

    func persistPreferences() {
        defaults.set(preferences.soundEnabled, forKey: "soundEnabled")
        defaults.set(preferences.soundVolumePercent, forKey: "soundVolumePercent")
        defaults.set(preferences.hapticsEnabled, forKey: "hapticsEnabled")
        defaults.set(preferences.coordinatesEnabled, forKey: "coordinatesEnabled")
        defaults.set(preferences.celebrationsEnabled, forKey: "celebrationsEnabled")
        defaults.set(preferences.threatIndicationEnabled, forKey: "threatIndicationEnabled")
        defaults.set(preferences.boardThemeId, forKey: "boardThemeId")
        setup.threatIndicationEnabled = preferences.threatIndicationEnabled
    }

    private static func normalizedBoardThemeId(_ id: String) -> String {
        switch id {
        case "amethyst_geode": "celestial_observatory"
        case "malachite_court": "verdigris_copper"
        case "all_hallows_court": "halloween_emberwood"
        default: id
        }
    }

    func selectBoardTheme(_ id: String) {
        let normalizedId = Self.normalizedBoardThemeId(id)
        guard Self.boardThemes.contains(where: { $0.id == normalizedId }) else { return }
        preferences.boardThemeId = normalizedId
        persistPreferences()
    }

    func previewSound() {
        feedback.preview(preferences: preferences)
    }

    private func apply(_ next: SharedGameView, observedPresentationRevision: Int64? = nil) {
        let previous = game
        let newlyCompleted = previous?.phase != "COMPLETED" && next.phase == "COMPLETED"
        let observedAtUptime = ProcessInfo.processInfo.systemUptime
        let completionDetectedAtUptime = newlyCompleted ? observedAtUptime : nil
        let newBotMovePresentation = makeBotMovePresentation(
            previous: previous,
            next: next,
            startedAtUptime: observedAtUptime
        )
        if let newBotMovePresentation {
            presentBotMove(newBotMovePresentation)
        } else if previous?.gameId != next.gameId || next.plyCount < (previous?.plyCount ?? 0) {
            cancelBotMovePresentation()
        }
        gameSnapshotDate = Date()
        game = next
        updateGameForegroundState()
#if DEBUG
        latencyProbePlyCount = Int(next.plyCount)
        publishLatencyProbeAccessibilityValue()
#endif
        hintText = next.hintMove
        feedback.process(
            previous: previous,
            next: next,
            preferences: preferences,
            completionDetectedAtUptime: completionDetectedAtUptime,
            minimumCompletionEffectStartUptime: newBotMovePresentation?.endsAtUptime
        )
        if let completionDetectedAtUptime {
            resetPostGameReviewFlow()
            let won = next.winner == next.humanSide
            let isCheckmate = next.endReason == "CHECKMATE"
            let soundEnabled = preferences.soundEnabled && preferences.soundVolumePercent > 0
            let authoredEffectStartUptime = GameCompletionTimeline.effectStartUptime(
                completionDetectedAtUptime: completionDetectedAtUptime,
                won: won,
                isCheckmate: isCheckmate,
                soundEnabled: soundEnabled,
                celebrationsEnabled: preferences.celebrationsEnabled
            )
            let effectStartUptime = max(
                authoredEffectStartUptime,
                newBotMovePresentation?.endsAtUptime ?? authoredEffectStartUptime
            )
            let authoredReviewNotBeforeUptime = GameCompletionTimeline.reviewNotBeforeUptime(
                completionDetectedAtUptime: completionDetectedAtUptime,
                effectStartUptime: effectStartUptime,
                won: won,
                isCheckmate: isCheckmate,
                soundEnabled: soundEnabled,
                celebrationsEnabled: preferences.celebrationsEnabled
            )
            let reviewNotBeforeUptime = max(
                authoredReviewNotBeforeUptime,
                newBotMovePresentation?.endsAtUptime ?? authoredReviewNotBeforeUptime
            )
            if preferences.celebrationsEnabled {
                completionPresentation = CompletionPresentation(
                    id: next.gameId,
                    won: won,
                    humanSide: next.humanSide,
                    opponentLevelId: next.opponentLevelId,
                    effectStartUptime: effectStartUptime,
                    reviewNotBeforeUptime: reviewNotBeforeUptime
                )
            } else {
                completionPresentation = nil
                schedulePostGameReviewTapReady(
                    gameId: next.gameId,
                    notBeforeUptime: reviewNotBeforeUptime
                )
            }
        }
        guard let runtime else { return }
        if ReleaseChannel.gameReviewEnabled {
            updateReviewStatus(runtime.reviewStatus(), runtime: runtime)
        }
#if DEBUG
        drainReviewAuditEvents(
            runtime,
            modelEvent: newlyCompleted ? "terminal-detected" : nil,
            forceFlush: false
        )
#endif
        let revision = runtime.checkpointRevision()
        // Only associate the view with the token observed before it was projected. Reading a
        // newer token here can pair revision R+1 with an R view and suppress that update forever.
        // Direct action projections deliberately leave this nil so the next cheap poll revalidates.
        presentedRuntimeRevision = observedPresentationRevision
        if next.phase == "COMPLETED" {
            if preservedCompletedCheckpointRevision != revision {
                if ReleaseChannel.gameReviewEnabled {
                    checkpointPersistence.preserveCompleted(
                        runtime.checkpointSnapshot(),
                        key: Self.completedReviewCheckpointKey
                    )
                } else {
                    defaults.removeObject(forKey: Self.completedReviewCheckpointKey)
                }
                // Invalidate pending snapshots synchronously, but serialize the actual defaults
                // clear behind any commit already in progress. Persist this terminal revision only
                // once: 50 ms presentation polling must not queue repeated long-game encodes.
                checkpointPersistence.clear()
                preservedCompletedCheckpointRevision = revision
            }
            hasResumableGame = false
            persistedCheckpointRevision = nil
        } else {
            persistActiveCheckpointIfChanged()
        }
        guard next.phase == "COMPLETED" else { return }
        guard recordedGameRevision != revision else { return }
        recordedGameRevision = revision
        recordCompletedGame(next)
        guard ReleaseChannel.gameReviewEnabled else { return }
        if next.reviewAvailable, automaticReviewGameId != next.gameId {
            automaticReviewGameId = next.gameId
            // The terminal view is already published. Start only the review control plane here;
            // opening Review still requests its full projection idempotently.
            runtime.ensureReviewPreparationStarted()
            updateReviewStatus(runtime.reviewStatus(), runtime: runtime)
            presentedRuntimeRevision = nil
#if DEBUG
            drainReviewAuditEvents(
                runtime,
                modelEvent: "terminal-review-started",
                forceFlush: false
            )
            updateLiveReviewDiagnostics(runtime, event: "terminal-review-started")
#endif
        }
    }

    private func makeBotMovePresentation(
        previous: SharedGameView?,
        next: SharedGameView,
        startedAtUptime: TimeInterval
    ) -> BotMovePresentation? {
        guard let previous,
              previous.gameId == next.gameId,
              previous.positionMarker != next.positionMarker,
              next.plyCount == previous.plyCount + 1,
              let motion = next.moveMotion,
              motion.ply == next.plyCount,
              motion.mover.uppercased() != next.humanSide.uppercased() else { return nil }

        let pieces = motion.pieces.map {
            BotMovePresentation.Piece(
                fromSquare: $0.fromSquare,
                toSquare: $0.toSquare,
                pieceCode: $0.pieceCode
            )
        }
        guard !pieces.isEmpty else { return nil }
        return BotMovePresentation(
            id: "\(next.gameId):\(next.plyCount):\(next.positionMarker)",
            gameId: next.gameId,
            ply: next.plyCount,
            pieces: pieces,
            startedAtUptime: startedAtUptime,
            duration: Self.botMoveAnimationDuration
        )
    }

    private func presentBotMove(_ presentation: BotMovePresentation) {
        botMovePresentationTask?.cancel()
        botMoveRenderSampleCount = 0
        botMoveRenderSawMidpoint = false
        botMoveRenderMonotonic = true
        botMoveRenderLastRawProgress = -1
        botMoveTaskStartedMillis = nil
        botMoveFirstFrameMillis = nil
        botMoveRawOneMillis = nil
        botMoveMaxFrameGapMillis = 0
        botMoveLastFrameUptime = nil
        botMovePresentation = presentation
#if DEBUG
        botMoveResultSurfaces.removeAll()
        botMoveResultOverlap = false
        publishBotMoveResultOrderingAccessibilityValue()
        let paths = presentation.pieces
            .map { "\($0.fromSquare)-\($0.toSquare)-\($0.pieceCode)" }
            .joined(separator: ",")
        botMoveAnimationAccessibilityValue =
            "state=active;ply=\(presentation.ply);durationMs=500;pieces=\(paths)"
#endif
        botMovePresentationTask = Task { [weak self] in
            guard let self else { return }
            let taskStartedAtUptime = ProcessInfo.processInfo.systemUptime
#if DEBUG
            self.botMoveTaskStartedMillis = Int(
                ((taskStartedAtUptime - presentation.startedAtUptime) * 1_000).rounded()
            )
#endif
            let remaining = max(0, presentation.endsAtUptime - taskStartedAtUptime)
            if remaining > 0 {
                try? await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
            }
            guard !Task.isCancelled,
                  self.botMovePresentation?.id == presentation.id else { return }
            self.botMovePresentation = nil
            self.botMovePresentationTask = nil
#if DEBUG
            let elapsedMillis = Int(
                ((ProcessInfo.processInfo.systemUptime - presentation.startedAtUptime) * 1_000).rounded()
            )
            self.botMoveAnimationAccessibilityValue =
                "state=completed;ply=\(presentation.ply);durationMs=500;elapsedMs=\(elapsedMillis);" +
                "pieces=\(paths);" +
                "renderSamples=\(self.botMoveRenderSampleCount);" +
                "midpointSeen=\(self.botMoveRenderSawMidpoint ? 1 : 0);" +
                "monotonic=\(self.botMoveRenderMonotonic ? 1 : 0);" +
                "taskStartedMs=\(self.botMoveTaskStartedMillis.map(String.init) ?? "missing");" +
                "firstFrameMs=\(self.botMoveFirstFrameMillis.map(String.init) ?? "missing");" +
                "rawOneMs=\(self.botMoveRawOneMillis.map(String.init) ?? "missing");" +
                "maxFrameGapMs=\(self.botMoveMaxFrameGapMillis)"
#endif
        }
    }

    func botMoveAnimationDidRender(id: String, rawProgress: Double) {
        guard let presentation = botMovePresentation, presentation.id == id else { return }
        let sampledAtUptime = ProcessInfo.processInfo.systemUptime
        let sampledMillis = Int(
            ((sampledAtUptime - presentation.startedAtUptime) * 1_000).rounded()
        )
        if botMoveFirstFrameMillis == nil {
            botMoveFirstFrameMillis = sampledMillis
        }
        if let previous = botMoveLastFrameUptime {
            let gapMillis = Int(((sampledAtUptime - previous) * 1_000).rounded())
            botMoveMaxFrameGapMillis = max(botMoveMaxFrameGapMillis, gapMillis)
        }
        botMoveLastFrameUptime = sampledAtUptime
        let clamped = min(1, max(0, rawProgress))
        if botMoveRenderLastRawProgress >= 0,
           clamped + 0.000_001 < botMoveRenderLastRawProgress {
            botMoveRenderMonotonic = false
        }
        if abs(clamped - botMoveRenderLastRawProgress) >= 0.000_1 {
            botMoveRenderSampleCount += 1
            botMoveRenderLastRawProgress = clamped
        }
        if clamped > 0.15, clamped < 0.85 {
            botMoveRenderSawMidpoint = true
        }
        if clamped >= 1, botMoveRawOneMillis == nil {
            botMoveRawOneMillis = sampledMillis
        }
    }

#if DEBUG
    func botMoveResultSurfaceDidAppear(_ surface: String) {
        guard surface == "postGame" || surface == "completionEffect" else { return }
        botMoveResultSurfaces.insert(surface)
        if botMovePresentation != nil {
            botMoveResultOverlap = true
        }
        publishBotMoveResultOrderingAccessibilityValue()
    }

    private func publishBotMoveResultOrderingAccessibilityValue() {
        let surfaces = botMoveResultSurfaces.isEmpty
            ? "none"
            : botMoveResultSurfaces.sorted().joined(separator: ",")
        botMoveResultOrderingAccessibilityValue =
            "resultSurfaces=\(surfaces);resultOverlap=\(botMoveResultOverlap ? 1 : 0)"
    }
#endif

    private func cancelBotMovePresentation() {
        botMovePresentationTask?.cancel()
        botMovePresentationTask = nil
        botMovePresentation = nil
        botMoveRenderSampleCount = 0
        botMoveRenderSawMidpoint = false
        botMoveRenderMonotonic = true
        botMoveRenderLastRawProgress = -1
        botMoveTaskStartedMillis = nil
        botMoveFirstFrameMillis = nil
        botMoveRawOneMillis = nil
        botMoveMaxFrameGapMillis = 0
        botMoveLastFrameUptime = nil
#if DEBUG
        botMoveAnimationAccessibilityValue = "state=idle"
#endif
    }

    private func updateReviewStatus(_ status: SharedReviewStatus, runtime: SharedGameRuntime) {
        if reviewReady != status.ready { reviewReady = status.ready }
        if reviewInProgress != status.inProgress { reviewInProgress = status.inProgress }
        let preparationPhase = Self.normalizedReviewPreparationPhase(
            runtime.reviewPreparationState()
        )
        if reviewPreparationPhase != preparationPhase {
            reviewPreparationPhase = preparationPhase
        }
    }

    private static func normalizedReviewPreparationPhase(_ value: String) -> String {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if normalized.contains("PREPAR") || normalized.contains("REPLAY") || normalized.contains("FINALIZ") {
            return "PREPARING"
        }
        if normalized.contains("ANALYZ") || normalized.contains("SEARCH") {
            return "ANALYZING"
        }
        if normalized.contains("READY") || normalized.contains("COMPLETE") {
            return "READY"
        }
        if normalized.contains("FAIL") || normalized.contains("ERROR") {
            return "FAILED"
        }
        return normalized.isEmpty ? "IDLE" : normalized
    }

    private func resetPostGameReviewFlow() {
        postGameReviewGateTask?.cancel()
        postGameReviewGateTask = nil
        postGameReviewHandledGameId = nil
        postGameReviewTapReady = false
    }

    private func schedulePostGameReviewTapReady(
        gameId: String,
        notBeforeUptime: TimeInterval
    ) {
        postGameReviewGateTask?.cancel()
        postGameReviewGateTask = nil
        postGameReviewTapReady = false
        guard ReleaseChannel.gameReviewEnabled else { return }

        let remaining = max(0, notBeforeUptime - ProcessInfo.processInfo.systemUptime)
        if remaining == 0 {
            guard game?.gameId == gameId, postGameReviewPending else { return }
            postGameReviewTapReady = true
            return
        }

        postGameReviewGateTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
            guard !Task.isCancelled,
                  let self,
                  self.game?.gameId == gameId,
                  self.postGameReviewPending else { return }
            self.postGameReviewTapReady = true
            self.postGameReviewGateTask = nil
        }
    }

    private func updateGameForegroundState() {
        guard let runtime else {
            runtimeGameForeground = nil
            return
        }
        // The model route is the synchronous source of truth. Waiting for GameView.onAppear made
        // foreground review depend on a later SwiftUI lifecycle callback that the physical probe
        // did not exercise and that can be delayed or skipped during view transitions.
        let foreground = ReleaseChannel.gameReviewEnabled &&
            sceneAllowsGameWork && route == .game && game?.phase != "COMPLETED"
        // Coordinator callbacks already advance prefetch after bot, hint, undo, and resume work.
        // Avoid re-entering its scheduler on every 50 ms presentation refresh when the lifecycle
        // state has not changed; on long games that could otherwise reconstruct a catch-up plan on
        // the main actor if the review launch gate happened to be free at that instant.
        guard runtimeGameForeground != foreground else { return }
        runtimeGameForeground = foreground
        runtime.setGameForeground(foreground: foreground)
#if DEBUG
        drainReviewAuditEvents(
            runtime,
            modelEvent: foreground ? "game-foreground" : "game-not-foreground"
        )
        updateLiveReviewDiagnostics(runtime, event: "foreground")
#endif
    }

    private func persistActiveCheckpointIfChanged() {
        guard let runtime, game?.phase != "COMPLETED" else { return }
        let observedRevision = runtime.checkpointRevision()
        guard persistedCheckpointRevision != observedRevision else { return }
        let snapshot = runtime.checkpointSnapshot()
        let revision = snapshot.revision
        guard persistedCheckpointRevision != revision else { return }
        checkpointPersistence.enqueue(snapshot)
        if !hasResumableGame { hasResumableGame = true }
        persistedCheckpointRevision = revision
    }

#if DEBUG
    /// Drains the runtime's structured review ledger into a per-game append-only file.
    /// This is deliberately enabled for every Debug launch, including a normal SpringBoard
    /// launch, so a manual physical-device game produces evidence without XCTest seams.
    private func drainReviewAuditEvents(
        _ runtime: SharedGameRuntime,
        modelEvent: String? = nil,
        forceFlush: Bool = false
    ) {
        let gameId = game?.gameId ?? "unknown-session"
        let runtimeEvents = runtime.drainReviewAuditEvents()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        var records: [String] = []
        if !runtimeEvents.isEmpty { records.append(runtimeEvents) }
        if let modelEvent,
           let modelRecord = reviewAuditModelRecord(event: modelEvent, gameId: gameId) {
            records.append(modelRecord)
        }
        if !records.isEmpty {
            Self.enqueueReviewAuditPersistence(
                records.joined(separator: "\n"),
                gameId: gameId
            )
        }
        if forceFlush { Self.flushReviewAuditPersistence() }
    }

    private func reviewAuditModelRecord(event: String, gameId: String) -> String? {
        let routeName: String
        switch route {
        case .home: routeName = "home"
        case .setup: routeName = "setup"
        case .game: routeName = "game"
#if DRAWLESS_IOS_GAME_REVIEW
        case .review: routeName = "review"
#endif
        case .options: routeName = "options"
        case .statistics: routeName = "statistics"
        }
        let payload: [String: Any] = [
            "schemaVersion": 1,
            "source": "ios-model",
            "event": event,
            "timestampEpochMillis": Int64(Date().timeIntervalSince1970 * 1_000),
            "build": Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown",
            "gameId": gameId,
            "route": routeName,
            "sceneAllowsGameWork": sceneAllowsGameWork,
            "gameVisible": gameVisible,
            "phase": game?.phase ?? "NONE",
            "ply": Int(game?.plyCount ?? 0),
            "reviewPreparationPhase": reviewPreparationPhase,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private static func enqueueReviewAuditPersistence(_ records: String, gameId: String) {
        reviewAuditPersistenceQueue.async {
            guard let documents = FileManager.default.urls(
                for: .documentDirectory,
                in: .userDomainMask
            ).first else { return }
            let safeGameId = gameId.map { character -> Character in
                character.isLetter || character.isNumber || character == "-" || character == "_"
                    ? character
                    : "_"
            }
            let destination = documents.appendingPathComponent(
                "review-audit-\(String(safeGameId)).jsonl"
            )
            guard let data = (records.trimmingCharacters(in: .newlines) + "\n")
                .data(using: .utf8) else { return }
            if !FileManager.default.fileExists(atPath: destination.path) {
                FileManager.default.createFile(atPath: destination.path, contents: nil)
            }
            guard let handle = try? FileHandle(forWritingTo: destination) else { return }
            handle.seekToEndOfFile()
            handle.write(data)
            handle.closeFile()
        }
    }

    private static func flushReviewAuditPersistence() {
        reviewAuditPersistenceQueue.sync {}
    }

    private func updateLatencyProbeActivity(_ runtime: SharedGameRuntime) {
        // Debug builds are what we install on test devices. Keep production-like manual play free
        // of XCTest's lock-heavy diagnostics unless a latency test explicitly enables the probe.
        guard moveLatencyProbe.isEnabled else { return }
        let reviewReuse = runtime.reviewReuseDiagnosticsForTesting()
        var changed = false
        if reviewReuse != latencyProbeReviewReuse {
            latencyProbeReviewReuse = reviewReuse
            changed = true
        }
        let engineActivity = runtime.engineActivityForTesting()
        let reviewPreparationActivity = runtime.reviewPreparationActivityForTesting()
        let checkpointActivity = checkpointPersistence.testingActivity()
        if engineActivity != latencyProbeEngineActivity {
            latencyProbeEngineActivity = engineActivity
            changed = true
        }
        if reviewPreparationActivity != latencyProbeReviewPreparationActivity {
            latencyProbeReviewPreparationActivity = reviewPreparationActivity
            changed = true
        }
        if checkpointActivity != latencyProbeCheckpointActivity {
            latencyProbeCheckpointActivity = checkpointActivity
            changed = true
        }
        if changed { publishLatencyProbeAccessibilityValue() }
        updateLiveReviewDiagnostics(runtime, event: "refresh", reviewReuse: reviewReuse)
    }

    private func updateLiveReviewDiagnostics(
        _ runtime: SharedGameRuntime,
        event: String,
        reviewReuse suppliedReviewReuse: String? = nil
    ) {
        guard moveLatencyProbe.isEnabled else { return }
        let reviewReuse = suppliedReviewReuse ?? runtime.reviewReuseDiagnosticsForTesting()
        if reviewReuse != latencyProbeReviewReuse {
            latencyProbeReviewReuse = reviewReuse
            publishLatencyProbeAccessibilityValue()
        }
        let routeName: String
        switch route {
        case .home: routeName = "home"
        case .setup: routeName = "setup"
        case .game: routeName = "game"
#if DRAWLESS_IOS_GAME_REVIEW
        case .review: routeName = "review"
#endif
        case .options: routeName = "options"
        case .statistics: routeName = "statistics"
        }
        let state = [
            "build=\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown")",
            "route=\(routeName)",
            "sceneAllowsGameWork=\(sceneAllowsGameWork)",
            "gameVisible=\(gameVisible)",
            "phase=\(game?.phase ?? "NONE")",
            "plyCount=\(game?.plyCount ?? 0)",
            "winner=\(game?.winner ?? "")",
            "endReason=\(game?.endReason ?? "")",
            reviewReuse,
        ].joined(separator: ";")
        guard state != lastLiveReviewDiagnosticsState else { return }
        lastLiveReviewDiagnosticsState = state
        guard let documents = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first else { return }
        let destination = documents.appendingPathComponent("last-live-review-diagnostics.txt")
        let report = "event=\(event);" + state + "\n"
        Self.liveReviewDiagnosticsQueue.async {
            try? report.write(to: destination, atomically: true, encoding: .utf8)
        }
    }

    private func updateLatencyProbeMetrics(_ value: String) {
        latencyProbeMetricsValue = value
        publishLatencyProbeAccessibilityValue()
    }

    private func publishLatencyProbeAccessibilityValue() {
        // The probe's pending AX publication would itself create work ahead of the main-queue
        // callback it is measuring. Publish one complete sample from that callback instead.
        guard !latencyProbePublicationDeferred else { return }
        latencyProbeAccessibilityValue = [
            "plyCount=\(latencyProbePlyCount)",
            "phase=\(game?.phase ?? "NONE")",
            "winner=\(game?.winner ?? "")",
            "endReason=\(game?.endReason ?? "")",
            latencyProbeEngineActivity.isEmpty ? nil : "engine=\(latencyProbeEngineActivity)",
            latencyProbeReviewPreparationActivity.isEmpty
                ? nil
                : "reviewPreparation=\(latencyProbeReviewPreparationActivity)",
            latencyProbeCheckpointActivity.isEmpty ? nil : "checkpoint=\(latencyProbeCheckpointActivity)",
            latencyProbeReviewReuse.isEmpty ? nil : latencyProbeReviewReuse,
            latencyProbeMetricsValue.isEmpty ? nil : latencyProbeMetricsValue,
        ].compactMap { $0 }.joined(separator: ";")
    }
#endif

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
        let record = CompletedGameRecord(
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
        )
        completedGames.append(record)
        statistics = Self.statisticsByAppending(record, to: statistics)

        let recordsSnapshot = completedGames
        let historyKey = completedGamesKey
        let persistedGames = statistics.games
        let persistedWins = statistics.wins
        let persistedLosses = statistics.losses
        let persistedScore = statistics.totalScore
        Self.completedGamePersistenceQueue.async {
            let defaults = UserDefaults.standard
            if let payload = try? JSONEncoder().encode(recordsSnapshot) {
                defaults.set(payload, forKey: historyKey)
            }
            defaults.set(persistedGames, forKey: "stats.games")
            defaults.set(persistedWins, forKey: "stats.wins")
            defaults.set(persistedLosses, forKey: "stats.losses")
            defaults.set(persistedScore, forKey: "stats.totalScore")
        }
    }

    private static func statisticsByAppending(
        _ record: CompletedGameRecord,
        to current: Statistics
    ) -> Statistics {
        var next = current
        next.games += 1
        next.totalScore += record.score
        if record.playerWon {
            next.wins += 1
            next.currentWinStreak += 1
            next.bestWinStreak = max(next.bestWinStreak, next.currentWinStreak)
            if record.unassisted { next.unassistedWins += 1 }
        } else {
            next.losses += 1
            next.currentWinStreak = 0
        }
        next.averageScore = Double(next.totalScore) / Double(next.games)
        next.winPercentage = Double(next.wins) * 100 / Double(next.games)

        if let index = next.opponents.firstIndex(where: { $0.id == record.opponentId }) {
            let old = next.opponents[index]
            let games = old.games + 1
            let wins = old.wins + (record.playerWon ? 1 : 0)
            next.opponents[index] = OpponentStatistics(
                id: record.opponentId,
                name: record.opponentName,
                elo: record.opponentElo,
                games: games,
                wins: wins,
                losses: games - wins,
                averageScore: (old.averageScore * Double(old.games) + Double(record.score))
                    / Double(games),
                winPercentage: Double(wins) * 100 / Double(games)
            )
        } else {
            next.opponents.append(OpponentStatistics(
                id: record.opponentId,
                name: record.opponentName,
                elo: record.opponentElo,
                games: 1,
                wins: record.playerWon ? 1 : 0,
                losses: record.playerWon ? 0 : 1,
                averageScore: Double(record.score),
                winPercentage: record.playerWon ? 100 : 0
            ))
        }
        next.opponents.sort { $0.elo < $1.elo }

        if record.opponentId == "adaptive", record.unassisted {
            next.adaptiveGamesPlayed += 1
            if let ratingAfter = record.playerRatingAfter {
                next.adaptiveRating = ratingAfter
            }
        }
        return next
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
        let current = OfflineRating(
            rating: Int32(rating),
            gamesPlayed: Int32(gamesPlayed)
        )
        let result = playerWon ? RatedResult.win : RatedResult.loss
        return Int(
            OfflineElo.shared.update(
                current: current,
                opponentElo: Int32(opponentElo),
                result: result
            ).rating
        )
    }

    private struct LegacyStatistics: Codable {
        let games: Int
        let wins: Int
        let losses: Int
        let totalScore: Int
    }

    private struct CompletedGameRecord: Codable, Sendable {
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

/// Moves growing checkpoint JSON construction and storage off the main actor.
///
/// The desired identity is replaced synchronously whenever a newer runtime revision arrives.
/// Work queued for an older identity checks that token both before and after encoding, so bursts
/// collapse to the latest exact snapshot without allowing an obsolete payload to become final.
private final class ActiveCheckpointPersistence: @unchecked Sendable {
    private struct Identity: Equatable, Sendable {
        let generation: UInt64
        let gameId: String
        let revision: Int64
    }

    private enum Desired: Equatable {
        case idle(UInt64)
        case active(Identity)
        case cleared(UInt64)
    }

    /// Kotlin/Native objects can be shared by the modern memory manager, but Swift cannot infer
    /// that guarantee from the generated framework declaration.
    private final class SnapshotBox: @unchecked Sendable {
        let value: SharedCheckpointSnapshot

        init(_ value: SharedCheckpointSnapshot) {
            self.value = value
        }
    }

    private let defaults: UserDefaults
    private let key: String
    private let clearTombstoneKey: String
    private let queue = DispatchQueue(
        label: "com.drawlesschess.active-checkpoint-persistence",
        qos: .utility
    )
    private let stateLock = NSLock()
    private let storageLock = NSLock()
    private var generation: UInt64 = 0
    private var desired: Desired = .idle(0)
    private var activity = "IDLE"
    private let testingDelayMillis: Int
    private let activityEnabled: Bool

    init(defaults: UserDefaults, key: String, clearTombstoneKey: String) {
        self.defaults = defaults
        self.key = key
        self.clearTombstoneKey = clearTombstoneKey
#if DEBUG
        let environment = ProcessInfo.processInfo.environment
        testingDelayMillis = min(
            10_000,
            max(0, Int(environment["DRAWLESS_XCTEST_CHECKPOINT_ENCODE_HOLD_MILLIS"] ?? "") ?? 0)
        )
        activityEnabled = environment["DRAWLESS_XCTEST_LATENCY"] == "1" ||
            testingDelayMillis > 0
#else
        testingDelayMillis = 0
        activityEnabled = false
#endif
    }

    /// Invalidates work from a closed runtime before the first snapshot of its replacement is
    /// submitted. Queue ordering still guarantees any write already in progress is overwritten by
    /// the replacement session's first persisted revision.
    func beginSession() {
        withStateLock {
            generation &+= 1
            desired = .idle(generation)
            activity = "IDLE"
        }
    }

    func enqueue(_ snapshot: SharedCheckpointSnapshot) {
        let boxedSnapshot = SnapshotBox(snapshot)
        let identity: Identity = withStateLock {
            let identity = Identity(
                generation: generation,
                gameId: snapshot.gameId,
                revision: snapshot.revision
            )
            desired = .active(identity)
            activity = "QUEUED"
            return identity
        }

        queue.async { [self, boxedSnapshot, identity] in
            guard isCurrent(identity) else { return }
            guard holdForTestingIfNeeded(identity) else { return }
            guard setActivity("ENCODING", ifCurrent: identity) else { return }
            let payload = boxedSnapshot.value.encodeJson()
            guard setActivity("WRITING", ifCurrent: identity) else { return }
            storageLock.lock()
            if isCurrent(identity) {
                defaults.set(payload, forKey: key)
                defaults.removeObject(forKey: clearTombstoneKey)
            }
            storageLock.unlock()
            _ = setActivity("IDLE", ifCurrent: identity)
        }
    }

    /// Stores the terminal checkpoint independently of the active-game tombstone. The task is
    /// ordered on the same queue as active writes and clears, so a delayed active encoder cannot
    /// overwrite or erase the completed review handoff.
    func preserveCompleted(_ snapshot: SharedCheckpointSnapshot, key completedKey: String) {
        let boxedSnapshot = SnapshotBox(snapshot)
        queue.async { [self, boxedSnapshot] in
            let payload = boxedSnapshot.value.encodeJson()
            storageLock.lock()
            defaults.set(payload, forKey: completedKey)
            storageLock.unlock()
        }
    }

    /// A clear advances the generation, invalidating every queued or encoding snapshot. The clear
    /// itself is serialized behind any write already past its final token check, so stale data
    /// cannot be left in storage after `flush()` returns.
    func clear() {
        let clearGeneration: UInt64 = withStateLock {
            generation &+= 1
            desired = .cleared(generation)
            activity = "CLEAR_QUEUED"
            return generation
        }
        defaults.set(true, forKey: clearTombstoneKey)

        queue.async { [self, clearGeneration] in
            _ = setActivity("CLEARING", ifCurrentClear: clearGeneration)
            removeStoredValue()
            _ = setActivity("IDLE", ifCurrentClear: clearGeneration)
        }
    }

    /// Terminal completion must not leave an obsolete resumable game behind if the process is
    /// suspended immediately. Invalidating first makes an encoder in flight fail its final token
    /// check; the storage lock orders this removal after a write already in its tiny commit window.
    func clearAndWaitForStorage() {
        let clearGeneration: UInt64 = withStateLock {
            generation &+= 1
            desired = .cleared(generation)
            activity = "CLEARING"
            return generation
        }
        defaults.set(true, forKey: clearTombstoneKey)
        removeStoredValue()
        withStateLock {
            if desired == .cleared(clearGeneration) { activity = "IDLE" }
        }
    }

    /// Lifecycle and explicit save/restore boundaries may wait for the worker. Normal move
    /// publication never calls this method.
    func flush() {
        queue.sync {}
    }

    /// Recovery needs an immediate payload, but serialization still happens on the persistence
    /// queue rather than on the main actor.
    func encodeAndWait(_ snapshot: SharedCheckpointSnapshot) -> String {
        let boxedSnapshot = SnapshotBox(snapshot)
        return queue.sync {
            boxedSnapshot.value.encodeJson()
        }
    }

    func testingActivity() -> String {
        guard activityEnabled else { return "DISABLED" }
        return withStateLock { activity }
    }

    private func isCurrent(_ identity: Identity) -> Bool {
        withStateLock { desired == .active(identity) }
    }

    @discardableResult
    private func setActivity(_ value: String, ifCurrent identity: Identity) -> Bool {
        withStateLock {
            guard desired == .active(identity) else { return false }
            activity = value
            return true
        }
    }

    @discardableResult
    private func setActivity(_ value: String, ifCurrentClear clearGeneration: UInt64) -> Bool {
        withStateLock {
            guard desired == .cleared(clearGeneration) else { return false }
            activity = value
            return true
        }
    }

    private func holdForTestingIfNeeded(_ identity: Identity) -> Bool {
        guard testingDelayMillis > 0 else { return isCurrent(identity) }
        guard setActivity("HOLDING", ifCurrent: identity) else { return false }
        var remaining = testingDelayMillis
        while remaining > 0 {
            guard isCurrent(identity) else { return false }
            let slice = min(remaining, 10)
            Thread.sleep(forTimeInterval: Double(slice) / 1_000.0)
            remaining -= slice
        }
        return isCurrent(identity)
    }

    private func removeStoredValue() {
        storageLock.lock()
        defaults.removeObject(forKey: key)
        defaults.removeObject(forKey: clearTombstoneKey)
        storageLock.unlock()
    }

    private func withStateLock<T>(_ operation: () -> T) -> T {
        stateLock.lock()
        defer { stateLock.unlock() }
        return operation()
    }
}

#if DEBUG
@MainActor
private final class MoveLatencyProbe {
    private struct Sample {
        let runtimeMicroseconds: UInt64
        let publishMicroseconds: UInt64
    }

    private let enabled = ProcessInfo.processInfo.environment["DRAWLESS_XCTEST_LATENCY"] == "1"
    private let log = OSLog(
        subsystem: Bundle.main.bundleIdentifier ?? "com.drawlesschess",
        category: .pointsOfInterest
    )
    private var samples: [Sample] = []

    var isEnabled: Bool { enabled }

    func start() -> UInt64? {
        enabled ? DispatchTime.now().uptimeNanoseconds : nil
    }

    func record(
        started: UInt64,
        runtimeFinished: UInt64,
        published: UInt64,
        onMainYield: @escaping @MainActor (String) -> Void
    ) -> String {
        let sample = Sample(
            runtimeMicroseconds: (runtimeFinished - started) / 1_000,
            publishMicroseconds: (published - started) / 1_000
        )
        samples.append(sample)
        let sequence = samples.count
        let immediate = value(sequence: sequence, sample: sample, mainYieldMicroseconds: nil)

        os_signpost(
            .event,
            log: log,
            name: "HumanMoveLatency",
            "sequence=%{public}d runtime_us=%{public}llu publish_us=%{public}llu p95_us=%{public}llu max_us=%{public}llu",
            sequence,
            sample.runtimeMicroseconds,
            sample.publishMicroseconds,
            percentile95,
            maximum
        )

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let mainYieldMicroseconds = (DispatchTime.now().uptimeNanoseconds - started) / 1_000
            onMainYield(self.value(
                sequence: sequence,
                sample: sample,
                mainYieldMicroseconds: mainYieldMicroseconds
            ))
        }
        return immediate
    }

    private var percentile95: UInt64 {
        let ordered = samples.map(\.publishMicroseconds).sorted()
        let index = max(0, Int(ceil(Double(ordered.count) * 0.95)) - 1)
        return ordered[index]
    }

    private var maximum: UInt64 {
        samples.map(\.publishMicroseconds).max() ?? 0
    }

    private func value(
        sequence: Int,
        sample: Sample,
        mainYieldMicroseconds: UInt64?
    ) -> String {
        [
            "moveSeq=\(sequence)",
            "runtimeUs=\(sample.runtimeMicroseconds)",
            "publishUs=\(sample.publishMicroseconds)",
            "mainYieldUs=\(mainYieldMicroseconds.map { String($0) } ?? "pending")",
            "p95Us=\(percentile95)",
            "maxUs=\(maximum)",
        ].joined(separator: ";")
    }
}
#endif
