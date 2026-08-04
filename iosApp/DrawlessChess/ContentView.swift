import DrawlessShared
import Foundation
import SwiftUI

struct ContentView: View {
    @StateObject private var model = DrawlessChessModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            AppBackground()
            switch model.route {
            case .home:
                HomeView(model: model)
            case .setup:
                SetupView(model: model)
            case .game:
                GameView(model: model)
            case .review:
                GameReviewView(model: model)
            case .options:
                OptionsView(model: model)
            case .statistics:
                StatisticsView(model: model)
            }
        }
        .preferredColorScheme(.dark)
        .onChange(of: scenePhase) { phase in
            model.setGameForeground(phase == .active)
        }
    }
}

private struct HomeView: View {
    @ObservedObject var model: DrawlessChessModel
    @State private var infoSheet: InfoSheet?
    @State private var pendingNewGame: PendingNewGameAction?
    private let smoke = SharedCoreSmoke()

    var body: some View {
        ScrollView {
            VStack(spacing: 26) {
                Spacer(minLength: 32)
                VStack(spacing: 9) {
                    Text(verbatim: "DRAWLESS CHESS")
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                        .tracking(3.4)
                        .foregroundStyle(AppPalette.gold)
                    Text("Every game has a winner.")
                        .font(.system(.largeTitle, design: .serif).weight(.semibold))
                        .multilineTextAlignment(.center)
                    Text("Offline decisive chess")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("home.header")

                StartingPositionBoard(themeId: model.preferences.boardThemeId)
                    .frame(maxWidth: 430)
                    .accessibilityHidden(true)

                if let opponent = model.opponentLevels.first(where: { $0.id == model.setup.botLevelId }) {
                    HStack(spacing: 14) {
                        OpponentPortrait(level: opponent, size: 58)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(localizedFormat("ios.quick_play_with", opponent.name)).font(.headline)
                            HStack(spacing: 3) {
                                Text(LocalizedStringKey(opponent.epithet))
                                Text(localizedFormat("ios.about_elo", opponent.elo))
                            }
                            .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .padding(14)
                    .frame(maxWidth: 430)
                    .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("home.quickOpponentSummary")

                    Picker(localized("ios.quick_play_opponent"), selection: $model.setup.botLevelId) {
                        ForEach(model.opponentLevels, id: \.id) { level in
                            Text(verbatim: "\(level.name) · \(level.elo)").tag(level.id)
                        }
                    }
                    .pickerStyle(.menu)
                    .frame(maxWidth: 430, alignment: .trailing)
                    .accessibilityIdentifier("home.quickOpponent")
                }

                VStack(spacing: 12) {
                    if model.hasResumableGame {
                        Button {
                            model.resumeSavedGame()
                        } label: {
                            Label("Resume Game", systemImage: "play.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("home.resume")

                        Button("Discard Saved Game", role: .destructive) {
                            model.discardSavedGame()
                        }
                        .font(.footnote.weight(.semibold))
                        .accessibilityIdentifier("home.discard")
                    }

                    Button {
                        if model.hasResumableGame { pendingNewGame = .quickPlay }
                        else { model.startQuickPlay() }
                    } label: {
                        Label("Quick Play", systemImage: "bolt.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("home.quickPlay")

                    Button("New Game") {
                        if model.hasResumableGame { pendingNewGame = .customGame }
                        else { model.route = .setup }
                    }
                        .buttonStyle(SecondaryButtonStyle())
                        .accessibilityIdentifier("home.newGame")

                    HStack(spacing: 12) {
                        Button { model.route = .options } label: {
                            Label("Options", systemImage: "slider.horizontal.3")
                        }
                        .accessibilityIdentifier("home.options")
                        Button { model.route = .statistics } label: {
                            Label("Statistics", systemImage: "chart.bar.fill")
                        }
                        .accessibilityIdentifier("home.statistics")
                    }
                    .buttonStyle(CompactButtonStyle())

                    HStack(spacing: 8) {
                        Button("Rules") { infoSheet = .rules }
                        Button("License") { infoSheet = .license }
                        Button("Privacy") { infoSheet = .privacy }
                    }
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppPalette.gold)
                }
                .frame(maxWidth: 430)

                Label(smoke.verificationSummary(), systemImage: "checkmark.seal.fill")
                    .font(.footnote.monospaced())
                    .foregroundStyle(AppPalette.mint)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(AppPalette.panel, in: Capsule())
                    .accessibilityIdentifier("sharedCore.status")
                Spacer(minLength: 28)
            }
            .padding(.horizontal, 22)
            .frame(maxWidth: .infinity)
        }
        .sheet(item: $infoSheet) { sheet in
            InformationSheet(sheet: sheet)
        }
        .alert(item: $pendingNewGame) { action in
            Alert(
                title: Text("Forfeit current game?"),
                message: Text("Are you sure you want to forfeit your current game? It will count as a loss in your stats."),
                primaryButton: .destructive(Text("Forfeit & start new game")) {
                    model.forfeitSavedGame()
                    if action == .quickPlay { model.startQuickPlay() }
                    else { model.route = .setup }
                },
                secondaryButton: .cancel(Text("Keep current game"))
            )
        }
    }
}

private struct SetupView: View {
    @ObservedObject var model: DrawlessChessModel

    private var selectedOpponent: DrawlessChessModel.BotLevel {
        model.opponentLevels.first(where: { $0.id == model.setup.botLevelId })
            ?? model.opponentLevels[2]
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                ScreenHeader(title: "New Game", back: { model.route = .home })

                SetupSection(title: "Rules") {
                    Picker("Rules", selection: $model.setup.presetId) {
                        Text("Drawless").tag("drawless")
                        Text("Escape").tag("escape")
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("setup.rules")
                    Text(LocalizedStringKey(model.setup.presetId == "drawless"
                         ? "Default: a player with no legal move loses."
                         : "Escape variant: a stalemated player wins instead."))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                SetupSection(title: "Play as") {
                    Picker("Play as", selection: $model.setup.humanSideId) {
                        Text("White").tag("white")
                        Text("Black").tag("black")
                        Text("Random").tag("random")
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("setup.side")
                }

                SetupSection(title: "Opponent") {
                    Picker("Opponent", selection: $model.setup.botLevelId) {
                        ForEach(model.opponentLevels, id: \.id) { level in
                            Text(verbatim: "\(level.name) · \(level.elo)").tag(level.id)
                        }
                    }
                    .pickerStyle(.menu)
                    .accessibilityIdentifier("setup.opponent")
                    HStack(alignment: .top, spacing: 14) {
                        OpponentPortrait(level: selectedOpponent, size: 72)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(LocalizedStringKey(selectedOpponent.epithet)).font(.headline)
                            Text(LocalizedStringKey(selectedOpponent.personality))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Text(localizedFormat("ios.approximate_elo", selectedOpponent.elo))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AppPalette.gold)
                            if selectedOpponent.id == "adaptive" {
                                Text(model.statistics.adaptiveGamesPlayed < 10
                                     ? localizedFormat(
                                        "ios.adaptive_status_provisional",
                                        model.statistics.adaptiveRating,
                                        model.statistics.adaptiveGamesPlayed
                                     )
                                     : localizedFormat(
                                        "ios.adaptive_status_matched",
                                        model.statistics.adaptiveRating
                                     ))
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(AppPalette.gold)
                            }
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("setup.opponentSummary")
                }

                SetupSection(title: "Clock") {
                    Picker("Clock", selection: $model.setup.clockMinutes) {
                        Text("Untimed").tag(0)
                        Text(localized("ios.duration_3")).tag(3)
                        Text(localized("ios.duration_10")).tag(10)
                        Text(localized("ios.duration_30")).tag(30)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("setup.clock")
                    if model.setup.clockMinutes > 0 {
                        Stepper(localizedFormat("ios.increment", model.setup.incrementSeconds),
                                value: $model.setup.incrementSeconds, in: 0...10)
                    }
                }

                Toggle("Show threatened pieces", isOn: $model.setup.threatIndicationEnabled)
                    .tint(AppPalette.gold)
                    .accessibilityIdentifier("setup.threats")

                Button("Start Game") { model.startConfiguredGame() }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("setup.start")
            }
            .padding(22)
            .frame(maxWidth: 680)
            .frame(maxWidth: .infinity)
        }
    }
}

private struct GameView: View {
    @ObservedObject var model: DrawlessChessModel
    @State private var showResignConfirmation = false

    var body: some View {
        GeometryReader { proxy in
            let landscape = proxy.size.width > 700 && proxy.size.width > proxy.size.height
            Group {
                if landscape {
                    HStack(alignment: .top, spacing: 24) {
                        boardColumn
                            .frame(maxWidth: min(proxy.size.height - 36, 680))
                        sidePanel
                            .frame(width: min(360, proxy.size.width * 0.34))
                    }
                    .padding(18)
                } else {
                    ScrollView {
                        VStack(spacing: 18) {
                            boardColumn
                                .frame(maxWidth: min(proxy.size.width - 28, 680))
                            sidePanel
                                .frame(maxWidth: 680)
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .confirmationDialog("Choose promotion", isPresented: Binding(
            get: { !(model.game?.promotionChoices.isEmpty ?? true) },
            set: { _ in }
        )) {
            ForEach(model.game?.promotionChoices ?? [], id: \.self) { choice in
                Button(choice.capitalized) { model.choosePromotion(choice) }
            }
        }
        .confirmationDialog("Resign this game?", isPresented: $showResignConfirmation) {
            Button("Resign", role: .destructive) { model.resign() }
            Button("Cancel", role: .cancel) {}
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 100_000_000)
                model.refreshGame()
            }
        }
        .onAppear { model.setGameForeground(true) }
        .onDisappear { model.setGameForeground(false) }
        .overlay {
            if model.game?.phase == "COMPLETED", model.preferences.celebrationsEnabled {
                GameCompletionOverlay(won: model.game?.winner == model.game?.humanSide)
                    .allowsHitTesting(false)
            }
        }
    }

    private var boardColumn: some View {
        VStack(spacing: 12) {
            HStack {
                Button { model.exitGame() } label: {
                    Label("Home", systemImage: "chevron.left")
                }
                .accessibilityIdentifier("game.home")
                Spacer()
                Button { model.flipBoard() } label: {
                    Label("Flip", systemImage: "arrow.triangle.2.circlepath")
                }
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(AppPalette.gold)

            PlayerStrip(
                title: model.opponentName,
                subtitle: localized("ios.offline_opponent"),
                time: clockText(opponentRemainingMillis),
                active: model.game?.sideToMove != model.game?.humanSide,
                portraitName: DrawlessChessModel.botLevels.first(where: { $0.id == model.setup.botLevelId })?.portraitName
            )
            .accessibilityIdentifier("game.opponent")

            ChessBoardView(model: model)
                .aspectRatio(1, contentMode: .fit)

            PlayerStrip(
                title: localized("ios.you"),
                subtitle: localized(model.game?.humanSide.capitalized ?? "White"),
                time: clockText(playerRemainingMillis),
                active: model.game?.sideToMove == model.game?.humanSide,
                portraitName: nil
            )
        }
    }

    private var sidePanel: some View {
        VStack(spacing: 14) {
            VStack(spacing: 5) {
                Text(localizedGameStatus)
                    .font(.title3.weight(.semibold))
                if let game = model.game, game.phase == "COMPLETED" {
                    Text(localizedEndReason(game))
                        .font(.subheadline)
                        .foregroundStyle(AppPalette.gold)
                } else {
                    Text(LocalizedStringKey(model.rulesName))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(14)
            .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("game.status")

            if let hint = model.hintText {
                Label(localizedFormat("ios.try_move", hint), systemImage: "lightbulb.fill")
                    .font(.headline)
                    .foregroundStyle(AppPalette.gold)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(AppPalette.gold.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityIdentifier("game.hintResult")
            }

            if let error = model.game?.engineError {
                VStack(alignment: .leading, spacing: 9) {
                    Label("Opponent unavailable", systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.red)
                    Text(verbatim: error)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button(localizedFormat("ios.retry_opponent", model.opponentName)) { model.retryOpponent() }
                        .buttonStyle(CompactButtonStyle())
                        .accessibilityIdentifier("game.retryOpponent")
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier("game.engineError")
            }

            if let game = model.game, game.phase == "COMPLETED" {
                VStack(alignment: .leading, spacing: 5) {
                    Text(localizedFormat("ios.score", Int(game.score), Int(game.scoreMaximumPoints)))
                        .font(.headline)
                    if game.hintPenalty > 0 { Text(localizedFormat("ios.penalty_hints", Int(game.hintPenalty))) }
                    if game.undoPenalty > 0 { Text(localizedFormat("ios.penalty_undos", Int(game.undoPenalty))) }
                    if game.pausePenalty > 0 { Text(localizedFormat("ios.penalty_pauses", Int(game.pausePenalty))) }
                    if game.threatPenalty > 0 { Text(localizedFormat("ios.penalty_threat", Int(game.threatPenalty))) }
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier("game.scoreBreakdown")
            }

            if let game = model.game, game.phase == "COMPLETED", game.reviewAvailable {
                VStack(alignment: .leading, spacing: 9) {
                    HStack {
                        Text(localized("ios.game_review")).font(.headline)
                        Text(localized("ios.review_beta"))
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 7).padding(.vertical, 3)
                            .background(AppPalette.gold.opacity(0.18), in: Capsule())
                    }
                    if game.reviewInProgress {
                        ProgressView(value: Double(game.reviewProgress), total: Double(max(1, game.reviewTotal)))
                        Text(localizedFormat(
                            "ios.review_progress",
                            Int(min(game.reviewProgress, game.reviewTotal)),
                            Int(game.reviewTotal)
                        ))
                            .font(.caption).foregroundStyle(.secondary)
                    } else if let error = game.reviewError {
                        Text(error).font(.caption).foregroundStyle(.red)
                        Button(localized("ios.review_retry")) { model.startReview() }
                            .buttonStyle(CompactButtonStyle())
                    } else if game.reviewSummary != nil {
                        Text(localizedFormat(
                            "ios.review_complete_body",
                            game.reviewMoves.count,
                            game.reviewMoves.filter { $0.quality != nil }.count
                        ))
                            .font(.subheadline)
                    } else {
                        Text(localized("ios.compare_review"))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Button(localized("ios.review_game")) { model.showReview() }
                        .buttonStyle(CompactButtonStyle())
                        .accessibilityIdentifier("game.startReview")
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier("game.review")
            }

            ScrollView {
                Text(model.game?.moveHistory.isEmpty == false
                     ? model.game!.moveHistory
                     : localized("ios.moves_placeholder"))
                    .font(.body.monospaced())
                    .foregroundStyle(model.game?.moveHistory.isEmpty == false ? .primary : .secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
            }
            .frame(minHeight: 82, maxHeight: 190)
            .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(historyAccessibilityLabel)
            .accessibilityIdentifier("game.history")

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                GameControlButton("Hint", icon: "lightbulb", enabled: model.game?.canHint == true) {
                    model.requestHint()
                }
                GameControlButton("Undo", icon: "arrow.uturn.backward", enabled: model.game?.canUndo == true) {
                    model.undo()
                }
                if model.game?.canResume == true {
                    GameControlButton("Resume", icon: "play.fill", enabled: true) { model.resume() }
                } else {
                    GameControlButton("Pause", icon: "pause.fill", enabled: model.game?.canPause == true) {
                        model.pause()
                    }
                }
                GameControlButton("Resign", icon: "flag.fill", enabled: model.game?.canResign == true) {
                    showResignConfirmation = true
                }
            }

            if model.game?.phase == "COMPLETED" {
                Button("Play Again") { model.startConfiguredGame() }
                    .buttonStyle(PrimaryButtonStyle())
                Button("Return Home") { model.exitGame() }
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.sidePanel")
    }

    private func clockText(_ millis: Int64) -> String {
        guard millis >= 0 else { return "∞" }
        let totalSeconds = max(0, millis / 1_000)
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private var localizedGameStatus: String {
        guard let game = model.game else { return localized("ios.starting_game") }
        switch game.phase {
        case "HUMAN_TURN": return localized("Your move")
        case "HINT_THINKING": return localized("Finding a hint")
        case "BOT_THINKING": return localizedFormat("ios.status_thinking", model.opponentName)
        case "BOT_ERROR": return localized("Opponent unavailable")
        case "PAUSED": return localized("Game paused")
        case "COMPLETED":
            if game.winner == game.humanSide { return localized("ios.status_you_won") }
            if game.winner != nil { return localized("ios.status_you_lost") }
            return localized("ios.status_complete")
        default: return localized("ios.starting_game")
        }
    }

    private func localizedEndReason(_ game: SharedGameView) -> String {
        switch game.endReason {
        case "CHECKMATE":
            return localized("Checkmate.")
        case "STALEMATE":
            return localized(game.presetId == "ESCAPE"
                ? "The player to move had no legal move and was not in check. Under Escape rules, that player wins."
                : "The player to move had no legal move and was not in check. Under Drawless rules, that player loses.")
        case "REPETITION":
            return localized("The same position occurred three times, so the repetition rule decided the game.")
        case "DEAD_POSITION_MATERIAL":
            return localized("Checkmate became impossible, so the material rule decided the winner.")
        case "DEAD_POSITION_FINAL_CAPTURE":
            return localized("That move made checkmate impossible. Under the Final Capture rule, the player who made the move won.")
        case "BARE_KING":
            return localized("• A player left with only a king loses immediately.")
        case "FIFTY_MOVE_LIMIT":
            return localized("The 50-move limit was reached, so that rule decided the game.")
        case "RESIGNATION":
            return localized("The game ended by resignation.")
        case "TIMEOUT":
            return localized("Time expired.")
        default:
            return localized("Game complete")
        }
    }

    private func localizedReviewSummary(_ summary: String) -> String {
        let numbers = summary.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        if summary.hasPrefix("Reviewed"), numbers.count >= 2 {
            return localizedFormat("ios.review_failures", numbers[0], numbers[1])
        }
        if numbers.count >= 2 {
            return localizedFormat("ios.review_matches", numbers[0], numbers[1])
        }
        return summary
    }

    private func localizedReviewDetails(_ details: String) -> String {
        details.split(separator: "\n", omittingEmptySubsequences: false).map { rawLine in
            let line = String(rawLine)
            if line.hasSuffix(" — match") {
                return String(line.dropLast("match".count)) + localized("ios.review_match")
            }
            if line.hasSuffix(" — unavailable") {
                return String(line.dropLast("unavailable".count)) + localized("ios.review_unavailable")
            }
            return line.replacingOccurrences(
                of: " — engine ",
                with: " — \(localized("ios.review_engine")) "
            )
        }.joined(separator: "\n")
    }

    private var opponentRemainingMillis: Int64 {
        guard let game = model.game else { return -1 }
        return game.humanSide == "WHITE" ? game.blackRemainingMillis : game.whiteRemainingMillis
    }

    private var playerRemainingMillis: Int64 {
        guard let game = model.game else { return -1 }
        return game.humanSide == "WHITE" ? game.whiteRemainingMillis : game.blackRemainingMillis
    }

    private var historyAccessibilityLabel: String {
        guard let history = model.game?.moveHistory, !history.isEmpty else {
            return localized("ios.moves_placeholder")
        }
        return history
    }
}

private struct GameReviewView: View {
    @ObservedObject var model: DrawlessChessModel
    @State private var selectedPly: Int32?

    private var moves: [SharedReviewMove] { model.game?.reviewMoves ?? [] }
    private var selectedMove: SharedReviewMove? {
        moves.first(where: { $0.ply == selectedPly }) ?? moves.first
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 12) {
                    Button { model.leaveReview() } label: {
                        Label("Back", systemImage: "chevron.left")
                    }
                    .accessibilityIdentifier("review.back")
                    Spacer()
                    Text(localized("ios.game_review"))
                        .font(.title2.weight(.bold))
                    Text(localized("ios.review_beta"))
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(AppPalette.gold.opacity(0.18), in: Capsule())
                }
                .foregroundStyle(AppPalette.gold)

                reviewStatus

                if !moves.isEmpty {
                    reviewSummary
                    Button(localized("ios.review_my_mistakes")) {
                        if let issue = moves.first(where: { reviewIsIssue($0.quality) }) {
                            selectedPly = issue.ply
                        }
                    }
                    .buttonStyle(CompactButtonStyle())
                    .disabled(!moves.contains(where: { reviewIsIssue($0.quality) }))
                    .accessibilityIdentifier("review.myMistakes")

                    VStack(alignment: .leading, spacing: 10) {
                        Text(localized("ios.review_moves")).font(.headline)
                        ForEach(moves, id: \.ply) { move in
                            Button {
                                selectedPly = move.ply
                            } label: {
                                HStack(spacing: 10) {
                                    Text(reviewMoveTitle(move))
                                        .font(.body.monospaced())
                                        .foregroundStyle(.primary)
                                    Spacer()
                                    ReviewGradeBadge(quality: move.quality)
                                }
                                .padding(11)
                                .background(
                                    (selectedMove?.ply == move.ply ? AppPalette.gold.opacity(0.15) : Color.clear),
                                    in: RoundedRectangle(cornerRadius: 10)
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("review.move.\(move.ply)")
                        }
                    }
                    .padding(14)
                    .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))

                    if let move = selectedMove {
                        reviewDetail(move)
                        reviewNavigator(move)
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: 820)
            .frame(maxWidth: .infinity)
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 100_000_000)
                model.refreshGame()
            }
        }
    }

    @ViewBuilder
    private var reviewStatus: some View {
        if let game = model.game, game.reviewInProgress {
            VStack(alignment: .leading, spacing: 8) {
                Text(localized("ios.review_analyzing")).font(.headline)
                ProgressView(value: Double(game.reviewProgress), total: Double(max(1, game.reviewTotal)))
                Text(localizedFormat("ios.review_progress", Int(game.reviewProgress), Int(game.reviewTotal)))
                    .font(.caption).foregroundStyle(.secondary)
            }
            .padding(14)
            .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
            .accessibilityIdentifier("review.status")
        } else if let error = model.game?.reviewError {
            VStack(alignment: .leading, spacing: 8) {
                Text(localized("ios.review_failed")).font(.headline)
                Text(error).font(.footnote).foregroundStyle(.secondary)
                Button(localized("ios.review_retry")) { model.startReview() }
                    .buttonStyle(CompactButtonStyle())
            }
            .padding(14)
            .background(Color.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
        } else if model.game?.reviewSummary != nil {
            Text(localized("ios.review_complete"))
                .font(.headline)
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
        }
    }

    private var reviewSummary: some View {
        let game = model.game
        return VStack(alignment: .leading, spacing: 10) {
            Text(localized("ios.review_summary_title")).font(.headline)
            Text(localizedFormat(
                "ios.review_complete_body",
                moves.count,
                moves.filter { $0.quality != nil }.count
            ))
                .font(.footnote).foregroundStyle(.secondary)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 112))], spacing: 8) {
                ReviewCountChip(quality: "BEST", count: Int(game?.reviewBestCount ?? 0))
                ReviewCountChip(quality: "GOOD", count: Int(game?.reviewGoodCount ?? 0))
                ReviewCountChip(quality: "INACCURACY", count: Int(game?.reviewInaccuracyCount ?? 0))
                ReviewCountChip(quality: "MISTAKE", count: Int(game?.reviewMistakeCount ?? 0))
                ReviewCountChip(quality: "BLUNDER", count: Int(game?.reviewBlunderCount ?? 0))
            }
        }
        .padding(14)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityIdentifier("review.summary")
    }

    private func reviewDetail(_ move: SharedReviewMove) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(reviewMoveTitle(move)).font(.title3.monospaced().weight(.semibold))
                Spacer()
                ReviewGradeBadge(quality: move.quality)
            }
            Text(localized(reviewGradeExplanationKey(move.quality)))
                .font(.footnote).foregroundStyle(.secondary)
            if move.bestMove != move.playedMove {
                Text(localizedFormat("ios.review_better_move", move.bestMove))
                    .font(.headline).foregroundStyle(AppPalette.gold)
            }
            if !move.suggestedLine.isEmpty {
                Text(localizedFormat("ios.review_suggested_line", move.suggestedLine.joined(separator: " ")))
                    .font(.footnote.monospaced())
            }
            if let evaluation = move.playedEvaluationText {
                Text(localizedFormat("ios.review_evaluation", localized("ios.you"), evaluation))
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityIdentifier("review.detail")
    }

    private func reviewNavigator(_ move: SharedReviewMove) -> some View {
        let index = moves.firstIndex(where: { $0.ply == move.ply }) ?? 0
        return HStack(spacing: 10) {
            Button { selectedPly = moves[max(0, index - 1)].ply } label: {
                Label(localized("ios.review_previous"), systemImage: "chevron.left")
            }
            .disabled(index == 0)
            Spacer()
            Text(localizedFormat("ios.review_move_position", index + 1, moves.count))
                .font(.caption.weight(.semibold))
            Spacer()
            Button { selectedPly = moves[min(moves.count - 1, index + 1)].ply } label: {
                Label(localized("ios.review_next"), systemImage: "chevron.right")
            }
            .disabled(index + 1 >= moves.count)
        }
        .font(.footnote.weight(.semibold))
    }

    private func reviewMoveTitle(_ move: SharedReviewMove) -> String {
        let number = (Int(move.ply) + 1) / 2
        return move.mover == "WHITE"
            ? "\(number). \(move.playedMove)"
            : "\(number)… \(move.playedMove)"
    }
}

private struct ReviewGradeBadge: View {
    let quality: String?

    var body: some View {
        Text(localized(reviewGradeKey(quality)))
            .font(.caption.weight(.bold))
            .foregroundStyle(reviewGradeColor(quality))
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(reviewGradeColor(quality).opacity(0.14), in: Capsule())
    }
}

private struct ReviewCountChip: View {
    let quality: String
    let count: Int

    var body: some View {
        HStack(spacing: 5) {
            Text(localized(reviewGradeKey(quality)))
            Text(String(count)).fontWeight(.bold)
        }
        .font(.caption)
        .foregroundStyle(reviewGradeColor(quality))
        .padding(.horizontal, 9).padding(.vertical, 6)
        .background(reviewGradeColor(quality).opacity(0.12), in: Capsule())
    }
}

private func reviewGradeKey(_ quality: String?) -> String {
    switch quality {
    case "BEST": return "ios.review_grade_best"
    case "GOOD": return "ios.review_grade_good"
    case "INACCURACY": return "ios.review_grade_inaccuracy"
    case "MISTAKE": return "ios.review_grade_mistake"
    case "BLUNDER": return "ios.review_grade_blunder"
    default: return "ios.review_grade_unreviewed"
    }
}

private func reviewGradeExplanationKey(_ quality: String?) -> String {
    switch quality {
    case "BEST": return "ios.review_grade_best_explanation"
    case "GOOD": return "ios.review_grade_good_explanation"
    case "INACCURACY": return "ios.review_grade_inaccuracy_explanation"
    case "MISTAKE": return "ios.review_grade_mistake_explanation"
    case "BLUNDER": return "ios.review_grade_blunder_explanation"
    default: return "ios.review_waiting"
    }
}

private func reviewGradeColor(_ quality: String?) -> Color {
    switch quality {
    case "BEST": return .green
    case "GOOD": return .mint
    case "INACCURACY": return .yellow
    case "MISTAKE": return .orange
    case "BLUNDER": return .red
    default: return .secondary
    }
}

private func reviewIsIssue(_ quality: String?) -> Bool {
    quality == "INACCURACY" || quality == "MISTAKE" || quality == "BLUNDER"
}

private struct ChessBoardView: View {
    @ObservedObject var model: DrawlessChessModel
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 8)

    var body: some View {
        let themeId = model.game?.boardThemeId ?? model.preferences.boardThemeId
        let cells = model.game?.cells ?? []
        ZStack {
            LazyVGrid(columns: columns, spacing: 0) {
                ForEach(cells, id: \.displayIndex) { cell in
                    Button {
                        model.tap(cell.displayIndex)
                    } label: {
                        GeometryReader { proxy in
                            ZStack {
                                BoardSquareSurface(
                                    themeId: themeId,
                                    isLight: !cell.darkSquare,
                                    square: cell.square
                                )
                                if cell.lastMove {
                                    Color(argb: model.game?.lastMoveArgb ?? 0x88D4AF37)
                                }
                                if cell.threatened { Color.red.opacity(0.25) }
                                if cell.selected {
                                    Color(argb: model.game?.selectedArgb ?? 0xCCD4AF37)
                                    Rectangle().stroke(AppPalette.gold, lineWidth: 3)
                                }
                                if cell.legalTarget {
                                    if cell.captureTarget {
                                        Circle().stroke(
                                            Color(argb: model.game?.legalCaptureArgb ?? 0x99B03A48),
                                            lineWidth: 4
                                        )
                                            .padding(proxy.size.width * 0.08)
                                    } else {
                                        Circle().fill(Color(argb: model.game?.legalMoveArgb ?? 0x99C9A227))
                                            .frame(width: proxy.size.width * 0.24)
                                    }
                                }
                                if cell.inCheck {
                                    Circle().fill(Color(argb: model.game?.checkArgb ?? 0xB3B22B38))
                                        .padding(proxy.size.width * 0.05)
                                }
                                ChessPieceView(pieceCode: cell.pieceCode, themeId: themeId)
                                    .padding(proxy.size.width * 0.015)
                                if model.preferences.coordinatesEnabled {
                                    coordinateLabels(cell: cell)
                                }
                            }
                        }
                        .aspectRatio(1, contentMode: .fit)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(localizedBoardCellLabel(cell))
                    .accessibilityIdentifier("square.\(cell.square)")
                }
            }
            if let from = model.game?.hintFromSquare, let to = model.game?.hintToSquare {
                BoardMoveArrowOverlay(cells: cells, fromSquare: from, toSquare: to)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(AppPalette.gold, lineWidth: 2))
        .shadow(color: .black.opacity(0.45), radius: 16, y: 8)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(localized("ios.chess_board"))
        .accessibilityIdentifier("game.board")
    }

    @ViewBuilder
    private func coordinateLabels(cell: SharedBoardCell) -> some View {
        let index = Int(cell.displayIndex)
        let labelColor = cell.darkSquare ? Color.white.opacity(0.76) : Color.black.opacity(0.65)
        ZStack {
            if index % 8 == 0, let rank = cell.square.last {
                VStack {
                    HStack {
                        Text(String(rank))
                        Spacer()
                    }
                    Spacer()
                }
            }
            if index / 8 == 7, let file = cell.square.first {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Text(String(file))
                    }
                }
            }
        }
        .font(.system(size: 9, weight: .bold, design: .rounded))
        .foregroundStyle(labelColor)
        .padding(2)
        .accessibilityHidden(true)
    }
}

private struct BoardMoveArrowOverlay: View {
    let cells: [SharedBoardCell]
    let fromSquare: String
    let toSquare: String

    var body: some View {
        Canvas { context, size in
            guard
                let fromIndex = cells.first(where: { $0.square == fromSquare })?.displayIndex,
                let toIndex = cells.first(where: { $0.square == toSquare })?.displayIndex
            else { return }

            let squareWidth = size.width / 8
            let squareHeight = size.height / 8
            func center(_ displayIndex: Int32) -> CGPoint {
                let index = Int(displayIndex)
                return CGPoint(
                    x: (CGFloat(index % 8) + 0.5) * squareWidth,
                    y: (CGFloat(index / 8) + 0.5) * squareHeight
                )
            }

            let start = center(fromIndex)
            let end = center(toIndex)
            let angle = atan2(end.y - start.y, end.x - start.x)
            let headLength = min(squareWidth, squareHeight) * 0.28
            let headAngle = CGFloat.pi / 6
            let shaftWidth = max(3, min(squareWidth, squareHeight) * 0.11)

            var shaft = Path()
            shaft.move(to: start)
            shaft.addLine(to: end)
            context.stroke(
                shaft,
                with: .color(.black.opacity(0.72)),
                style: StrokeStyle(lineWidth: shaftWidth + 3, lineCap: .round, lineJoin: .round)
            )
            context.stroke(
                shaft,
                with: .color(AppPalette.gold),
                style: StrokeStyle(lineWidth: shaftWidth, lineCap: .round, lineJoin: .round)
            )

            var head = Path()
            head.move(to: end)
            head.addLine(to: CGPoint(
                x: end.x - headLength * cos(angle - headAngle),
                y: end.y - headLength * sin(angle - headAngle)
            ))
            head.move(to: end)
            head.addLine(to: CGPoint(
                x: end.x - headLength * cos(angle + headAngle),
                y: end.y - headLength * sin(angle + headAngle)
            ))
            context.stroke(
                head,
                with: .color(.black.opacity(0.72)),
                style: StrokeStyle(lineWidth: shaftWidth + 3, lineCap: .round, lineJoin: .round)
            )
            context.stroke(
                head,
                with: .color(AppPalette.gold),
                style: StrokeStyle(lineWidth: shaftWidth, lineCap: .round, lineJoin: .round)
            )
        }
    }
}

private struct OptionsView: View {
    @ObservedObject var model: DrawlessChessModel

    var body: some View {
        Form {
            Section {
                Toggle("Sound", isOn: $model.preferences.soundEnabled)
                    .accessibilityIdentifier("options.sound")
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("Sound volume")
                        Spacer()
                        Text(verbatim: "\(model.preferences.soundVolumePercent)%").foregroundStyle(.secondary)
                    }
                    Slider(
                        value: Binding(
                            get: { Double(model.preferences.soundVolumePercent) },
                            set: { model.preferences.soundVolumePercent = Int($0.rounded()) }
                        ),
                        in: 0...100,
                        step: 10,
                        onEditingChanged: { editing in if !editing { model.previewSound() } }
                    )
                    .disabled(!model.preferences.soundEnabled)
                    .accessibilityIdentifier("options.soundVolume")
                }
                Toggle("Haptic feedback", isOn: $model.preferences.hapticsEnabled)
                    .accessibilityIdentifier("options.haptics")
                Toggle("Board coordinates", isOn: $model.preferences.coordinatesEnabled)
                    .accessibilityIdentifier("options.coordinates")
                Toggle("Celebration effects", isOn: $model.preferences.celebrationsEnabled)
                    .accessibilityIdentifier("options.celebrations")
            } header: { Text(localized("ios.presentation")) }
            Section {
                Picker("Board theme", selection: $model.preferences.boardThemeId) {
                    ForEach(DrawlessChessModel.boardThemes, id: \.id) { theme in
                        Text(LocalizedStringKey(theme.name)).tag(theme.id)
                    }
                }
                .pickerStyle(.menu)
                .accessibilityIdentifier("options.boardTheme")
                StartingPositionBoard(themeId: model.preferences.boardThemeId)
                    .frame(maxWidth: 250)
                    .padding(.vertical, 8)
                    .accessibilityHidden(true)
            } header: { Text("Theme") }
            Section {
                Toggle("Show threatened pieces", isOn: $model.preferences.threatIndicationEnabled)
                    .accessibilityIdentifier("options.threats")
                Text(localized("ios.threat_score_notice"))
                    .font(.footnote)
            } header: { Text(localized("ios.assistance")) }
            Section {
                HStack {
                    Text(localized("ios.version"))
                    Spacer()
                    Text(verbatim: "1.0.0").foregroundStyle(.secondary)
                }
                HStack {
                    Text(localized("ios.data"))
                    Spacer()
                    Text(localized("ios.stored_only")).foregroundStyle(.secondary)
                }
                Link("Open-source license", destination: URL(string: "https://github.com/DeviousVon/Drawless-Chess")!)
            } header: { Text(localized("ios.about")) }
        }
        .safeAreaInset(edge: .top) {
            ScreenHeader(title: "Options", back: { model.savePreferencesAndGoHome() })
                .padding(.horizontal, 18)
                .background(AppPalette.background.opacity(0.96))
        }
    }
}

private struct StatisticsView: View {
    @ObservedObject var model: DrawlessChessModel

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                ScreenHeader(title: "Statistics", back: { model.route = .home })
                HStack(spacing: 12) {
                    StatCard(value: "\(model.statistics.games)", label: localized("ios.games"))
                        .accessibilityIdentifier("statistics.games")
                    StatCard(value: "\(model.statistics.wins)", label: "Wins")
                        .accessibilityIdentifier("statistics.wins")
                    StatCard(value: "\(model.statistics.losses)", label: localized("ios.losses"))
                        .accessibilityIdentifier("statistics.losses")
                }
                HStack(spacing: 12) {
                    StatCard(
                        value: model.statistics.winPercentage.map { String(format: "%.1f%%", $0) } ?? "—",
                        label: "Win rate"
                    )
                    StatCard(
                        value: model.statistics.averageScore.map { String(format: "%.1f", $0) } ?? "—",
                        label: "Average score"
                    )
                }
                HStack(spacing: 12) {
                    StatCard(value: "\(model.statistics.currentWinStreak)", label: "Current streak")
                    StatCard(value: "\(model.statistics.bestWinStreak)", label: "Best streak")
                    StatCard(value: "\(model.statistics.unassistedWins)", label: "Unassisted wins")
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("About game score").font(.headline)
                    Text("A clean win scores 100. Successful hints and undos deduct 10 each; timed pauses and threat indication deduct 5. Losses score 0.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding(16)
                .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
                .frame(maxWidth: .infinity, alignment: .leading)
                if !model.statistics.opponents.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("By opponent").font(.title3.weight(.semibold))
                        ForEach(model.statistics.opponents) { opponent in
                            HStack(spacing: 12) {
                                if let level = model.opponentLevels.first(where: { $0.id == opponent.id }) {
                                    OpponentPortrait(level: level, size: 46)
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: "\(opponent.name) · \(opponent.elo)").font(.headline)
                                    Text(localizedFormat(
                                        "ios.opponent_record",
                                        opponent.games,
                                        opponent.wins,
                                        opponent.losses
                                    ))
                                    Text(localizedFormat(
                                        "ios.opponent_summary",
                                        opponent.winPercentage,
                                        opponent.averageScore
                                    ))
                                }
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                Spacer()
                            }
                            if opponent.id != model.statistics.opponents.last?.id { Divider() }
                        }
                    }
                    .padding(16)
                    .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
                }
                Text(localized("ios.statistics_local_notice"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(22)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
    }
}

private struct StartingPositionBoard: View {
    let themeId: String
    private let pieces = ["bR", "bN", "bB", "bQ", "bK", "bB", "bN", "bR"]
        + Array(repeating: "bP", count: 8)
        + Array(repeating: "", count: 32)
        + Array(repeating: "wP", count: 8)
        + ["wR", "wN", "wB", "wQ", "wK", "wB", "wN", "wR"]
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 8)

    var body: some View {
        LazyVGrid(columns: columns, spacing: 0) {
            ForEach(0..<64, id: \.self) { index in
                ZStack {
                    BoardSquareSurface(
                        themeId: themeId,
                        isLight: (index / 8 + index % 8).isMultiple(of: 2),
                        file: index % 8,
                        rank: 7 - index / 8
                    )
                    ChessPieceView(pieceCode: pieces[index], themeId: themeId)
                }
                .aspectRatio(1, contentMode: .fit)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(AppPalette.gold, lineWidth: 2))
        .shadow(color: .black.opacity(0.45), radius: 16, y: 8)
    }
}

private struct PlayerStrip: View {
    let title: String
    let subtitle: String
    let time: String
    let active: Bool
    let portraitName: String?

    var body: some View {
        HStack {
            if let portraitName {
                Image(portraitName)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 38, height: 38)
                    .clipShape(Circle())
                    .overlay(Circle().stroke(active ? AppPalette.mint : Color.secondary, lineWidth: 2))
                    .accessibilityHidden(true)
            } else {
                Image(systemName: "person.crop.circle.fill")
                    .font(.system(size: 34))
                    .foregroundStyle(active ? AppPalette.mint : Color.secondary)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.headline)
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Text(time)
                .font(.title3.monospacedDigit().weight(.semibold))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct SetupSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(LocalizedStringKey(title))
                .font(.caption.weight(.bold))
                .tracking(1.2)
                .textCase(.uppercase)
                .foregroundStyle(AppPalette.gold)
            content
        }
        .padding(16)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct ScreenHeader: View {
    let title: String
    let back: () -> Void

    var body: some View {
        HStack {
            Button(action: back) { Image(systemName: "chevron.left") }
                .accessibilityLabel("Back")
            Text(LocalizedStringKey(title)).font(.title2.weight(.semibold))
            Spacer()
        }
        .foregroundStyle(AppPalette.gold)
        .padding(.vertical, 10)
    }
}

private struct GameControlButton: View {
    let title: String
    let icon: String
    let enabled: Bool
    let action: () -> Void

    init(_ title: String, icon: String, enabled: Bool, action: @escaping () -> Void) {
        self.title = title
        self.icon = icon
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Label {
                Text(LocalizedStringKey(title))
            } icon: {
                Image(systemName: icon)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(CompactButtonStyle())
        .disabled(!enabled)
        .accessibilityIdentifier("game.\(title.lowercased())")
    }
}

private struct StatCard: View {
    let value: String
    let label: String

    var body: some View {
        VStack(spacing: 5) {
            Text(value).font(.system(.largeTitle, design: .rounded).weight(.bold))
                .foregroundStyle(AppPalette.gold)
            Text(LocalizedStringKey(label)).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(18)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct AppBackground: View {
    var body: some View {
        LinearGradient(
            colors: [AppPalette.background, Color(red: 0.11, green: 0.15, blue: 0.13)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
    }
}

private struct OpponentPortrait: View {
    let level: DrawlessChessModel.BotLevel
    let size: CGFloat

    var body: some View {
        Image(level.portraitName)
            .resizable()
            .scaledToFill()
            .frame(width: size, height: size)
            .clipShape(Circle())
            .overlay(Circle().stroke(AppPalette.gold.opacity(0.8), lineWidth: 2))
            .accessibilityLabel(localizedFormat(
                "ios.name_epithet",
                level.name,
                localized(level.epithet)
            ))
    }
}

private enum PendingNewGameAction: String, Identifiable {
    case quickPlay
    case customGame

    var id: String { rawValue }
}

private enum InfoSheet: String, Identifiable {
    case rules
    case license
    case privacy

    var id: String { rawValue }
    var title: String {
        switch self {
        case .rules: localized("Drawless in one minute")
        case .license: localized("Open-source software")
        case .privacy: localized("Privacy")
        }
    }
    var body: String {
        switch self {
        case .rules:
            [
                "• Checkmate still wins.",
                "• In default Drawless, a player with no legal move loses. The optional Escape variant makes stalemate a win instead.",
                "• Causing the same position a third time loses, unless every legal move repeats.",
                "• If checkmate becomes impossible, the selected dead-position rule awards the game.",
                "• A player left with only a king loses immediately.",
                "• After 50 moves without a pawn move or capture, material points decide the winner.",
            ].map(localized).joined(separator: "\n\n")
        case .license:
            localized("ios.license_body")
        case .privacy:
            localized("ios.privacy_body")
        }
    }
    var link: (String, URL)? {
        switch self {
        case .rules: nil
        case .license: (localized("ios.view_source"), URL(string: "https://github.com/DeviousVon/Drawless-Chess")!)
        case .privacy: (localized("ios.privacy_contact"), URL(string: "mailto:realitymaster@protonmail.ch")!)
        }
    }
}

private struct InformationSheet: View {
    @Environment(\.dismiss) private var dismiss
    let sheet: InfoSheet

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text(verbatim: sheet.body).font(.body)
                    if let link = sheet.link {
                        Link(link.0, destination: link.1)
                            .font(.headline)
                    }
                }
                .padding(22)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(sheet.title)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

private struct GameCompletionOverlay: View {
    let won: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expanded = false

    var body: some View {
        ZStack {
            Color(won ? .systemGreen : .systemRed).opacity(expanded ? 0.10 : 0)
            if !reduceMotion {
                ForEach(0..<18, id: \.self) { index in
                    Circle()
                        .fill(index.isMultiple(of: 2) ? AppPalette.gold : AppPalette.mint)
                        .frame(width: CGFloat(5 + index % 4) * 2)
                        .offset(
                            x: expanded ? CGFloat((index % 6) - 3) * 54 : 0,
                            y: expanded ? CGFloat((index / 6) - 1) * 120 : 0
                        )
                        .opacity(expanded ? 0 : 0.9)
                }
            }
            Image(systemName: won ? "crown.fill" : "shield.slash.fill")
                .font(.system(size: 70, weight: .bold))
                .foregroundStyle(won ? AppPalette.gold : Color.red)
                .scaleEffect(expanded ? 1.0 : 0.55)
                .opacity(expanded ? 0.22 : 0)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
        .onAppear {
            withAnimation(reduceMotion ? nil : .easeOut(duration: 1.1)) {
                expanded = true
            }
        }
    }
}

private func localized(_ key: String) -> String {
    NSLocalizedString(key, comment: "")
}

private func localizedFormat(_ key: String, _ arguments: CVarArg...) -> String {
    String(format: localized(key), locale: Locale.current, arguments: arguments)
}

private func localizedBoardCellLabel(_ cell: SharedBoardCell) -> String {
    let code = Array(cell.pieceCode.utf8)
    var facts: [String]
    if code.count == 2 {
        let side = localized(code[0] == Character("w").asciiValue ? "White" : "Black")
        let pieceName: String = switch code[1] {
        case Character("P").asciiValue: localized("pawn")
        case Character("N").asciiValue: localized("knight")
        case Character("B").asciiValue: localized("bishop")
        case Character("R").asciiValue: localized("rook")
        case Character("Q").asciiValue: localized("queen")
        case Character("K").asciiValue: localized("king")
        default: cell.pieceCode
        }
        facts = [localizedFormat("ios.piece_on_square", side, pieceName, cell.square)]
    } else {
        facts = [localizedFormat("ios.empty_square", cell.square)]
    }
    if cell.captureTarget { facts.append(localized("legal capture")) }
    else if cell.legalTarget { facts.append(localized("legal move")) }
    if cell.inCheck { facts.append(localized("in check")) }
    if cell.threatened { facts.append(localized("under threat")) }
    if cell.selected { facts.append(localized("Selected")) }
    return facts.joined(separator: ", ")
}

private extension Color {
    init(argb: Int64) {
        let value = UInt64(argb)
        self.init(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255,
            opacity: Double((value >> 24) & 0xFF) / 255
        )
    }
}

private enum AppPalette {
    static let background = Color(red: 0.065, green: 0.075, blue: 0.072)
    static let panel = Color(red: 0.115, green: 0.14, blue: 0.12)
    static let gold = Color(red: 0.82, green: 0.71, blue: 0.42)
    static let mint = Color(red: 0.31, green: 0.86, blue: 0.72)
    static let lightSquare = Color(red: 0.91, green: 0.88, blue: 0.80)
    static let darkSquare = Color(red: 0.19, green: 0.31, blue: 0.26)
    static let lastMove = Color(red: 0.89, green: 0.70, blue: 0.22)
}

private struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .padding(.vertical, 14)
            .padding(.horizontal, 18)
            .background(AppPalette.gold.opacity(configuration.isPressed ? 0.72 : 1),
                        in: RoundedRectangle(cornerRadius: 12))
            .foregroundStyle(Color.black)
    }
}

private struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 13)
            .background(AppPalette.panel.opacity(configuration.isPressed ? 0.7 : 1),
                        in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(AppPalette.gold.opacity(0.55)))
            .foregroundStyle(AppPalette.gold)
    }
}

private struct CompactButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .background(AppPalette.panel.opacity(configuration.isPressed ? 0.65 : 1),
                        in: RoundedRectangle(cornerRadius: 11))
            .foregroundStyle(.primary)
    }
}

#Preview {
    ContentView()
}
