import DrawlessShared
import Foundation
import SwiftUI
import UIKit

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
#if DRAWLESS_IOS_GAME_REVIEW
            case .review:
                GameReviewView(model: model)
#endif
            case .options:
                OptionsView(model: model) { model.savePreferencesAndGoHome() }
            case .statistics:
                StatisticsView(model: model)
            }
        }
        .onAppear {
            // System sheets (USB trust, Control Center, permission prompts, and similar
            // interruptions) temporarily make the scene inactive even though the game remains
            // visible. Only a real background transition should suspend foreground review work.
            model.setSceneActive(scenePhase != .background)
        }
        .onChange(of: scenePhase) { phase in
            model.setSceneActive(phase != .background)
        }
    }
}

private struct HomeView: View {
    @ObservedObject var model: DrawlessChessModel
    @State private var infoSheet: InfoSheet?
    @State private var pendingNewGame: PendingNewGameAction?
    @State private var isQuickPlayOpponentPickerPresented = false
    @State private var isThemePickerPresented = false

    private var quickPlayOpponent: DrawlessChessModel.BotLevel? {
        model.opponentLevels.first(where: { $0.id == model.quickPlayOpponentId })
    }

    private var selectedThemeName: String {
        DrawlessChessModel.boardThemes
            .first(where: { $0.id == model.preferences.boardThemeId })?
            .name ?? "Imperial Marble"
    }

    private var homeStatisticsSummary: String {
        let stats = model.statistics
        guard stats.games > 0 else { return localized("No completed games yet") }
        let winPercentage = stats.winPercentage
            .map { String(format: "%.1f", locale: Locale.current, $0) } ?? "—"
        let averageScore = stats.averageScore
            .map { String(format: "%.1f", locale: Locale.current, $0) } ?? "—"
        return localizedFormat(
            "%1$d–%2$d · %3$s%% wins · Avg %4$s",
            stats.wins,
            stats.losses,
            winPercentage,
            averageScore
        )
    }

    private func levelName(_ level: DrawlessChessModel.BotLevel) -> String {
        localized(level.id == "adaptive" ? "Adaptive" : level.id.capitalized)
    }

    private func quickPlaySummary(_ opponent: DrawlessChessModel.BotLevel) -> String {
        localizedFormat(
            "Drawless · %1$s, %2$s · Random side · Untimed",
            opponent.name,
            levelName(opponent)
        )
    }

    var body: some View {
        GeometryReader { geometry in
            let spacing = max(7, min(12, geometry.size.height * 0.014))
            ScrollView(showsIndicators: false) {
                VStack(spacing: spacing) {
                    Spacer(minLength: 6)

                    bundledPortraitImage(named: "home_hero_kings")
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 430)
                        .frame(maxHeight: min(190, geometry.size.height * 0.24))
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(localized("DRAWLESS CHESS. Every game has a winner."))
                        .accessibilityIdentifier("home.header")

                    if model.hasResumableGame {
                        Button {
                            model.resumeSavedGame()
                        } label: {
                            Label("Resume Game", systemImage: "play.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("home.resume")

                        Button(role: .destructive) {
                            model.discardSavedGame()
                        } label: {
                            Text("Discard Saved Game")
                                .font(.footnote.weight(.semibold))
                                .frame(minHeight: 44)
                        }
                        .foregroundStyle(AppPalette.dangerText)
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

                    if let opponent = quickPlayOpponent {
                        Button {
                            isQuickPlayOpponentPickerPresented = true
                        } label: {
                            HStack(spacing: 10) {
                                OpponentPortrait(level: opponent, size: 38)
                                    .accessibilityHidden(true)
                                VStack(spacing: 2) {
                                    Text("Opponent")
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(AppPalette.secondaryText)
                                    Text(verbatim: "\(opponent.name) · \(levelName(opponent))")
                                        .font(.headline)
                                }
                                .frame(maxWidth: .infinity)
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.bold))
                                    .frame(width: 38)
                                    .accessibilityHidden(true)
                            }
                            .padding(.horizontal, 10)
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(AppPalette.gold.opacity(0.55)))
                            .foregroundStyle(AppPalette.gold)
                            .contentShape(RoundedRectangle(cornerRadius: 12))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(localizedFormat(
                            "Opponent, %1$s, %2$s",
                            opponent.name,
                            levelName(opponent)
                        ))
                        .accessibilityIdentifier("home.quickOpponent")

                        Text(quickPlaySummary(opponent))
                            .font(.caption)
                            .foregroundStyle(AppPalette.highContrastText)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("home.quickOpponentSummary")
                    }

                    Button("Custom game") {
                        if model.hasResumableGame { pendingNewGame = .customGame }
                        else { model.showCustomGameSetup() }
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("home.newGame")

                    Button {
                        isThemePickerPresented = true
                    } label: {
                        Text(localizedFormat("Theme · %1$s", localized(selectedThemeName)))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("home.theme")

                    Button {
                        model.route = .statistics
                    } label: {
                        VStack(spacing: 2) {
                            Text("Player stats")
                            Text(homeStatisticsSummary)
                                .font(.caption)
                                .foregroundStyle(AppPalette.secondaryText)
                        }
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("home.statistics")

                    Button("Options") { model.route = .options }
                        .buttonStyle(SecondaryButtonStyle())
                        .accessibilityIdentifier("home.options")

                    VStack(spacing: 1) {
                        HStack(spacing: 10) {
                            Button("How Drawless works") { infoSheet = .rules }
                                .frame(minHeight: 44)
                                .padding(.horizontal, 10)
                                .background(AppPalette.panel, in: Capsule())
                                .contentShape(Capsule())
                                .buttonStyle(.plain)
                                .foregroundStyle(.primary)
                                .tint(Color.primary)
                                .accessibilityIdentifier("home.rules")
                            Button("Open-source license") { infoSheet = .license }
                                .frame(minHeight: 44)
                                .padding(.horizontal, 10)
                                .background(AppPalette.panel, in: Capsule())
                                .contentShape(Capsule())
                                .buttonStyle(.plain)
                                .foregroundStyle(.primary)
                                .tint(Color.primary)
                                .accessibilityIdentifier("home.license")
                        }
                        Button("Privacy") { infoSheet = .privacy }
                            .frame(minHeight: 44)
                            .padding(.horizontal, 10)
                            .background(AppPalette.panel, in: Capsule())
                            .contentShape(Capsule())
                            .buttonStyle(.plain)
                            .foregroundStyle(.primary)
                            .tint(Color.primary)
                            .accessibilityIdentifier("home.privacy")
                    }
                    .font(.footnote.weight(.semibold))

                    Text("Offline • Decisive rules • Modern play")
                        .font(.caption)
                        .foregroundStyle(AppPalette.secondaryText)

                    Spacer(minLength: 6)
                }
                .padding(.horizontal, 20)
                .frame(maxWidth: 470, minHeight: geometry.size.height)
                .frame(maxWidth: .infinity)
            }
        }
        .overlay(
            Group {
                if let sheet = infoSheet {
                    InformationDialog(sheet: sheet) { infoSheet = nil }
                } else if isThemePickerPresented {
                    ThemePickerDialog(model: model) { isThemePickerPresented = false }
                }
            }
        )
        .sheet(isPresented: $isQuickPlayOpponentPickerPresented) {
            QuickPlayOpponentSheet(model: model) {
                isQuickPlayOpponentPickerPresented = false
            }
        }
        .alert(item: $pendingNewGame) { action in
            Alert(
                title: Text("Forfeit current game?"),
                message: Text("Are you sure you want to forfeit your current game? It will count as a loss in your stats."),
                primaryButton: .destructive(Text("Forfeit & start new game")) {
                    model.forfeitSavedGame()
                    if action == .quickPlay { model.startQuickPlay() }
                    else { model.showCustomGameSetup() }
                },
                secondaryButton: .cancel(Text("Keep current game"))
            )
        }
    }
}

private struct QuickPlayOpponentSheet: View {
    @ObservedObject var model: DrawlessChessModel
    let onDone: () -> Void

    private var selectedOpponent: DrawlessChessModel.BotLevel {
        model.opponentLevels.first(where: { $0.id == model.quickPlayOpponentId })
            ?? model.opponentLevels[2]
    }

    var body: some View {
        NavigationView {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 16) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(model.opponentLevels, id: \.id) { level in
                                opponentChoice(level)
                            }
                        }
                        .padding(.horizontal, 2)
                        .padding(.vertical, 4)
                    }
                    .accessibilityIdentifier("home.quickOpponentPicker")

                    opponentDetail(selectedOpponent)
                }
                .padding(20)
            }
            .background(AppBackground())
            .navigationTitle("Opponent")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onDone)
                        .accessibilityIdentifier("home.quickOpponent.done")
                }
            }
        }
        .navigationViewStyle(.stack)
        .accessibilityIdentifier("home.quickOpponentSheet")
    }

    private func opponentChoice(_ level: DrawlessChessModel.BotLevel) -> some View {
        let selected = level.id == selectedOpponent.id
        return Button {
            model.selectQuickPlayOpponent(level.id)
        } label: {
            VStack(spacing: 5) {
                OpponentPortrait(level: level, size: 74)
                    .accessibilityHidden(true)
                Text(verbatim: level.name)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Text(LocalizedStringKey(levelName(level)))
                    .font(.caption)
                    .foregroundStyle(selected ? AppPalette.gold : Color.secondary)
                    .lineLimit(1)
            }
            .frame(width: 100)
            .padding(.horizontal, 8)
            .padding(.vertical, 10)
            .background(
                selected ? AppPalette.gold.opacity(0.17) : AppPalette.panel,
                in: RoundedRectangle(cornerRadius: 18)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 18)
                    .stroke(
                        selected ? AppPalette.gold : Color.secondary.opacity(0.35),
                        lineWidth: selected ? 2 : 1
                    )
            }
            .shadow(color: selected ? AppPalette.gold.opacity(0.22) : .clear, radius: 6, y: 2)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(localizedFormat(
            "%1$s, %2$s opponent",
            level.name,
            levelName(level)
        ))
        .accessibilityValue(selected ? localized("Selected") : localized("Not selected"))
        .accessibilityIdentifier("home.quickOpponent.option.\(level.id)")
    }

    private func opponentDetail(_ level: DrawlessChessModel.BotLevel) -> some View {
        HStack(alignment: .center, spacing: 14) {
            OpponentPortrait(level: level, size: 92)
                .accessibilityIdentifier("home.quickOpponent.detailPortrait")

            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: level.name)
                    .font(.title2.weight(.bold))
                    .accessibilityIdentifier("home.quickOpponent.detailName")
                Text(localizedFormat("%1$s opponent", levelName(level)))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppPalette.gold)
                    .accessibilityIdentifier("home.quickOpponent.detailLevel")
                Text(LocalizedStringKey(level.epithet))
                    .font(.headline)
                    .accessibilityIdentifier("home.quickOpponent.detailEpithet")
                Text(LocalizedStringKey(level.personality))
                    .font(.subheadline)
                    .accessibilityIdentifier("home.quickOpponent.detailPersonality")
                Text(LocalizedStringKey(levelDescription(level)))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("home.quickOpponent.detailDescription")

                if level.id == "adaptive" {
                    Text(adaptiveStatus)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppPalette.gold)
                        .accessibilityIdentifier("home.quickOpponent.adaptiveStatus")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 18))
        .overlay {
            RoundedRectangle(cornerRadius: 18)
                .stroke(AppPalette.gold.opacity(0.55), lineWidth: 1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("home.quickOpponent.detail.\(level.id)")
    }

    private var adaptiveStatus: String {
        if model.statistics.adaptiveGamesPlayed < 10 {
            return localizedFormat(
                "ios.adaptive_status_provisional",
                model.statistics.adaptiveRating,
                model.statistics.adaptiveGamesPlayed
            )
        }
        return localizedFormat("ios.adaptive_status_matched", model.statistics.adaptiveRating)
    }

    private func levelName(_ level: DrawlessChessModel.BotLevel) -> String {
        localized(level.id == "adaptive" ? "Adaptive" : level.id.capitalized)
    }

    private func levelDescription(_ level: DrawlessChessModel.BotLevel) -> String {
        switch level.id {
        case "adaptive": return "Matches your current strength and evolves after each unassisted game."
        case "learner": return "A gentle introduction to Drawless Chess."
        case "casual": return "Relaxed play with room to experiment."
        case "challenger": return "A spirited opponent who notices tactics."
        case "club": return "Steady club-level opposition."
        case "expert": return "Sharp, accurate play for experienced players."
        case "master": return "A demanding strategic challenge."
        case "grandmaster": return "The strongest available opponent."
        default: return "Relaxed play with room to experiment."
        }
    }
}

private struct SetupView: View {
    @ObservedObject var model: DrawlessChessModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var advancedExpanded = false

    private struct ClockChoice: Identifiable {
        let id: String
        let label: String
        let minutes: Int
        let incrementSeconds: Int
    }

    private static let clockChoices = [
        ClockChoice(id: "untimed", label: "Untimed", minutes: 0, incrementSeconds: 0),
        ClockChoice(id: "3", label: "3 min", minutes: 3, incrementSeconds: 0),
        ClockChoice(id: "5", label: "5 min", minutes: 5, incrementSeconds: 0),
        ClockChoice(id: "10", label: "10 min", minutes: 10, incrementSeconds: 0),
        ClockChoice(id: "15+10", label: "15+10", minutes: 15, incrementSeconds: 10),
    ]

    private var clockColumns: [GridItem] {
        Array(
            repeating: GridItem(.flexible(), spacing: 6),
            count: dynamicTypeSize.isAccessibilitySize ? 2 : 5
        )
    }

    private var sideColumns: [GridItem] {
        Array(
            repeating: GridItem(.flexible(), spacing: 8),
            count: dynamicTypeSize.isAccessibilitySize ? 1 : 3
        )
    }

    private var ruleColumns: [GridItem] {
        Array(
            repeating: GridItem(.flexible(), spacing: 8),
            count: dynamicTypeSize.isAccessibilitySize ? 1 : 2
        )
    }

    private var selectedOpponent: DrawlessChessModel.BotLevel {
        model.opponentLevels.first(where: { $0.id == model.setup.botLevelId })
            ?? model.opponentLevels[2]
    }

    private var sideExplanation: String {
        if model.setup.humanSideId == "random" {
            return localized("White or Black will be chosen when the game starts.")
        }
        let side = localized(model.setup.humanSideId == "white" ? "White" : "Black")
        return localizedFormat("You’ll play %1$s.", side)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SetupSection(title: "Clock") {
                    LazyVGrid(columns: clockColumns, alignment: .leading, spacing: 6) {
                        ForEach(Self.clockChoices) { choice in
                            SetupChoiceButton(
                                title: choice.label,
                                selected: model.setup.clockMinutes == choice.minutes &&
                                    model.setup.incrementSeconds == choice.incrementSeconds,
                                identifier: "setup.clock.\(choice.id)",
                                horizontalPadding: 3
                            ) {
                                model.setup.clockMinutes = choice.minutes
                                model.setup.incrementSeconds = choice.incrementSeconds
                            }
                        }
                    }
                    .accessibilityIdentifier("setup.clock")
                }
                SetupSection(title: "Play as") {
                    LazyVGrid(columns: sideColumns, alignment: .leading, spacing: 8) {
                        ForEach(["random", "white", "black"], id: \.self) { side in
                            SetupChoiceButton(
                                title: side.capitalized,
                                selected: model.setup.humanSideId == side,
                                identifier: "setup.side.\(side)"
                            ) {
                                model.setup.humanSideId = side
                            }
                        }
                    }
                    .accessibilityIdentifier("setup.side")
                    Text(verbatim: sideExplanation)
                        .font(.footnote)
                        .foregroundStyle(AppPalette.secondaryText)
                        .accessibilityIdentifier("setup.sideExplanation")
                }
                SetupSection(title: "Opponent") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(model.opponentLevels, id: \.id) { level in
                                opponentChoice(level)
                            }
                        }
                        .padding(.horizontal, 2)
                        .padding(.vertical, 4)
                    }
                    .accessibilityIdentifier("setup.opponent")
                    opponentDetail(selectedOpponent)
                }
                SetupSection(title: "Advanced rules") {
                    Text("Quick Play uses the recommended Drawless rules. Change these only if you want a variant.")
                        .font(.footnote)
                        .foregroundStyle(AppPalette.secondaryText)
                        .accessibilityIdentifier("setup.advanced.description")

                    Button {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            advancedExpanded.toggle()
                        }
                    } label: {
                        Label(
                            advancedExpanded ? "Hide options" : "Show options",
                            systemImage: advancedExpanded ? "chevron.up" : "chevron.down"
                        )
                        .frame(minHeight: 44)
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppPalette.gold)
                    .buttonStyle(.plain)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
                    .accessibilityIdentifier("setup.advanced.toggle")

                    if advancedExpanded {
                        Text("Stalemate").font(.headline)
                        LazyVGrid(columns: ruleColumns, alignment: .leading, spacing: 8) {
                            SetupChoiceButton(
                                title: "Drawless",
                                selected: model.setup.presetId == "drawless",
                                identifier: "setup.rules.drawless"
                            ) { model.setup.presetId = "drawless" }
                            SetupChoiceButton(
                                title: "Escape",
                                selected: model.setup.presetId == "escape",
                                identifier: "setup.rules.escape"
                            ) { model.setup.presetId = "escape" }
                        }
                        .accessibilityIdentifier("setup.rules")

                        Text(LocalizedStringKey(model.setup.presetId == "drawless"
                             ? "Default: a player with no legal move loses."
                             : "Escape variant: a stalemated player wins instead."))
                            .font(.footnote)
                            .foregroundStyle(AppPalette.secondaryText)
                            .accessibilityIdentifier("setup.rules.description")

                        Divider()

                        Text("Impossible checkmate").font(.headline)
                        LazyVGrid(columns: ruleColumns, alignment: .leading, spacing: 8) {
                            SetupChoiceButton(
                                title: "Material wins",
                                selected: model.setup.deadPositionId == "material",
                                identifier: "setup.deadPosition.material"
                            ) { model.setup.deadPositionId = "material" }
                            SetupChoiceButton(
                                title: "Final capture",
                                selected: model.setup.deadPositionId == "final_capture",
                                identifier: "setup.deadPosition.finalCapture"
                            ) { model.setup.deadPositionId = "final_capture" }
                        }
                        .accessibilityIdentifier("setup.deadPosition")

                        Text(LocalizedStringKey(model.setup.deadPositionId == "material"
                             ? "When checkmate is impossible, the side with more material wins; equal material favors the last mover."
                             : "When a capture makes checkmate impossible, the capturing side wins."))
                            .font(.footnote)
                            .foregroundStyle(.primary)
                            .accessibilityIdentifier("setup.deadPosition.description")
                    }
                }
            }
            .padding(14)
            .padding(.bottom, 24)
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            ScreenHeader(title: "Custom game", back: { model.route = .home })
                .padding(.horizontal, 18)
                .background(AppPalette.background)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                Divider()
                Button {
                    model.startConfiguredGame()
                } label: {
                    Text("Start game")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("setup.start")
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
            }
            .background(AppPalette.background)
        }
    }

    private func opponentChoice(_ level: DrawlessChessModel.BotLevel) -> some View {
        let selected = level.id == selectedOpponent.id
        return Button {
            model.setup.botLevelId = level.id
        } label: {
            VStack(spacing: 4) {
                OpponentPortrait(level: level, size: 52)
                    .accessibilityHidden(true)
                Text(verbatim: level.name)
                    .font(.caption.weight(.semibold))
                    .multilineTextAlignment(.center)
                Text(verbatim: levelName(level))
                    .font(.caption)
                    .foregroundStyle(selected ? AppPalette.gold : AppPalette.secondaryText)
                    .multilineTextAlignment(.center)
            }
            .frame(width: dynamicTypeSize.isAccessibilitySize ? 144 : 72)
            .padding(.horizontal, 4)
            .padding(.vertical, 7)
            .background(
                selected ? AppPalette.gold.opacity(0.17) : AppPalette.panel,
                in: RoundedRectangle(cornerRadius: 16)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 16)
                    .stroke(
                        selected ? AppPalette.gold : Color.secondary.opacity(0.35),
                        lineWidth: selected ? 2 : 1
                    )
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(localizedFormat(
            "%1$s, %2$s opponent",
            level.name,
            levelName(level)
        ))
        .accessibilityValue(selected ? localized("Selected") : localized("Not selected"))
        .accessibilityIdentifier("setup.opponent.option.\(level.id)")
    }

    private func opponentDetail(_ level: DrawlessChessModel.BotLevel) -> some View {
        HStack(alignment: .center, spacing: 10) {
            OpponentPortrait(level: level, size: 64)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: level.name)
                    .font(.headline.weight(.bold))
                Text(localizedFormat("%1$s opponent", levelName(level)))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppPalette.gold)
                Text(LocalizedStringKey(level.epithet)).font(.subheadline.weight(.semibold))
                Text(LocalizedStringKey(level.personality))
                    .font(.caption)
                    .fixedSize(horizontal: false, vertical: true)
                Text(LocalizedStringKey(levelDescription(level)))
                    .font(.caption)
                    .foregroundStyle(AppPalette.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)

                if level.id == "adaptive" {
                    Text(adaptiveStatus)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppPalette.gold)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(AppPalette.gold.opacity(0.55)))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("setup.opponentSummary")
    }

    private var adaptiveStatus: String {
        if model.statistics.adaptiveGamesPlayed < 10 {
            return localizedFormat(
                "ios.adaptive_status_provisional",
                model.statistics.adaptiveRating,
                model.statistics.adaptiveGamesPlayed
            )
        }
        return localizedFormat("ios.adaptive_status_matched", model.statistics.adaptiveRating)
    }

    private func levelName(_ level: DrawlessChessModel.BotLevel) -> String {
        localized(level.id == "adaptive" ? "Adaptive" : level.id.capitalized)
    }

    private func levelDescription(_ level: DrawlessChessModel.BotLevel) -> String {
        switch level.id {
        case "adaptive": return "Matches your current strength and evolves after each unassisted game."
        case "learner": return "A gentle introduction to Drawless Chess."
        case "casual": return "Relaxed play with room to experiment."
        case "challenger": return "A spirited opponent who notices tactics."
        case "club": return "Steady club-level opposition."
        case "expert": return "Sharp, accurate play for experienced players."
        case "master": return "Demanding strategic play with little room for error."
        case "grandmaster": return "Our strongest fixed challenge."
        default: return "Relaxed play with room to experiment."
        }
    }
}

private struct GameView: View {
    @ObservedObject var model: DrawlessChessModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var showResignConfirmation = false
    @State private var settingsPanel: GameSettingsPanel?

    private var controlColumns: [GridItem] {
        if dynamicTypeSize.isAccessibilitySize {
            return [GridItem(.flexible())]
        }
        return [GridItem(.flexible()), GridItem(.flexible())]
    }

    private var shouldHidePostGameContentFromAccessibility: Bool {
        guard model.postGameReviewPending else { return false }
#if DEBUG
        // Latency/review XCTest probes remain observable in internal builds while Release stays core-only.
        return ProcessInfo.processInfo.environment["DRAWLESS_XCTEST_LATENCY"] != "1"
#else
        return true
#endif
    }

    var body: some View {
        GeometryReader { proxy in
            let landscape = proxy.size.width > proxy.size.height
            let sidePanelWidth = min(360, max(240, proxy.size.width * 0.34))
            let boardWidth = landscape
                ? min(
                    max(0, proxy.size.height - 64),
                    max(0, proxy.size.width - sidePanelWidth - 48)
                )
                : min(max(0, proxy.size.width - 28), 680)
            Group {
                if landscape {
                    HStack(alignment: .top, spacing: 16) {
                        landscapeBoardColumn(sideLength: boardWidth)
                            .frame(width: boardWidth)
                        ScrollView {
                            VStack(spacing: 10) {
                                opponentStrip
                                playerStrip
                                sidePanel
                            }
                        }
                            .frame(width: sidePanelWidth)
                    }
                    .padding(12)
                } else {
                    ScrollView {
                        VStack(spacing: 18) {
                            boardColumn(sideLength: boardWidth)
                                .frame(width: boardWidth)
                            sidePanel
                                .frame(maxWidth: 680)
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let game = model.game,
               game.phase == "COMPLETED",
               model.botMovePresentation == nil {
                GamePostGameBar(
                    model: model,
                    game: game,
                    endReason: localizedEndReason(game)
                )
#if DEBUG
                .onAppear { model.botMoveResultSurfaceDidAppear("postGame") }
#endif
            }
        }
        .accessibilityHidden(shouldHidePostGameContentFromAccessibility)
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 50_000_000)
                model.refreshGame()
            }
        }
        .onAppear { model.setGameVisible(true) }
        .onDisappear {
            model.dismissCompletionPresentation()
            model.setGameVisible(false)
        }
        .overlay {
            if let presentation = model.completionPresentation,
               model.botMovePresentation == nil {
                GameCompletionOverlay(
                    presentation: presentation,
                    themeId: model.preferences.boardThemeId,
                    opponent: model.opponentLevels.first(where: {
                        $0.id == presentation.opponentLevelId
                    }),
                    onFinished: {
                        model.completionPresentationDidFinish(id: presentation.id)
                    }
                )
#if DEBUG
                    .onAppear { model.botMoveResultSurfaceDidAppear("completionEffect") }
#endif
                    .id(presentation.id)
                    .allowsHitTesting(false)
            }
        }
        .overlay {
            if let game = model.game, !game.promotionChoices.isEmpty {
                PromotionPickerView(
                    choices: game.promotionChoices,
                    sideToMove: game.sideToMove,
                    themeId: model.preferences.boardThemeId,
                    onChoose: model.choosePromotion,
                    onCancel: model.cancelPromotion
                )
            }
        }
        .overlay {
            switch settingsPanel {
            case .theme:
                ThemePickerDialog(model: model) { settingsPanel = nil }
            case .options:
                ZStack {
                    AppBackground()
                    OptionsView(model: model) {
                        model.persistPreferences()
                        settingsPanel = nil
                    }
                }
                .transition(.opacity)
                .accessibilityIdentifier("game.options.panel")
            case nil:
                EmptyView()
            }
        }
        .overlay {
            if showResignConfirmation {
                ResignConfirmationDialog(
                    opponentName: model.opponentName,
                    onCancel: { showResignConfirmation = false },
                    onConfirm: {
                        showResignConfirmation = false
                        model.resign()
                    }
                )
            }
        }
#if DRAWLESS_IOS_GAME_REVIEW
        .overlay {
            if let game = model.game,
               model.postGameReviewPending,
               model.botMovePresentation == nil {
                if model.postGameReviewTapReady {
                    PostGameReviewTapGate(model: model, game: game)
                } else {
                    PostGamePresentationInputBlocker()
                }
            }
        }
#endif
    }

    private func boardColumn(sideLength: CGFloat) -> some View {
        VStack(spacing: 12) {
            gameHeader
            opponentStrip
            ChessBoardView(model: model, sideLength: sideLength)
            playerStrip
        }
    }

    private func landscapeBoardColumn(sideLength: CGFloat) -> some View {
        VStack(spacing: 8) {
            gameHeader
            ChessBoardView(model: model, sideLength: sideLength)
        }
    }

    private var gameHeader: some View {
        HStack {
            Button { model.exitGame() } label: {
                Label("Home", systemImage: "chevron.left")
                    .frame(minHeight: 44)
            }
            .accessibilityIdentifier("game.home")
            Spacer()
            Button { settingsPanel = .theme } label: {
                Image(systemName: "paintpalette.fill")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Theme")
            .accessibilityIdentifier("game.theme")
            Button { settingsPanel = .options } label: {
                Image(systemName: "gearshape.fill")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Options")
            .accessibilityIdentifier("game.options")
            Button { model.flipBoard() } label: {
                Label("Flip", systemImage: "arrow.triangle.2.circlepath")
                    .frame(minHeight: 44)
            }
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(AppPalette.gold)
    }

    private var opponentStrip: some View {
        PlayerStrip(
            title: model.opponentName,
            subtitle: localized("ios.offline_opponent"),
            remainingMillis: opponentRemainingMillis,
            clockRunning: (model.game?.initialMillis ?? 0) > 0 &&
                model.game?.sideToMove != model.game?.humanSide &&
                model.game?.phase != "PAUSED" && model.game?.phase != "COMPLETED",
            snapshotDate: model.gameSnapshotDate,
            active: model.game?.sideToMove != model.game?.humanSide,
            portraitName: DrawlessChessModel.botLevels.first(where: { $0.id == model.setup.botLevelId })?.portraitName,
            accessibilityIdentifier: "game.opponent"
        )
    }

    private var playerStrip: some View {
        PlayerStrip(
            title: localized("ios.you"),
            subtitle: localized(model.game?.humanSide.capitalized ?? "White"),
            remainingMillis: playerRemainingMillis,
            clockRunning: (model.game?.initialMillis ?? 0) > 0 &&
                model.game?.sideToMove == model.game?.humanSide &&
                model.game?.phase != "PAUSED" && model.game?.phase != "COMPLETED",
            snapshotDate: model.gameSnapshotDate,
            active: model.game?.sideToMove == model.game?.humanSide,
            portraitName: nil,
            accessibilityIdentifier: "game.player"
        )
    }

    private var sidePanel: some View {
        VStack(spacing: 14) {
            VStack(spacing: 5) {
                Text(localizedGameStatus)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AppPalette.highContrastText)
                if let game = model.game, game.phase == "COMPLETED" {
                    Text(localizedEndReason(game))
                        .font(.subheadline)
                        .foregroundStyle(AppPalette.gold)
                } else {
                    Text(LocalizedStringKey(model.rulesName))
                        .font(.footnote)
                        .foregroundStyle(AppPalette.secondaryText)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(14)
            .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("game.status")
#if DEBUG
            .accessibilityValue(model.latencyProbeAccessibilityValue)
#endif

            if model.game?.phase != "COMPLETED" {
                LazyVGrid(columns: controlColumns, spacing: 10) {
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
                .accessibilityIdentifier("game.controls")
            }

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
                        .foregroundStyle(AppPalette.secondaryText)
                    Button(localizedFormat("ios.retry_opponent", model.opponentName)) { model.retryOpponent() }
                        .buttonStyle(CompactButtonStyle())
                        .accessibilityIdentifier("game.retryOpponent")
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier("game.engineError")
            }

            ScrollView {
                Text(model.game?.moveHistory.isEmpty == false
                     ? model.game!.moveHistory
                     : localized("ios.moves_placeholder"))
                    .font(.body.monospaced())
                    .foregroundStyle(
                        model.game?.moveHistory.isEmpty == false
                            ? Color.primary
                            : AppPalette.secondaryText
                    )
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
            }
            .frame(minHeight: 82, maxHeight: 190)
            .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(historyAccessibilityLabel)
            .accessibilityIdentifier("game.history")

        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.sidePanel")
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

private enum GameSettingsPanel: String, Identifiable {
    case theme
    case options

    var id: String { rawValue }
}

private struct GamePostGameBar: View {
    @ObservedObject var model: DrawlessChessModel
    let game: SharedGameView
    let endReason: String

    private var won: Bool { game.winner == game.humanSide }

    private var resultSummary: String {
        won
            ? localizedFormat("You defeated %1$s.", model.opponentName)
            : localizedFormat("%1$s won.", model.opponentName)
    }

    private var penaltySummary: String? {
        var penalties: [String] = []
        if game.hintPenalty > 0 {
            penalties.append(localizedFormat("ios.penalty_hints", Int(game.hintPenalty)))
        }
        if game.undoPenalty > 0 {
            penalties.append(localizedFormat("ios.penalty_undos", Int(game.undoPenalty)))
        }
        if game.pausePenalty > 0 {
            penalties.append(localizedFormat("ios.penalty_pauses", Int(game.pausePenalty)))
        }
        if game.threatPenalty > 0 {
            penalties.append(localizedFormat("ios.penalty_threat", Int(game.threatPenalty)))
        }
        return penalties.isEmpty ? nil : penalties.joined(separator: " · ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(localized(won ? "Victory" : "Defeat"))
                    .font(.title3.weight(.bold))
                Spacer()
                Text(localizedFormat("ios.score", Int(game.score), Int(game.scoreMaximumPoints)))
                    .font(.headline.monospacedDigit())
                    .accessibilityIdentifier("game.postGame.score")
            }

            Text(resultSummary)
                .font(.subheadline.weight(.semibold))
            Text(endReason)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)

            HStack(alignment: .firstTextBaseline, spacing: 10) {
                if let average = model.statistics.averageScore {
                    Text(localizedFormat(
                        "Career average game score: %1$s",
                        String(format: "%.1f", average)
                    ))
                }
                if let penaltySummary {
                    Text(penaltySummary)
                }
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
            .lineLimit(2)

            HStack(spacing: 10) {
                Button {
                    model.startQuickPlay()
                } label: {
                    Text(localized("Quick Play"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("game.postGame.quickPlay")

                Button {
                    model.startRematch()
                } label: {
                    Text(localized("Rematch"))
                }
                .buttonStyle(SecondaryButtonStyle())
                .accessibilityIdentifier("game.postGame.rematch")
            }

            Button {
                model.exitGame()
            } label: {
                Text(localized("Home"))
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 5)
            }
            .buttonStyle(.plain)
            .foregroundStyle(AppPalette.gold)
            .accessibilityIdentifier("game.postGame.home")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 6)
        .background {
            ZStack {
                AppPalette.panel
                (won ? Color.green : Color.red).opacity(0.13)
            }
        }
        .overlay(alignment: .top) {
            Rectangle()
                .fill((won ? Color.green : Color.red).opacity(0.62))
                .frame(height: 1)
        }
        .shadow(color: .black.opacity(0.34), radius: 12, y: -4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.postGame")
    }
}

#if DRAWLESS_IOS_GAME_REVIEW
private struct PostGamePresentationInputBlocker: View {
    var body: some View {
        Color.black.opacity(0.001)
            .ignoresSafeArea()
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0))
            .accessibilityHidden(true)
            .accessibilityIdentifier("game.postGame.presentationBlocker")
    }
}

private struct PostGameReviewTapGate: View {
    @ObservedObject var model: DrawlessChessModel
    let game: SharedGameView
    @AccessibilityFocusState private var accessibilityFocused: Bool

    private var won: Bool { game.winner == game.humanSide }

    var body: some View {
        Button {
            model.enterPostGameReview(expectedGameId: game.gameId)
        } label: {
            ZStack(alignment: .bottom) {
                // iOS 15 can exclude nearly transparent views from the UIKit hit-test plane even
                // though SwiftUI and Accessibility report the full-screen Button. Keep a visually
                // negligible but real plane so the legacy iPad receives the one review contact.
                Color.black.opacity(0.02)

                Text(localized("ios.tap_anywhere_to_review"))
                    .font(.headline.weight(.bold))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 16)
                    .frame(maxWidth: 420)
                    .background(AppPalette.panel.opacity(0.97), in: RoundedRectangle(cornerRadius: 24))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(AppPalette.gold.opacity(0.62), lineWidth: 1)
                    )
                    .shadow(color: .black.opacity(0.35), radius: 10, y: 5)
                    .padding(24)
                    .accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .accessibilityLabel(localized("ios.tap_anywhere_to_review"))
        .accessibilityValue(
            localizedFormat(
                "ios.game_result_accessibility",
                localized(won ? "Victory" : "Defeat"),
                Int(game.score),
                Int(game.scoreMaximumPoints)
            )
        )
        .accessibilityIdentifier("game.postGame.reviewGate")
        .accessibilityFocused($accessibilityFocused)
        .onAppear {
            accessibilityFocused = true
            UIAccessibility.post(
                notification: .announcement,
                argument: localized("ios.tap_anywhere_to_review")
            )
        }
    }
}

private struct GameReviewView: View {
    @ObservedObject var model: DrawlessChessModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var selectedPly: Int32?
    @State private var boardFlipped = false
    @State private var showOpponentMoves = false
    @State private var reviewPollingGeneration = 0

    private var moves: [SharedReviewMove] { model.game?.reviewMoves ?? [] }
    private var playerMoves: [SharedReviewMove] { moves.filter(\.playerDecision) }
    private var visibleMoves: [SharedReviewMove] {
        showOpponentMoves ? moves : playerMoves
    }
    private var selectedMove: SharedReviewMove? {
        visibleMoves.first(where: { $0.ply == selectedPly }) ?? visibleMoves.first
    }

    var body: some View {
        VStack(spacing: 0) {
            reviewHeader
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 12) {
                    if let move = selectedMove {
                        ReviewChessBoardView(
                            move: move,
                            themeId: model.preferences.boardThemeId,
                            lastMoveArgb: model.game?.lastMoveArgb ?? 0x88D4AF37,
                            checkArgb: model.game?.checkArgb ?? 0xB3B22B38,
                            showCoordinates: model.preferences.coordinatesEnabled,
                            playerSide: model.game?.humanSide ?? "WHITE",
                            flipped: boardFlipped
                        )
                        .frame(maxWidth: 720)
                        .frame(maxWidth: .infinity)
                    }

                    if model.game?.reviewSummary != nil, let move = selectedMove {
                        reviewDetail(move)
                        reviewNavigator(move)
                    }

                    reviewStatus

                    if model.game?.reviewSummary != nil {
                        reviewSummary
                    }

                    if model.game?.reviewSummary == nil, let move = selectedMove {
                        reviewDetail(move)
                        reviewNavigator(move)
                    }

                    reviewMoveList
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 24)
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
            }
        }
        .task(id: reviewPollingGeneration) {
            // The post-game acknowledgement projects the current runtime attempt; this immediate refresh also
            // closes the narrow race where it completed between that projection and navigation.
            model.refreshGame()
            if selectedPly == nil {
                selectedPly = playerMoves.first?.ply
            }
            while !Task.isCancelled {
                if model.game?.reviewSummary != nil || model.game?.reviewError != nil { break }
                try? await Task.sleep(nanoseconds: 100_000_000)
                model.refreshGame()
                if selectedPly == nil {
                    selectedPly = playerMoves.first?.ply
                }
            }
        }
    }

    private var reviewHeader: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(spacing: 2) {
                    reviewHeaderActions
                    reviewHeaderTitle
                        .padding(.bottom, 8)
                }
            } else {
                ZStack {
                    reviewHeaderActions
                    reviewHeaderTitle
                }
            }
        }
        .padding(.horizontal, 12)
        .background(AppPalette.panel)
        .overlay(alignment: .bottom) {
            Rectangle().fill(AppPalette.gold.opacity(0.35)).frame(height: 1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("review.header")
#if DEBUG
        .accessibilityValue(model.latencyProbeAccessibilityValue)
#endif
    }

    private var reviewHeaderActions: some View {
        HStack {
            Button { model.exitGame() } label: {
                Text(localized("Save & exit"))
                    .frame(minHeight: 48)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("review.saveExit")
            Spacer()
            Button("Flip") { boardFlipped.toggle() }
                .frame(minWidth: 44, minHeight: 48)
                .buttonStyle(.plain)
                .accessibilityIdentifier("review.flip")
        }
        .foregroundStyle(.primary)
    }

    private var reviewHeaderTitle: some View {
        VStack(spacing: 0) {
            Text(localized("ios.game_review"))
                .font(.headline.weight(.bold))
            Text(localized("ios.review_beta"))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AppPalette.secondaryText)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var reviewStatus: some View {
        if let game = model.game, game.reviewInProgress {
            VStack(alignment: .leading, spacing: 8) {
                if model.reviewPreparationPhase == "PREPARING" {
                    Text(localized("Preparing game review"))
                        .font(.headline)
                    ProgressView()
                    Text(localized("Matching the analysis completed while you played."))
                        .font(.caption)
                        .foregroundStyle(AppPalette.secondaryText)
                    Color.clear
                        .frame(width: 1, height: 1)
                        .accessibilityIdentifier("review.preparing")
                } else {
                    Text(localized("ios.review_analyzing")).font(.headline)
                    if game.reviewProgress > 0 {
                        ProgressView(
                            value: Double(game.reviewProgress),
                            total: Double(max(1, game.reviewTotal))
                        )
                        Text(localizedFormat(
                            "ios.review_progress",
                            Int(game.reviewProgress),
                            Int(game.reviewTotal)
                        ))
                            .font(.caption)
                            .foregroundStyle(AppPalette.secondaryText)
                    } else {
                        // Zero is not evidence that the engine restarted at move one. Until the
                        // runtime publishes its preserved-coverage count, use an indeterminate
                        // analysis state rather than the misleading "0 of N" presentation.
                        ProgressView()
                        Text(localized("Checking the positions that still need analysis."))
                            .font(.caption)
                            .foregroundStyle(AppPalette.secondaryText)
                    }
                    Color.clear
                        .frame(width: 1, height: 1)
                        .accessibilityIdentifier("review.analyzing")
                }
            }
            .padding(14)
            .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
            .accessibilityIdentifier("review.status")
            .accessibilityValue(reviewStatusAccessibilityValue(game))
        } else if let error = model.game?.reviewError {
            VStack(alignment: .leading, spacing: 8) {
                Text(localized("ios.review_failed")).font(.headline)
                Text(error).font(.footnote).foregroundStyle(AppPalette.secondaryText)
                Button(localized("ios.review_retry")) {
                    model.startReview()
                    reviewPollingGeneration &+= 1
                }
                    .buttonStyle(CompactButtonStyle())
            }
            .padding(14)
            .background(Color.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
            .accessibilityIdentifier("review.error")
        } else if model.game?.reviewSummary != nil {
            VStack(alignment: .leading, spacing: 10) {
                Text(localized("ios.review_complete"))
                    .font(.headline)
                Text(localizedFormat(
                    "ios.review_complete_body",
                    playerMoves.count,
                    playerMoves.filter { $0.quality != nil }.count
                ))
                    .font(.subheadline)
                    .foregroundStyle(AppPalette.secondaryText)
                Button {
                    model.startRematch()
                } label: {
                    Text(localized("Rematch"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(SecondaryButtonStyle())
                .accessibilityIdentifier("review.rematch")
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
            .accessibilityIdentifier("review.complete")
        }
    }

    private func reviewStatusAccessibilityValue(_ game: SharedGameView) -> String {
#if DEBUG
        return "phase=\(model.reviewPreparationPhase);reviewed=\(game.reviewProgress);total=\(game.reviewTotal)"
#else
        if model.reviewPreparationPhase == "PREPARING" {
            return localized("Preparing game review")
        }
        if game.reviewProgress > 0 {
            return localizedFormat(
                "ios.review_progress",
                Int(game.reviewProgress),
                Int(game.reviewTotal)
            )
        }
        return localized("Checking the positions that still need analysis.")
#endif
    }

    private var reviewSummary: some View {
        let game = model.game
        return VStack(alignment: .leading, spacing: 10) {
            Text(localized("ios.review_summary_title")).font(.headline)
            HStack {
                Text(localizedFormat(
                    "You (%1$s)",
                    localized(model.game?.humanSide == "BLACK" ? "Black" : "White")
                ))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppPalette.highContrastText)
                    .accessibilityIdentifier("review.summary.player")
                Spacer()
                Text(localizedFormat("Moves graded: %1$d", playerMoves.filter { $0.quality != nil }.count))
                    .font(.caption)
                    .foregroundStyle(AppPalette.highContrastText)
                    .accessibilityIdentifier("review.summary.gradedCount")
            }
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 6) {
                ReviewCountChip(quality: "BEST", count: Int(game?.reviewBestCount ?? 0))
                ReviewCountChip(quality: "GOOD", count: Int(game?.reviewGoodCount ?? 0))
                ReviewCountChip(quality: "INACCURACY", count: Int(game?.reviewInaccuracyCount ?? 0))
                ReviewCountChip(quality: "MISTAKE", count: Int(game?.reviewMistakeCount ?? 0))
                ReviewCountChip(quality: "BLUNDER", count: Int(game?.reviewBlunderCount ?? 0))
            }
        }
        .padding(14)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("review.summary")
    }

    private func reviewDetail(_ move: SharedReviewMove) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(reviewMoveTitle(move))
                .font(.title3.weight(.semibold))
                .accessibilityIdentifier("review.detail.title")

            if !move.playerDecision {
                Text(localized("Opponent move"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppPalette.secondaryText)
                Text(localized("Shown for context. Only your moves are graded."))
                    .foregroundStyle(AppPalette.secondaryText)
                    .accessibilityIdentifier("review.detail.context")
            } else if let quality = move.quality {
                ReviewGradeBadge(quality: quality)
                Text(localized(reviewGradeExplanationKey(quality)))

                if quality != "BEST", let bestMoveSan = move.bestMoveSan,
                   bestMoveSan != move.playedSan {
                    Text(localizedFormat("ios.review_better_move", bestMoveSan))
                        .font(.headline)
                        .accessibilityIdentifier("review.detail.betterMove")
                }
                if !move.suggestedLineSan.isEmpty {
                    Text(localizedFormat(
                        "ios.review_suggested_line",
                        move.suggestedLineSan.joined(separator: " ")
                    ))
                        .foregroundStyle(AppPalette.secondaryText)
                        .accessibilityIdentifier("review.detail.suggestedLine")
                }
                if let evaluation = move.playedEvaluationText {
                    Text(localizedFormat(
                        "ios.review_evaluation",
                        localized(model.game?.humanSide == "BLACK" ? "Black" : "White"),
                        evaluation
                    ))
                        .font(.footnote)
                        .foregroundStyle(AppPalette.secondaryText)
                        .accessibilityIdentifier("review.detail.evaluation")
                }
            } else {
                Text(localized("ios.review_waiting"))
                    .foregroundStyle(AppPalette.secondaryText)
                    .accessibilityIdentifier("review.detail.waiting")
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("review.detail")
    }

    private func reviewNavigator(_ move: SharedReviewMove) -> some View {
        let index = visibleMoves.firstIndex(where: { $0.ply == move.ply }) ?? 0
        let previousMove = index > 0 ? visibleMoves[index - 1] : nil
        let nextMove = index + 1 < visibleMoves.count ? visibleMoves[index + 1] : nil
        let previousIssue = visibleMoves.prefix(index).last(where: {
            $0.playerDecision && reviewIsIssue($0.quality)
        })
        let nextIssue = visibleMoves.dropFirst(index + 1).first(where: {
            $0.playerDecision && reviewIsIssue($0.quality)
        })
        return VStack(spacing: 8) {
            Text(localizedFormat(
                showOpponentMoves ? "ios.review_move_position" : "ios.review_your_move_position",
                index + 1,
                visibleMoves.count
            ))
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("review.movePosition")
            HStack {
                reviewNavigationButton(
                    symbol: "|<<",
                    label: "Previous mistake",
                    identifier: "review.previousIssue",
                    target: previousIssue
                )
                Spacer()
                reviewNavigationButton(
                    symbol: "<<",
                    label: "ios.review_previous",
                    identifier: "review.previous",
                    target: previousMove
                )
                Spacer()
                reviewNavigationButton(
                    symbol: ">>",
                    label: "ios.review_next",
                    identifier: "review.next",
                    target: nextMove
                )
                Spacer()
                reviewNavigationButton(
                    symbol: ">>|",
                    label: "Next mistake",
                    identifier: "review.nextIssue",
                    target: nextIssue
                )
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("review.navigator")
    }

    private func reviewNavigationButton(
        symbol: String,
        label: String,
        identifier: String,
        target: SharedReviewMove?
    ) -> some View {
        Button {
            selectedPly = target?.ply
        } label: {
            Text(verbatim: symbol)
                .font(.caption.weight(.black))
                .frame(width: 48, height: 48)
                .contentShape(Circle())
                .overlay(Circle().stroke(target == nil ? Color.secondary.opacity(0.4) : AppPalette.gold))
        }
        .buttonStyle(.plain)
        .foregroundStyle(target == nil ? Color.secondary.opacity(0.55) : AppPalette.gold)
        .disabled(target == nil)
        .accessibilityLabel(localized(label))
        .accessibilityIdentifier(identifier)
    }

    private var reviewMoveList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(localized(showOpponentMoves ? "ios.review_moves" : "ios.review_your_moves"))
                .font(.headline)

            Toggle(
                localized("ios.review_show_opponent_moves"),
                isOn: $showOpponentMoves
            )
            .frame(minHeight: 48)
            .accessibilityIdentifier("review.showOpponentMoves")
            .onChange(of: showOpponentMoves) { show in
                updateSelectedPlyForOpponentMoveVisibility(show)
            }

            Divider()

            if visibleMoves.isEmpty,
               model.game?.reviewInProgress == true,
               (model.game?.reviewTotal ?? 0) > 0 {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(localized(
                        model.reviewPreparationPhase == "PREPARING"
                            ? "Preparing game review"
                            : "ios.review_waiting"
                    ))
                        .foregroundStyle(AppPalette.secondaryText)
                }
                .frame(maxWidth: .infinity, minHeight: 96)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("review.movesPreparing")
            } else if visibleMoves.isEmpty {
                Text(localized("ios.review_no_player_moves"))
                    .foregroundStyle(AppPalette.secondaryText)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, minHeight: 96)
                    .accessibilityIdentifier("review.noPlayerMoves")
            } else if showOpponentMoves {
                HStack {
                    Color.clear
                        .frame(width: 34, height: 1)
                        .accessibilityHidden(true)
                    Text(localized("White"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(localized("Black"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .font(.caption)
                .foregroundStyle(AppPalette.secondaryText)

                ForEach(reviewRows) { row in
                    HStack(spacing: 4) {
                        Text(verbatim: "\(row.moveNumber).")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(AppPalette.secondaryText)
                            .frame(width: 34, alignment: .leading)
                        reviewMoveCell(row.white)
                        reviewMoveCell(row.black)
                    }
                }
            } else {
                ForEach(playerMoves, id: \.ply) { move in
                    reviewMoveCell(move, includeMoveNumber: true)
                }
            }
        }
        .padding(14)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("review.moves")
    }

    private func updateSelectedPlyForOpponentMoveVisibility(_ show: Bool) {
        if !show,
           let selectedPly,
           let selected = moves.first(where: { $0.ply == selectedPly }),
           !selected.playerDecision {
            self.selectedPly = playerMoves.first(where: { $0.ply > selected.ply })?.ply
                ?? playerMoves.last(where: { $0.ply < selected.ply })?.ply
        } else if show, selectedPly == nil {
            selectedPly = moves.first?.ply
        }
    }

    private var reviewRows: [ReviewMoveRow] {
        var rows: [ReviewMoveRow] = []
        for move in moves {
            if rows.last?.moveNumber != move.moveNumber {
                rows.append(ReviewMoveRow(moveNumber: move.moveNumber))
            }
            if move.mover == "WHITE" { rows[rows.count - 1].white = move }
            else { rows[rows.count - 1].black = move }
        }
        return rows
    }

    @ViewBuilder
    private func reviewMoveCell(
        _ move: SharedReviewMove?,
        includeMoveNumber: Bool = false
    ) -> some View {
        if let move {
            let selected = selectedMove?.ply == move.ply
            Button { selectedPly = move.ply } label: {
                HStack(spacing: 5) {
                    if move.playerDecision, let quality = move.quality {
                        Text(reviewGradeSymbol(quality))
                            .font(.caption2.weight(.black))
                            .foregroundStyle(reviewGradeColor(quality))
                            .frame(width: 26, height: 26)
                            .background(reviewGradeColor(quality).opacity(0.15), in: Circle())
                            .accessibilityHidden(true)
                    }
                    Text(verbatim: includeMoveNumber ? reviewMoveTitle(move) : move.playedSan)
                        .font(.subheadline)
                        .fontWeight(selected ? .bold : .regular)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 6)
                .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                .background(
                    selected ? AppPalette.gold.opacity(0.16) : Color.clear,
                    in: RoundedRectangle(cornerRadius: 10)
                )
            }
            .buttonStyle(.plain)
            .accessibilityLabel(reviewMoveAccessibilityLabel(move, selected: selected))
            .accessibilityIdentifier("review.move.\(move.ply)")
        } else {
            Color.clear.frame(maxWidth: .infinity, minHeight: 48)
                .accessibilityHidden(true)
        }
    }

    private func reviewMoveAccessibilityLabel(_ move: SharedReviewMove, selected: Bool) -> String {
        let grade = move.playerDecision
            ? localized(move.quality.map { reviewGradeKey($0) } ?? "ios.review_waiting")
            : localized("Opponent move")
        return "\(reviewMoveTitle(move)), \(grade), \(localized(selected ? "Selected" : "Not selected"))"
    }

    private func reviewMoveTitle(_ move: SharedReviewMove) -> String {
        return move.mover == "WHITE"
            ? "\(move.moveNumber). \(move.playedSan)"
            : "\(move.moveNumber)… \(move.playedSan)"
    }
}

private struct ReviewMoveRow: Identifiable {
    let moveNumber: Int32
    var white: SharedReviewMove?
    var black: SharedReviewMove?

    init(moveNumber: Int32, white: SharedReviewMove? = nil, black: SharedReviewMove? = nil) {
        self.moveNumber = moveNumber
        self.white = white
        self.black = black
    }

    var id: Int32 { moveNumber }
}

private struct ReviewChessBoardView: View {
    let move: SharedReviewMove
    let themeId: String
    let lastMoveArgb: Int64
    let checkArgb: Int64
    let showCoordinates: Bool
    let playerSide: String
    let flipped: Bool
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 8)

    private var cells: [SharedBoardCell] {
        flipped ? Array(move.cells.reversed()) : move.cells
    }

    var body: some View {
        ZStack {
            LazyVGrid(columns: columns, spacing: 0) {
                ForEach(cells.indices, id: \.self) { index in
                    let cell = cells[index]
                    GeometryReader { proxy in
                        ZStack {
                            BoardSquareSurface(
                                themeId: themeId,
                                isLight: !cell.darkSquare,
                                square: cell.square
                            )
                            if cell.lastMove { Color(argb: lastMoveArgb) }
                            if cell.inCheck {
                                Circle().fill(Color(argb: checkArgb))
                                    .padding(proxy.size.width * 0.05)
                            }
                            ChessPieceView(pieceCode: cell.pieceCode, themeId: themeId)
                                .padding(proxy.size.width * 0.015)
                            if showCoordinates {
                                coordinateLabels(cell: cell, displayIndex: index)
                            }
                        }
                    }
                    .aspectRatio(1, contentMode: .fit)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(localizedBoardCellLabel(cell))
                    .accessibilityIdentifier("review.square.\(cell.square)")
                }
            }

            if let from = move.betterMoveFromSquare, let to = move.betterMoveToSquare {
                ReviewBoardMoveArrowOverlay(cells: cells, fromSquare: from, toSquare: to)
                    .allowsHitTesting(false)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(AppPalette.gold, lineWidth: 2))
        .shadow(color: .black.opacity(0.45), radius: 12, y: 6)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(localized("ios.chess_board"))
        .accessibilityValue(
            localizedFormat("ios.board_orientation", localized(reviewBoardBottomSide))
        )
        .accessibilityIdentifier("review.board")
    }

    private var reviewBoardBottomSide: String {
        let playerIsBlack = playerSide.uppercased() == "BLACK"
        let blackAtBottom = flipped ? !playerIsBlack : playerIsBlack
        return blackAtBottom ? "Black" : "White"
    }

    @ViewBuilder
    private func coordinateLabels(cell: SharedBoardCell, displayIndex: Int) -> some View {
        let labelColor = cell.darkSquare ? Color.white : Color.black
        let backingColor = cell.darkSquare ? Color.black : Color.white
        Canvas { context, size in
            if displayIndex % 8 == 0, let rank = cell.square.last {
                context.fill(
                    Path(roundedRect: CGRect(x: 0, y: 0, width: 13, height: 13), cornerRadius: 3),
                    with: .color(backingColor)
                )
                context.draw(
                    Text(String(rank))
                        .font(.system(size: 9, weight: .bold, design: .rounded))
                        .foregroundColor(labelColor),
                    at: CGPoint(x: 2, y: 2),
                    anchor: .topLeading
                )
            }
            if displayIndex / 8 == 7, let file = cell.square.first {
                context.fill(
                    Path(
                        roundedRect: CGRect(
                            x: size.width - 13,
                            y: size.height - 13,
                            width: 13,
                            height: 13
                        ),
                        cornerRadius: 3
                    ),
                    with: .color(backingColor)
                )
                context.draw(
                    Text(String(file))
                        .font(.system(size: 9, weight: .bold, design: .rounded))
                        .foregroundColor(labelColor),
                    at: CGPoint(x: size.width - 2, y: size.height - 2),
                    anchor: .bottomTrailing
                )
            }
        }
        .accessibilityHidden(true)
    }
}

private struct ReviewBoardMoveArrowOverlay: View {
    let cells: [SharedBoardCell]
    let fromSquare: String
    let toSquare: String

    var body: some View {
        Canvas { context, size in
            guard
                let fromIndex = cells.firstIndex(where: { $0.square == fromSquare }),
                let toIndex = cells.firstIndex(where: { $0.square == toSquare })
            else { return }

            let squareLength = min(size.width, size.height) / 8
            func center(_ index: Int) -> CGPoint {
                CGPoint(
                    x: (CGFloat(index % 8) + 0.5) * squareLength,
                    y: (CGFloat(index / 8) + 0.5) * squareLength
                )
            }

            let start = center(fromIndex)
            let end = center(toIndex)
            let angle = atan2(end.y - start.y, end.x - start.x)
            let headLength = squareLength * 0.28
            let headAngle = CGFloat.pi / 6
            let shaftWidth = max(3, squareLength * 0.11)

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
                with: .color(AppPalette.gold),
                style: StrokeStyle(lineWidth: shaftWidth, lineCap: .round, lineJoin: .round)
            )
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(localizedFormat(
            "Better move arrow from %1$s to %2$s",
            fromSquare,
            toSquare
        ))
        .accessibilityIdentifier("review.betterMoveArrow")
    }
}

private struct ReviewGradeBadge: View {
    let quality: String?

    var body: some View {
        Text(verbatim: localizedFormat(
            "%1$s %2$s",
            reviewGradeSymbol(quality),
            localized(reviewGradeKey(quality))
        ))
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
        Text(verbatim: localizedFormat("%1$s %2$d", reviewGradeSymbol(quality), count))
        .fontWeight(.bold)
        .font(.caption)
        .foregroundStyle(reviewGradeColor(quality))
        .padding(.horizontal, 9).padding(.vertical, 6)
        .background(reviewGradeColor(quality).opacity(0.12), in: Capsule())
        .accessibilityLabel(localizedFormat("%1$s: %2$d", localized(reviewGradeKey(quality)), count))
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
    case "BEST": return adaptiveColor(light: 0x493B00, dark: 0xFFE082)
    case "GOOD": return adaptiveColor(light: 0x8F1742, dark: 0xFF8AB3)
    case "INACCURACY": return adaptiveColor(light: 0x1F6331, dark: 0x7DDB8A)
    case "MISTAKE": return adaptiveColor(light: 0x8A3C00, dark: 0xFFB36B)
    case "BLUNDER": return adaptiveColor(light: 0x9B1C1C, dark: 0xFF8A80)
    default: return AppPalette.secondaryText
    }
}

private func reviewGradeSymbol(_ quality: String?) -> String {
    switch quality {
    case "BEST": return "★"
    case "GOOD": return "✓"
    case "INACCURACY": return "?!"
    case "MISTAKE": return "?"
    case "BLUNDER": return "??"
    default: return "—"
    }
}

private func reviewIsIssue(_ quality: String?) -> Bool {
    quality == "INACCURACY" || quality == "MISTAKE" || quality == "BLUNDER"
}
#endif

private struct PromotionPickerView: View {
    let choices: [String]
    let sideToMove: String
    let themeId: String
    let onChoose: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Button(action: onCancel) {
                    Color.black.opacity(0.58)
                        .ignoresSafeArea()
                }
                .buttonStyle(.plain)
                .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 20) {
                    Text("Promote pawn")
                        .font(.title2.weight(.semibold))

                    HStack(spacing: 12) {
                        ForEach(choices, id: \.self) { choice in
                            Button {
                                onChoose(choice)
                            } label: {
                                VStack(spacing: 6) {
                                    ChessPieceView(
                                        pieceCode: promotionPieceCode(choice),
                                        themeId: themeId
                                    )
                                    .frame(width: 48, height: 48)
                                    Text(LocalizedStringKey(choice.capitalized))
                                        .font(.caption.weight(.semibold))
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(
                                    AppPalette.background.opacity(0.72),
                                    in: RoundedRectangle(cornerRadius: 12)
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(LocalizedStringKey(choice.capitalized))
                            .accessibilityIdentifier("promotion.choice.\(choice.lowercased())")
                        }
                    }

                    HStack {
                        Spacer()
                        Button("Cancel", action: onCancel)
                            .font(.headline)
                            .foregroundStyle(AppPalette.gold)
                            .accessibilityIdentifier("promotion.cancel")
                    }
                }
                .padding(22)
                .frame(maxWidth: min(520, max(280, geometry.size.width - 32)))
                .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 22))
                .overlay(RoundedRectangle(cornerRadius: 22).stroke(AppPalette.gold.opacity(0.55)))
                .shadow(color: .black.opacity(0.42), radius: 24, y: 10)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("promotion.dialog")
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
    }

    private func promotionPieceCode(_ choice: String) -> String {
        let sidePrefix = sideToMove.uppercased() == "BLACK" ? "b" : "w"
        let pieceSuffix: String
        switch choice.uppercased() {
        case "ROOK": pieceSuffix = "R"
        case "BISHOP": pieceSuffix = "B"
        case "KNIGHT": pieceSuffix = "N"
        default: pieceSuffix = "Q"
        }
        return sidePrefix + pieceSuffix
    }
}

private struct ChessBoardView: View {
    @ObservedObject var model: DrawlessChessModel
    let sideLength: CGFloat
    @State private var boardDrag: BoardDrag?
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 8)

    var body: some View {
        let themeId = model.preferences.boardThemeId
        let highlights = BoardHighlightPalette.resolve(themeId)
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
                                    Color(argb: highlights.lastMove)
                                }
                                if cell.threatened { Color.red.opacity(0.25) }
                                if cell.selected {
                                    Color(argb: highlights.selected)
                                    Rectangle().stroke(AppPalette.gold, lineWidth: 3)
                                }
                                if cell.legalTarget && model.botMovePresentation == nil {
                                    if cell.captureTarget {
                                        Circle().stroke(
                                            Color(argb: highlights.legalCapture),
                                            lineWidth: 4
                                        )
                                            .padding(proxy.size.width * 0.08)
                                    } else {
                                        Circle().fill(Color(argb: highlights.legalMove))
                                            .frame(width: proxy.size.width * 0.24)
                                    }
                                }
                                if cell.inCheck {
                                    Circle().fill(Color(argb: highlights.check))
                                        .padding(proxy.size.width * 0.05)
                                }
                                if boardDrag?.hidesPiece(at: cell.displayIndex) != true,
                                   model.botMovePresentation?.hidesDestination(cell.square) != true {
                                    ChessPieceView(pieceCode: cell.pieceCode, themeId: themeId)
                                        .padding(proxy.size.width * 0.015)
                                }
                                if model.preferences.coordinatesEnabled {
                                    coordinateLabels(cell: cell)
                                }
                            }
                        }
                        .aspectRatio(1, contentMode: .fit)
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(localizedBoardCellLabel(cell))
                    .accessibilityIdentifier("square.\(cell.square)")
                }
            }
            if let drag = boardDrag, drag.moved, drag.canMovePiece {
                ChessPieceView(pieceCode: drag.pieceCode, themeId: themeId)
                    .frame(width: sideLength / 8, height: sideLength / 8)
                    .position(drag.location)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            if let presentation = model.botMovePresentation {
                BotMoveAnimationOverlay(
                    presentation: presentation,
                    cells: cells,
                    sideLength: sideLength,
                    themeId: themeId,
                    onFrame: model.botMoveAnimationDidRender
                )
                .allowsHitTesting(false)
            }
        }
        .frame(width: sideLength, height: sideLength)
        .contentShape(Rectangle())
        .highPriorityGesture(boardDragGesture(cells: cells), including: .all)
        .overlay {
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
        .accessibilityValue(boardAccessibilityValue(themeId: themeId, highlights: highlights))
        .accessibilityIdentifier("game.board")
    }

    private func boardAccessibilityValue(
        themeId: String,
        highlights: BoardHighlightPalette
    ) -> String {
#if DEBUG
        var value =
            "gameId=\(model.game?.gameId ?? "");themeId=\(themeId);selectedArgb=\(highlights.selected)"
        value += ";botMoveAnimation=\(model.botMoveAnimationAccessibilityValue)"
        value += ";\(model.botMoveResultOrderingAccessibilityValue)"
        return value
#else
        let topLeftSquare = model.game?.cells.first(where: { $0.displayIndex == 0 })?.square
        let bottomSide = topLeftSquare == "h1" ? "Black" : "White"
        return localizedFormat("ios.board_orientation", localized(bottomSide))
#endif
    }

    private func boardDragGesture(cells: [SharedBoardCell]) -> some Gesture {
        // Claim the touch as soon as it begins on the board so the containing
        // portrait ScrollView cannot turn a piece drag into page scrolling.
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { value in
                guard model.botMovePresentation == nil else {
                    boardDrag = nil
                    return
                }
                let drag = boardDrag ?? makeBoardDrag(at: value.startLocation, cells: cells)
                guard var drag else { return }

                drag.location = clampedToBoard(value.location)
                let distance = hypot(value.translation.width, value.translation.height)
                var shouldSelectSource = false
                if distance >= 6 {
                    drag.moved = true
                    if drag.canMovePiece && !drag.startedSelected && !drag.selectionCommitted {
                        drag.selectionCommitted = true
                        shouldSelectSource = true
                    }
                }
                boardDrag = drag
                if shouldSelectSource {
                    model.tap(drag.startIndex)
                }
            }
            .onEnded { value in
                guard var drag = boardDrag ?? makeBoardDrag(at: value.startLocation, cells: cells) else {
                    boardDrag = nil
                    return
                }
                drag.moved = drag.moved || hypot(value.translation.width, value.translation.height) >= 6

                if drag.moved {
                    guard drag.canMovePiece else {
                        boardDrag = nil
                        return
                    }
                    if !drag.startedSelected && !drag.selectionCommitted {
                        model.tap(drag.startIndex)
                    }
                    if let destination = cell(at: value.location, cells: cells)?.displayIndex,
                       destination != drag.startIndex {
                        model.tap(destination)
                    }
                } else {
                    model.tap(drag.startIndex)
                }
                boardDrag = nil
            }
    }

    private func makeBoardDrag(at location: CGPoint, cells: [SharedBoardCell]) -> BoardDrag? {
        guard let cell = cell(at: location, cells: cells) else { return nil }
        let humanPiecePrefix = model.game?.humanSide.uppercased() == "BLACK" ? "b" : "w"
        let canMovePiece = model.botMovePresentation == nil &&
            cell.pieceCode.hasPrefix(humanPiecePrefix) &&
            model.game?.phase == "HUMAN_TURN"
        return BoardDrag(
            startIndex: cell.displayIndex,
            pieceCode: cell.pieceCode,
            startedSelected: cell.selected,
            canMovePiece: canMovePiece,
            location: clampedToBoard(location)
        )
    }

    private func cell(at location: CGPoint, cells: [SharedBoardCell]) -> SharedBoardCell? {
        guard location.x >= 0, location.y >= 0, location.x < sideLength, location.y < sideLength else {
            return nil
        }
        let squareLength = sideLength / 8
        let column = min(7, Int(location.x / squareLength))
        let row = min(7, Int(location.y / squareLength))
        let displayIndex = Int32(row * 8 + column)
        return cells.first { $0.displayIndex == displayIndex }
    }

    private func clampedToBoard(_ location: CGPoint) -> CGPoint {
        CGPoint(
            x: min(max(0, location.x), sideLength),
            y: min(max(0, location.y), sideLength)
        )
    }

    private struct BoardDrag {
        let startIndex: Int32
        let pieceCode: String
        let startedSelected: Bool
        let canMovePiece: Bool
        var location: CGPoint
        var moved = false
        var selectionCommitted = false

        func hidesPiece(at displayIndex: Int32) -> Bool {
            moved && canMovePiece && startIndex == displayIndex
        }
    }

    @ViewBuilder
    private func coordinateLabels(cell: SharedBoardCell) -> some View {
        let index = Int(cell.displayIndex)
        let labelColor = cell.darkSquare ? Color.white : Color.black
        let backingColor = cell.darkSquare ? Color.black : Color.white
        Canvas { context, size in
            if index % 8 == 0, let rank = cell.square.last {
                context.fill(
                    Path(roundedRect: CGRect(x: 0, y: 0, width: 13, height: 13), cornerRadius: 3),
                    with: .color(backingColor)
                )
                context.draw(
                    Text(String(rank))
                        .font(.system(size: 9, weight: .bold, design: .rounded))
                        .foregroundColor(labelColor),
                    at: CGPoint(x: 2, y: 2),
                    anchor: .topLeading
                )
            }
            if index / 8 == 7, let file = cell.square.first {
                context.fill(
                    Path(
                        roundedRect: CGRect(
                            x: size.width - 13,
                            y: size.height - 13,
                            width: 13,
                            height: 13
                        ),
                        cornerRadius: 3
                    ),
                    with: .color(backingColor)
                )
                context.draw(
                    Text(String(file))
                        .font(.system(size: 9, weight: .bold, design: .rounded))
                        .foregroundColor(labelColor),
                    at: CGPoint(x: size.width - 2, y: size.height - 2),
                    anchor: .bottomTrailing
                )
            }
        }
        .accessibilityHidden(true)
    }
}

private struct BotMoveAnimationOverlay: View {
    let presentation: DrawlessChessModel.BotMovePresentation
    let cells: [SharedBoardCell]
    let sideLength: CGFloat
    let themeId: String
    let onFrame: (String, Double) -> Void

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: false)) { _ in
            let rawProgress = min(
                1,
                max(
                    0,
                    (ProcessInfo.processInfo.systemUptime - presentation.startedAtUptime) /
                        presentation.duration
                )
            )
            let easedProgress = AndroidFastOutSlowIn.value(at: rawProgress)
            ZStack {
                Color.clear
                ForEach(presentation.pieces) { motion in
                    if let from = center(of: motion.fromSquare),
                       let to = center(of: motion.toSquare) {
                        ChessPieceView(pieceCode: motion.pieceCode, themeId: themeId)
                            .frame(width: sideLength / 8, height: sideLength / 8)
                            .position(
                                x: from.x + (to.x - from.x) * easedProgress,
                                y: from.y + (to.y - from.y) * easedProgress
                            )
#if DEBUG
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel(localizedFormat("Moving %1$s", motion.pieceCode))
                            .accessibilityValue(
                                String(
                                    format: "from=%@;to=%@;raw=%.4f;eased=%.4f",
                                    motion.fromSquare,
                                    motion.toSquare,
                                    rawProgress,
                                    easedProgress
                                )
                            )
                            .accessibilityIdentifier("game.botMovePiece.\(motion.id)")
#else
                            .accessibilityHidden(true)
#endif
                    }
                }
            }
            .frame(width: sideLength, height: sideLength)
#if DEBUG
            .accessibilityElement(children: .contain)
            .accessibilityLabel(localized("Opponent move animation"))
            .accessibilityValue(
                String(
                    format: "ply=%d;raw=%.4f;eased=%.4f;durationMs=500",
                    presentation.ply,
                    rawProgress,
                    easedProgress
                )
            )
            .accessibilityIdentifier("game.botMoveAnimation")
#else
            .accessibilityHidden(true)
#endif
            .onAppear {
                onFrame(presentation.id, rawProgress)
                announceOpponentMove()
            }
            .onChange(of: rawProgress) { progress in
                onFrame(presentation.id, progress)
            }
        }
    }

    private func center(of square: String) -> CGPoint? {
        guard let displayIndex = cells.first(where: { $0.square == square })?.displayIndex else {
            return nil
        }
        let index = Int(displayIndex)
        let squareLength = sideLength / 8
        return CGPoint(
            x: (CGFloat(index % 8) + 0.5) * squareLength,
            y: (CGFloat(index / 8) + 0.5) * squareLength
        )
    }

    private func announceOpponentMove() {
        guard UIAccessibility.isVoiceOverRunning,
              let primaryMotion = presentation.pieces.first else { return }
        UIAccessibility.post(
            notification: .announcement,
            argument: localizedFormat(
                "Opponent moved from %1$s to %2$s",
                primaryMotion.fromSquare,
                primaryMotion.toSquare
            )
        )
    }
}

/** Compose's FastOutSlowInEasing: cubic Bezier (0.4, 0, 0.2, 1). */
private enum AndroidFastOutSlowIn {
    static func value(at progress: Double) -> CGFloat {
        let x = min(1, max(0, progress))
        var parameter = x
        for _ in 0..<8 {
            let error = cubic(parameter, control1: 0.4, control2: 0.2) - x
            let slope = derivative(parameter, control1: 0.4, control2: 0.2)
            guard abs(slope) > 0.000_001 else { break }
            parameter = min(1, max(0, parameter - error / slope))
        }
        return CGFloat(cubic(parameter, control1: 0, control2: 1))
    }

    private static func cubic(_ t: Double, control1: Double, control2: Double) -> Double {
        let inverse = 1 - t
        return 3 * inverse * inverse * t * control1 +
            3 * inverse * t * t * control2 +
            t * t * t
    }

    private static func derivative(_ t: Double, control1: Double, control2: Double) -> Double {
        let inverse = 1 - t
        return 3 * inverse * inverse * control1 +
            6 * inverse * t * (control2 - control1) +
            3 * t * t * (1 - control2)
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
    let onBack: () -> Void

    private var versionLabel: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "Unknown"
        let build = Int(info?["CFBundleVersion"] as? String ?? "") ?? 0
        return localizedFormat("Version %1$s (%2$d)", version, build)
    }

    private func persistedBinding(
        _ keyPath: WritableKeyPath<DrawlessChessModel.Preferences, Bool>
    ) -> Binding<Bool> {
        Binding(
            get: { model.preferences[keyPath: keyPath] },
            set: { value in
                model.preferences[keyPath: keyPath] = value
                model.persistPreferences()
            }
        )
    }

    private var volumeBinding: Binding<Double> {
        Binding(
            get: { Double(model.preferences.soundVolumePercent) },
            set: { value in
                model.preferences.soundVolumePercent = Int(value.rounded())
                model.persistPreferences()
            }
        )
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                OptionsSectionCard(
                    title: "Audio",
                    description: "Control the physical board and game-result sounds.",
                    identifier: "options.section.audio"
                ) {
                    PreferenceToggleRow(
                        title: "Sound effects",
                        description: "Play move, capture, victory, and defeat sounds.",
                        isOn: persistedBinding(\.soundEnabled),
                        identifier: "options.sound"
                    )
                    Divider()
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Sound volume").font(.body.weight(.semibold))
                                Text("Adjust game sounds independently of your device volume.")
                                    .font(.footnote)
                                    .foregroundStyle(AppPalette.secondaryText)
                            }
                            Spacer()
                            Text(verbatim: "\(model.preferences.soundVolumePercent)%")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(AppPalette.gold)
                        }
                        Slider(
                            value: volumeBinding,
                            in: 0...100,
                            step: 10,
                            onEditingChanged: { editing in
                                if !editing { model.previewSound() }
                            }
                        )
                        .disabled(!model.preferences.soundEnabled)
                        .accessibilityIdentifier("options.soundVolume")
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }

                OptionsSectionCard(
                    title: "Board",
                    description: "Choose the guidance shown around and on the board.",
                    identifier: "options.section.board"
                ) {
                    PreferenceToggleRow(
                        title: "Board coordinates",
                        description: "Show rank and file labels along the board edges.",
                        isOn: persistedBinding(\.coordinatesEnabled),
                        identifier: "options.coordinates"
                    )
                    Divider()
                    PreferenceToggleRow(
                        title: "Threat indication",
                        description: "Highlight your pieces that are attacked by the opponent. This is assistance: a win scores 95 instead of 100 points and does not qualify for rated play. It applies to new games; a resumed game keeps the setting it started with.",
                        isOn: persistedBinding(\.threatIndicationEnabled),
                        identifier: "options.threats"
                    )
                }

                OptionsSectionCard(
                    title: "Game feedback",
                    description: "Adjust result presentation without changing chess rules.",
                    identifier: "options.section.feedback"
                ) {
                    PreferenceToggleRow(
                        title: "Haptic feedback",
                        description: "Use subtle vibrations for selections, moves, captures, checks, and game results. Your device’s touch-feedback setting still applies.",
                        isOn: persistedBinding(\.hapticsEnabled),
                        identifier: "options.haptics"
                    )
                    Divider()
                    PreferenceToggleRow(
                        title: "Win/loss celebration effects",
                        description: "Show the full-screen victory or defeat animation and play its matching fireworks or glass cue. The Sound effects setting remains the master audio control. The result and score are always shown.",
                        isOn: persistedBinding(\.celebrationsEnabled),
                        identifier: "options.celebrations"
                    )
                }

                Text(localized("ios.options_local_storage_notice"))
                    .font(.footnote)
                    .foregroundStyle(AppPalette.secondaryText)
                    .accessibilityIdentifier("options.localNotice")
                Text(versionLabel)
                    .font(.caption)
                    .foregroundStyle(AppPalette.secondaryText)
                    .accessibilityIdentifier("options.version")
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .top) {
            ScreenHeader(title: "Options", back: onBack)
                .padding(.horizontal, 18)
                .background(AppPalette.background.opacity(0.96))
        }
    }
}

private struct StatisticsView: View {
    @ObservedObject var model: DrawlessChessModel

    private var completedGamesText: String {
        if model.statistics.games == 1 { return localized("1 completed game") }
        return localizedFormat("%1$d completed games", model.statistics.games)
    }

    private var playerMetrics: [StatsMetricDescriptor] {
        [
            StatsMetricDescriptor(
                id: "statistics.record",
                label: localized("Record"),
                value: "\(model.statistics.wins)–\(model.statistics.losses)",
                firstValue: "\(model.statistics.wins)",
                firstIdentifier: "statistics.wins",
                secondValue: "\(model.statistics.losses)",
                secondIdentifier: "statistics.losses"
            ),
            StatsMetricDescriptor(
                id: "statistics.winRate",
                label: localized("Win rate"),
                value: model.statistics.winPercentage.map { String(format: "%.1f%%", $0) } ?? "—"
            ),
            StatsMetricDescriptor(
                id: "statistics.averageScore",
                label: localized("Average game score"),
                value: model.statistics.averageScore.map { String(format: "%.1f", $0) } ?? "—"
            ),
        ]
    }

    private var momentumMetrics: [StatsMetricDescriptor] {
        [
            StatsMetricDescriptor(
                id: "statistics.currentStreak",
                label: localized("Current streak"),
                value: "\(model.statistics.currentWinStreak)"
            ),
            StatsMetricDescriptor(
                id: "statistics.bestStreak",
                label: localized("Best streak"),
                value: "\(model.statistics.bestWinStreak)"
            ),
            StatsMetricDescriptor(
                id: "statistics.unassistedWins",
                label: localized("Unassisted wins"),
                value: "\(model.statistics.unassistedWins)"
            ),
        ]
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Player")
                        .font(.title2.weight(.bold))
                    Text(completedGamesText)
                        .font(.headline)
                        .accessibilityIdentifier("statistics.games")
                    AdaptiveStatsGrid(metrics: playerMetrics)
                }
                .padding(18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AppPalette.gold.opacity(0.22), in: RoundedRectangle(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(AppPalette.gold.opacity(0.3)))
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("statistics.player")

                VStack(alignment: .leading, spacing: 12) {
                    Text("Momentum").font(.headline)
                    AdaptiveStatsGrid(metrics: momentumMetrics)
                }
                .padding(18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 20))
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("statistics.momentum")

                VStack(alignment: .leading, spacing: 8) {
                    Text("About game score").font(.headline)
                    Text("A clean win scores 100. Successful hints and undos deduct 10 each; timed pauses and threat indication deduct 5. Losses score 0.")
                        .font(.body)
                    Text("Game score is a motivational measure of clean wins—not a chess rating. Your average includes every completed game, including zero-point losses. Future online Elo will remain separate.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .padding(18)
                .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 20))
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("statistics.aboutScore")

                if !model.statistics.opponents.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("By opponent").font(.headline)
                        ForEach(model.statistics.opponents) { opponent in
                            VStack(alignment: .leading, spacing: 3) {
                                let level = model.opponentLevels.first(where: { $0.id == opponent.id })
                                let levelLabel = level.map {
                                    localized($0.id == "adaptive" ? "Adaptive" : $0.id.capitalized)
                                } ?? localized("Opponent")
                                Text(localizedFormat("%1$s · %2$s", opponent.name, levelLabel))
                                    .font(.subheadline.weight(.semibold))
                                Text(verbatim: "\(localizedFormat("ios.opponent_record", opponent.games, opponent.wins, opponent.losses)) · \(localizedFormat("latest strength: %1$d estimated Elo", opponent.elo))")
                                    .font(.footnote)
                                Text(localizedFormat(
                                    "ios.opponent_summary",
                                    opponent.winPercentage,
                                    opponent.averageScore
                                ))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            }
                            if opponent.id != model.statistics.opponents.last?.id { Divider() }
                        }
                    }
                    .padding(18)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 20))
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("statistics.opponents")
                }

                Text("Stats are derived from completed games.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(18)
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .top) {
            ScreenHeader(title: "Player stats", back: { model.route = .home })
                .padding(.horizontal, 18)
                .background(AppPalette.background.opacity(0.96))
        }
    }
}

private struct BoardHighlightPalette {
    let selected: Int64
    let legalMove: Int64
    let legalCapture: Int64
    let lastMove: Int64
    let check: Int64

    static func resolve(_ themeId: String) -> BoardHighlightPalette {
        switch themeId {
        case "desert_sandstone":
            BoardHighlightPalette(
                selected: 0xCC2E8B74,
                legalMove: 0x992E8B74,
                legalCapture: 0x99C34A33,
                lastMove: 0x88D9A441,
                check: 0xB3C43B2E
            )
        case "glacier_slate":
            BoardHighlightPalette(
                selected: 0xCC2878D0,
                legalMove: 0x992878D0,
                legalCapture: 0x99D85D4A,
                lastMove: 0x88F2B84B,
                check: 0xB3D73B45
            )
        case "verdigris_copper", "malachite_court":
            BoardHighlightPalette(
                selected: 0xCCD29A3A,
                legalMove: 0x99BD8A36,
                legalCapture: 0x99C34A33,
                lastMove: 0x88D8A24A,
                check: 0xB3C43B3A
            )
        case "amethyst_geode":
            BoardHighlightPalette(
                selected: 0xCCF1C75B,
                legalMove: 0x99C9A94E,
                legalCapture: 0x99E25A4F,
                lastMove: 0x88E9B949,
                check: 0xB3D9465F
            )
        default:
            BoardHighlightPalette(
                selected: 0xCCD4AF37,
                legalMove: 0x99C9A227,
                legalCapture: 0x99B03A48,
                lastMove: 0x88D4AF37,
                check: 0xB3B22B38
            )
        }
    }
}

private struct OptionsSectionCard<Content: View>: View {
    let title: String
    let description: String
    let identifier: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text(LocalizedStringKey(title)).font(.headline)
                Text(LocalizedStringKey(description))
                    .font(.subheadline)
                    .foregroundStyle(.primary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 12))
            VStack(spacing: 0) { content }
                .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 18))
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.secondary.opacity(0.18)))
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }
}

private struct PreferenceToggleRow: View {
    let title: String
    let description: String
    @Binding var isOn: Bool
    let identifier: String

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 3) {
                Text(LocalizedStringKey(title))
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                Text(LocalizedStringKey(description))
                    .font(.footnote)
                    .foregroundStyle(AppPalette.secondaryText)
            }
            .background(AppPalette.panel)
        }
        .toggleStyle(.switch)
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .accessibilityIdentifier(identifier)
    }
}

private struct StatsMetricDescriptor: Identifiable {
    let id: String
    let label: String
    let value: String
    var firstValue: String? = nil
    var firstIdentifier: String? = nil
    var secondValue: String? = nil
    var secondIdentifier: String? = nil
}

private struct AdaptiveStatsGrid: View {
    let metrics: [StatsMetricDescriptor]
    @State private var availableWidth: CGFloat = 0

    private var compact: Bool { availableWidth == 0 || availableWidth < 420 }

    var body: some View {
        Group {
            if compact {
                VStack(spacing: 10) {
                    ForEach(metrics) { metric in
                        StatsMetricView(metric: metric, compact: true)
                    }
                }
            } else {
                HStack(alignment: .top, spacing: 8) {
                    ForEach(metrics) { metric in
                        StatsMetricView(metric: metric, compact: false)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .background(
            GeometryReader { proxy in
                Color.clear.preference(key: StatsGridWidthPreferenceKey.self, value: proxy.size.width)
            }
        )
        .onPreferenceChange(StatsGridWidthPreferenceKey.self) { availableWidth = $0 }
    }
}

private struct StatsMetricView: View {
    let metric: StatsMetricDescriptor
    let compact: Bool

    @ViewBuilder
    private var valueView: some View {
        if let firstValue = metric.firstValue,
           let firstIdentifier = metric.firstIdentifier,
           let secondValue = metric.secondValue,
           let secondIdentifier = metric.secondIdentifier {
            HStack(spacing: 0) {
                Text(firstValue).accessibilityIdentifier(firstIdentifier)
                Text(verbatim: "–").accessibilityHidden(true)
                Text(secondValue).accessibilityIdentifier(secondIdentifier)
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(metric.id)
        } else {
            Text(metric.value).accessibilityIdentifier(metric.id)
        }
    }

    var body: some View {
        Group {
            if compact {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(metric.label)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Spacer()
                    valueView
                        .font(.title3.weight(.bold))
                }
            } else {
                VStack(spacing: 4) {
                    valueView
                        .font(.title2.weight(.bold))
                    Text(metric.label)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
        }
        .accessibilityLabel(localizedFormat("%1$s: %2$s", metric.label, metric.value))
    }
}

private struct StatsGridWidthPreferenceKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

private struct ThemePickerDialog: View {
    @ObservedObject var model: DrawlessChessModel
    let onDismiss: () -> Void

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black.opacity(0.58)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onDismiss)

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(alignment: .firstTextBaseline) {
                            Text("Choose a theme")
                                .font(.title2.weight(.semibold))
                            Spacer()
                            Button(action: onDismiss) {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.title2)
                                    .foregroundStyle(.secondary)
                            }
                            .accessibilityLabel("Close")
                            .accessibilityIdentifier("theme.dismiss")
                        }

                        Text("Board and pieces update immediately. Your choice is saved.")
                            .font(.body)
                            .foregroundStyle(.secondary)

                        ForEach(DrawlessChessModel.boardThemes, id: \.id) { theme in
                            ThemeOptionRow(
                                themeId: theme.id,
                                name: theme.name,
                                description: themeDescription(theme.id),
                                selected: model.preferences.boardThemeId == theme.id
                            ) {
                                model.selectBoardTheme(theme.id)
                                onDismiss()
                            }
                        }
                    }
                    .padding(20)
                }
                .frame(maxWidth: min(540, proxy.size.width - 32))
                .frame(maxHeight: min(700, proxy.size.height - 40))
                .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 24))
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.secondary.opacity(0.22)))
                .shadow(color: .black.opacity(0.35), radius: 24, y: 10)
                .accessibilityIdentifier("theme.picker")
            }
        }
        .transition(.opacity)
    }

    private func themeDescription(_ id: String) -> String {
        switch id {
        case "desert_sandstone": localized("Sun-baked strata and warm grain")
        case "glacier_slate": localized("Riven stone and mica light")
        case "verdigris_copper": localized("Aged copper and ivory limestone")
        case "amethyst_geode": localized("Violet crystal and gold glints")
        default: localized("Carrara white and veined verde")
        }
    }
}

private struct ThemeOptionRow: View {
    let themeId: String
    let name: String
    let description: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ThemeMaterialPreview(themeId: themeId)
                VStack(alignment: .leading, spacing: 3) {
                    Text(LocalizedStringKey(name))
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(description)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.title2)
                    .foregroundStyle(selected ? AppPalette.gold : Color.secondary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 74)
            .background(
                selected ? AppPalette.gold.opacity(0.20) : Color.secondary.opacity(0.07),
                in: RoundedRectangle(cornerRadius: 16)
            )
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(localizedFormat("%1$s, %2$s", localized(name), description))
        .accessibilityAddTraits(selected ? .isSelected : AccessibilityTraits())
        .accessibilityIdentifier("theme.option.\(themeId)")
    }
}

private struct ThemeMaterialPreview: View {
    let themeId: String

    private var accent: Color {
        switch themeId {
        case "desert_sandstone": Color(red: 0.18, green: 0.55, blue: 0.45)
        case "glacier_slate": Color(red: 0.16, green: 0.47, blue: 0.82)
        case "verdigris_copper": Color(red: 0.82, green: 0.60, blue: 0.23)
        case "amethyst_geode": Color(red: 0.95, green: 0.78, blue: 0.36)
        default: Color(red: 0.83, green: 0.69, blue: 0.22)
        }
    }

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    previewSquare(light: true, file: 0, rank: 1)
                    previewSquare(light: false, file: 1, rank: 1)
                }
                HStack(spacing: 0) {
                    previewSquare(light: false, file: 0, rank: 0)
                    previewSquare(light: true, file: 1, rank: 0)
                }
            }
            Circle().fill(accent).frame(width: 13, height: 13)
        }
        .frame(width: 54, height: 54)
        .clipShape(RoundedRectangle(cornerRadius: 9))
        .overlay(RoundedRectangle(cornerRadius: 9).stroke(Color.secondary.opacity(0.7)))
        .accessibilityHidden(true)
    }

    private func previewSquare(light: Bool, file: Int, rank: Int) -> some View {
        BoardSquareSurface(
            themeId: themeId,
            isLight: light,
            file: file,
            rank: rank
        )
        .frame(width: 27, height: 27)
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
    let remainingMillis: Int64
    let clockRunning: Bool
    let snapshotDate: Date
    let active: Bool
    let portraitName: String?
    let accessibilityIdentifier: String

    var body: some View {
        HStack {
            if let portraitName {
                bundledPortraitImage(named: portraitName)
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
                Text(title)
                    .font(.headline)
                    .foregroundStyle(AppPalette.highContrastText)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AppPalette.highContrastText)
            }
            Spacer()
            if clockRunning, remainingMillis >= 0 {
                TimelineView(.periodic(from: snapshotDate, by: 1)) { context in
                    Text(clockText(at: context.date))
                        .font(.title3.monospacedDigit().weight(.semibold))
                        .foregroundStyle(.primary)
                }
            } else {
                Text(clockText(at: snapshotDate))
                    .font(.title3.monospacedDigit().weight(.semibold))
                    .foregroundStyle(.primary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(accessibilityIdentifier)
    }

    private func clockText(at date: Date) -> String {
        guard remainingMillis >= 0 else { return "∞" }
        let elapsedMillis = clockRunning
            ? Int64(max(0, date.timeIntervalSince(snapshotDate)) * 1_000)
            : 0
        let totalSeconds = max(0, remainingMillis - elapsedMillis) / 1_000
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

private struct SetupChoiceButton: View {
    let title: String
    let selected: Bool
    let identifier: String
    var horizontalPadding: CGFloat = 10
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(LocalizedStringKey(title))
                .font(.body.weight(.semibold))
                .foregroundStyle(selected ? AppPalette.onGold : Color.primary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, horizontalPadding)
                .padding(.vertical, 9)
                .frame(minHeight: 44)
                .background(
                    selected ? AppPalette.gold : Color.clear,
                    in: RoundedRectangle(cornerRadius: 10)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(
                            selected ? AppPalette.gold : Color.secondary.opacity(0.45),
                            lineWidth: selected ? 2 : 1
                        )
                }
        }
        .buttonStyle(.plain)
        .accessibilityValue(selected ? localized("Selected") : localized("Not selected"))
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(identifier)
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
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct ScreenHeader: View {
    let title: String
    let back: () -> Void

    var body: some View {
        HStack {
            Button(action: back) {
                Image(systemName: "chevron.left")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
                .buttonStyle(.plain)
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
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            } icon: {
                Image(systemName: icon)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
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
            colors: [AppPalette.background, AppPalette.backgroundGradientEnd],
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
        bundledPortraitImage(named: level.portraitName)
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

private func bundledPortraitImage(named name: String) -> Image {
    if let image = UIImage(named: "\(name).png") ?? UIImage(named: name) {
        return Image(uiImage: image)
    }
    return Image(systemName: "person.crop.circle.fill")
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
        case .license: (localized("View source"), URL(string: "https://drawlesschess.com/open-source/")!)
        case .privacy: (localized("View policy"), URL(string: "https://drawlesschess.com/privacy/")!)
        }
    }

    var dismissTitle: String {
        self == .rules ? localized("Got it") : localized("Close")
    }
}

private struct InformationDialog: View {
    let sheet: InfoSheet
    let onDismiss: () -> Void

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black.opacity(0.58)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onDismiss)

                VStack(alignment: .leading, spacing: 18) {
                    Text(verbatim: sheet.title)
                        .font(.title2.weight(.semibold))

                    ScrollView(showsIndicators: false) {
                        Text(verbatim: sheet.body)
                            .font(.body)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("dialog.\(sheet.rawValue).body")
                    }
                    .frame(maxHeight: min(450, proxy.size.height * 0.62))

                    HStack(spacing: 18) {
                        Spacer()
                        if let link = sheet.link {
                            Link(link.0, destination: link.1)
                                .font(.headline)
                                .foregroundStyle(AppPalette.gold)
                                .accessibilityIdentifier("dialog.\(sheet.rawValue).link")
                                .accessibilityValue(link.1.absoluteString)
                        }
                        Button(sheet.dismissTitle, action: onDismiss)
                            .font(.headline)
                            .buttonStyle(DialogActionButtonStyle(filled: sheet == .rules))
                            .fixedSize(horizontal: true, vertical: false)
                            .accessibilityIdentifier("dialog.\(sheet.rawValue).dismiss")
                    }
                }
                .padding(22)
                .frame(maxWidth: min(540, proxy.size.width - 32), alignment: .leading)
                .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 24))
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.secondary.opacity(0.22)))
                .shadow(color: .black.opacity(0.35), radius: 24, y: 10)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("dialog.\(sheet.rawValue)")
            }
        }
        .transition(.opacity)
    }
}

private struct ResignConfirmationDialog: View {
    let opponentName: String
    let onCancel: () -> Void
    let onConfirm: () -> Void

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black.opacity(0.58)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onCancel)

                VStack(alignment: .leading, spacing: 14) {
                    Text("Resign this game?")
                        .font(.title2.weight(.semibold))

                    Text(localizedFormat("%1$s will be awarded the win.", opponentName))
                        .font(.body)
                        .foregroundStyle(.secondary)

                    HStack(spacing: 12) {
                        Spacer()
                        Button("Cancel", action: onCancel)
                            .font(.headline)
                            .buttonStyle(DialogActionButtonStyle(filled: false))
                            .accessibilityIdentifier("dialog.resign.cancel")

                        Button("Resign game", role: .destructive, action: onConfirm)
                            .font(.headline)
                            .buttonStyle(.borderedProminent)
                            .tint(.red)
                            .accessibilityIdentifier("dialog.resign.confirm")
                    }
                }
                .padding(22)
                .frame(maxWidth: min(480, proxy.size.width - 32), alignment: .leading)
                .background(AppPalette.panel, in: RoundedRectangle(cornerRadius: 24))
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.secondary.opacity(0.22)))
                .shadow(color: .black.opacity(0.35), radius: 24, y: 10)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("dialog.resign")
            }
        }
        .transition(.opacity)
    }
}

private struct GameCompletionOverlay: View {
    let presentation: DrawlessChessModel.CompletionPresentation
    let themeId: String
    let opponent: DrawlessChessModel.BotLevel?
    let onFinished: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var duration: TimeInterval {
        presentation.won
            ? GameCompletionTimeline.victoryDuration
            : GameCompletionTimeline.defeatDuration
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0)) { _ in
            let nowUptime = ProcessInfo.processInfo.systemUptime
            let elapsed = nowUptime - presentation.effectStartUptime
            let progress = min(1, max(0, elapsed / duration))
            let visualProgress = reduceMotion ? 0.5 : progress
            let firstCueProgress = presentation.won
                ? (GameCompletionTimeline.victoryCueProgress.first ?? 0)
                : (GameCompletionTimeline.defeatCueProgress.first ?? 0)
            let firstCueUptime = presentation.effectStartUptime + duration * firstCueProgress

            ZStack {
                Color(presentation.won ? .systemGreen : .systemRed)
                    .opacity(reduceMotion ? 0.12 : veilAlpha(progress))

                if !reduceMotion {
                    Canvas(opaque: false, colorMode: .nonLinear, rendersAsynchronously: true) {
                        context, size in
                        if presentation.won {
                            drawVictoryFireworks(
                                context: &context,
                                size: size,
                                progress: CGFloat(progress)
                            )
                        } else {
                            drawDefeatCracks(
                                context: &context,
                                size: size,
                                progress: CGFloat(progress)
                            )
                        }
                    }
                }

                finishCallout(progress: visualProgress)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityIdentifier("game.completionEffect")
#if DEBUG
            .accessibilityValue(
                "firstCueVisible=\(nowUptime >= firstCueUptime);" +
                    "firstCueLagMillis=\(Int(max(0, nowUptime - firstCueUptime) * 1_000))"
            )
            .accessibilityHidden(
                ProcessInfo.processInfo.environment["DRAWLESS_XCTEST_COMPLETION_TIMING"] != "1"
            )
#else
            .accessibilityHidden(true)
#endif
        }
        .ignoresSafeArea()
        .task(id: presentation.id) {
            let visibleDuration = reduceMotion ? 1.0 : duration
            let remaining = max(
                0,
                presentation.effectStartUptime + visibleDuration
                    - ProcessInfo.processInfo.systemUptime
            )
            try? await Task.sleep(
                nanoseconds: UInt64(remaining * 1_000_000_000)
            )
            guard !Task.isCancelled else { return }
            onFinished()
        }
    }

    @ViewBuilder
    private func finishCallout(progress: Double) -> some View {
        let calloutAlpha: Double = if progress < 0.12 {
            progress / 0.12
        } else if progress > 0.78 {
            (1 - progress) / 0.22
        } else {
            1
        }
        let entrance = CGFloat(min(1, max(0, progress / 0.32)))
        let opponentName = opponent?.name ?? localized("Opponent")

        VStack(spacing: 8) {
            if presentation.won {
                ChessPieceView(
                    pieceCode: presentation.humanSide == "BLACK" ? "bK" : "wK",
                    themeId: themeId
                )
                .frame(width: 74, height: 74)
            } else if let opponent {
                OpponentPortrait(level: opponent, size: 74)
            } else {
                Image(systemName: "shield.slash.fill")
                    .font(.system(size: 64, weight: .bold))
            }

            Text(localized(presentation.won ? "Victory" : "Defeat"))
                .font(.system(size: 28, weight: .black))
                .tracking(2)
                .textCase(.uppercase)
            Text(presentation.won
                 ? localizedFormat("You defeated %1$s.", opponentName)
                 : localizedFormat("%1$s won.", opponentName))
                .font(.headline)
        }
        .foregroundStyle(.white)
        .multilineTextAlignment(.center)
        .padding(.horizontal, 30)
        .padding(.vertical, 24)
        .frame(maxWidth: 320)
        .background(
            Color(presentation.won ? .systemGreen : .systemRed).opacity(0.94),
            in: RoundedRectangle(cornerRadius: 28)
        )
        .shadow(color: .black.opacity(0.42), radius: 18, y: 8)
        .opacity(min(1, max(0, calloutAlpha)))
        .scaleEffect(0.84 + (0.16 * entrance))
        .offset(y: presentation.won ? 0 : 18 * (1 - entrance))
        .rotationEffect(.degrees(
            presentation.won ? 0 : Double(4 * (1 - entrance))
        ))
        .padding(24)
    }

    private func drawVictoryFireworks(
        context: inout GraphicsContext,
        size: CGSize,
        progress: CGFloat
    ) {
        let palette = [
            AppPalette.mint,
            Color(red: 1.0, green: 0.78, blue: 0.34),
            Color(red: 1.0, green: 0.48, blue: 0.40),
            AppPalette.gold,
            Color.white,
        ]
        let centers = [
            CGPoint(x: size.width * 0.22, y: size.height * 0.24),
            CGPoint(x: size.width * 0.78, y: size.height * 0.30),
            CGPoint(x: size.width * 0.52, y: size.height * 0.16),
        ]
        let starts = GameCompletionTimeline.victoryCueProgress.map { CGFloat($0) }
        let minimumDimension = min(size.width, size.height)

        for (burstIndex, center) in centers.enumerated() {
            let start = starts[burstIndex]
            let localProgress = unit((progress - start) / (1 - start))
            guard localProgress > 0 else { continue }
            let reveal = unit(localProgress / 0.34)
            let fade = unit(1 - ((localProgress - 0.68) / 0.32))
            let radius = minimumDimension * 0.20 * reveal * (0.78 + CGFloat(burstIndex) * 0.12)

            for ray in 0..<18 {
                let angle = (CGFloat(ray) / 18 * 2 * .pi) + CGFloat(burstIndex) * 0.31
                let direction = CGVector(dx: cos(angle), dy: sin(angle))
                let startRadius = radius * 0.32
                let endRadius = radius * (0.78 + CGFloat(ray % 3) * 0.10)
                let startPoint = CGPoint(
                    x: center.x + direction.dx * startRadius,
                    y: center.y + direction.dy * startRadius
                )
                let endPoint = CGPoint(
                    x: center.x + direction.dx * endRadius,
                    y: center.y + direction.dy * endRadius
                )
                let color = palette[(ray + burstIndex) % palette.count].opacity(Double(fade))
                var rayPath = Path()
                rayPath.move(to: startPoint)
                rayPath.addLine(to: endPoint)
                context.stroke(
                    rayPath,
                    with: .color(color),
                    style: StrokeStyle(
                        lineWidth: max(2, minimumDimension * 0.006),
                        lineCap: .round
                    )
                )
                let sparkRadius = max(2.5, minimumDimension * 0.007)
                context.fill(
                    Path(ellipseIn: CGRect(
                        x: endPoint.x - sparkRadius,
                        y: endPoint.y - sparkRadius,
                        width: sparkRadius * 2,
                        height: sparkRadius * 2
                    )),
                    with: .color(color)
                )
            }

            let ringRadius = radius * 0.58
            context.stroke(
                Path(ellipseIn: CGRect(
                    x: center.x - ringRadius,
                    y: center.y - ringRadius,
                    width: ringRadius * 2,
                    height: ringRadius * 2
                )),
                with: .color(AppPalette.mint.opacity(Double(fade * 0.62))),
                lineWidth: max(2, minimumDimension * 0.005)
            )
        }

        let confettiProgress = unit((progress - starts[0]) / (1 - starts[0]))
        for index in 0..<30 {
            let delay = CGFloat(index % 6) * 0.035
            let fall = unit((confettiProgress - delay) / (1 - delay))
            guard fall > 0 else { continue }
            let seededX = CGFloat((index * 37) % 101) / 101
            let x = size.width * (0.05 + seededX * 0.90) +
                sin((fall * 5 + CGFloat(index)) * 1.3) * size.width * 0.018
            let y = -size.height * 0.08 + fall * size.height * 1.05
            let pieceWidth = max(5, minimumDimension * 0.012)
            let pieceHeight = pieceWidth * 1.8
            let alpha = unit(1 - ((fall - 0.76) / 0.24))
            var piece = Path()
            piece.addRoundedRect(
                in: CGRect(
                    x: x - pieceWidth / 2,
                    y: y - pieceHeight / 2,
                    width: pieceWidth,
                    height: pieceHeight
                ),
                cornerSize: CGSize(width: pieceWidth * 0.25, height: pieceWidth * 0.25)
            )
            context.fill(
                piece,
                with: .color(palette[index % palette.count].opacity(Double(alpha)))
            )
        }
    }

    private func drawDefeatCracks(
        context: inout GraphicsContext,
        size: CGSize,
        progress: CGFloat
    ) {
        let minimumDimension = min(size.width, size.height)
        let impact = CGPoint(x: size.width * 0.54, y: size.height * 0.34)
        let cueProgress = GameCompletionTimeline.defeatCueProgress.map { CGFloat($0) }
        let impactReveal = unit((progress - cueProgress[0]) / 0.20)
        let fade = unit(1 - ((progress - 0.62) / 0.38))
        let lineColor = Color.white.opacity(Double(fade * 0.88))
        let shadowColor = Color(red: 0.13, green: 0.19, blue: 0.23)
            .opacity(Double(fade * 0.52))
        let angles: [CGFloat] = [-2.74, -2.18, -1.58, -1.02, -0.38, 0.18, 0.84, 1.42, 2.06, 2.58]

        let ringRadius = minimumDimension * 0.05 * impactReveal
        context.stroke(
            Path(ellipseIn: CGRect(
                x: impact.x - ringRadius,
                y: impact.y - ringRadius,
                width: ringRadius * 2,
                height: ringRadius * 2
            )),
            with: .color(Color.white.opacity(Double(fade * 0.45))),
            lineWidth: max(2, minimumDimension * 0.004)
        )

        for (index, angle) in angles.enumerated() {
            let start = index * 3 / angles.count < 2 ? cueProgress[1] : cueProgress[2]
            let reveal = unit((progress - start) / 0.24)
            guard reveal > 0 else { continue }
            let direction = CGVector(dx: cos(angle), dy: sin(angle))
            let length = minimumDimension * (0.24 + CGFloat(index % 4) * 0.055) * reveal
            let end = CGPoint(
                x: impact.x + direction.dx * length,
                y: impact.y + direction.dy * length
            )
            let strokeWidth = max(2.2, minimumDimension * 0.005)

            var shadow = Path()
            shadow.move(to: CGPoint(x: impact.x + 2, y: impact.y + 3))
            shadow.addLine(to: CGPoint(x: end.x + 2, y: end.y + 3))
            context.stroke(shadow, with: .color(shadowColor), lineWidth: strokeWidth + 2)

            var crack = Path()
            crack.move(to: impact)
            crack.addLine(to: end)
            context.stroke(
                crack,
                with: .color(lineColor),
                style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round)
            )

            let branchStart = CGPoint(
                x: impact.x + direction.dx * length * 0.54,
                y: impact.y + direction.dy * length * 0.54
            )
            let branchAngle = angle + (index.isMultiple(of: 2) ? 0.52 : -0.48)
            let branchLength = length * (0.30 + CGFloat(index % 3) * 0.06)
            var branch = Path()
            branch.move(to: branchStart)
            branch.addLine(to: CGPoint(
                x: branchStart.x + cos(branchAngle) * branchLength,
                y: branchStart.y + sin(branchAngle) * branchLength
            ))
            context.stroke(
                branch,
                with: .color(Color.white.opacity(Double(fade * 0.68))),
                lineWidth: strokeWidth * 0.72
            )
        }

        let shardReveal = unit((progress - cueProgress[2]) / 0.22)
        var shard = Path()
        shard.move(to: CGPoint(x: impact.x - minimumDimension * 0.035 * shardReveal, y: impact.y))
        shard.addLine(to: CGPoint(
            x: impact.x + minimumDimension * 0.018 * shardReveal,
            y: impact.y - minimumDimension * 0.045 * shardReveal
        ))
        shard.addLine(to: CGPoint(
            x: impact.x + minimumDimension * 0.042 * shardReveal,
            y: impact.y + minimumDimension * 0.028 * shardReveal
        ))
        shard.closeSubpath()
        context.fill(shard, with: .color(Color.white.opacity(Double(fade * 0.20))))
    }

    private func veilAlpha(_ progress: Double) -> Double {
        let fade = min(1, max(0, 1 - ((progress - 0.58) / 0.42)))
        return (presentation.won ? 0.16 : 0.22) * fade
    }

    private func unit(_ value: CGFloat) -> CGFloat {
        min(1, max(0, value))
    }
}

private func localized(_ key: String) -> String {
    NSLocalizedString(key, comment: "")
}

private func localizedFormat(_ key: String, _ arguments: CVarArg...) -> String {
    var format = localized(key)
    for index in 1...9 {
        format = format.replacingOccurrences(of: "%\(index)$s", with: "%\(index)$@")
    }
    format = format.replacingOccurrences(of: "%s", with: "%@")
    return String(format: format, locale: Locale.current, arguments: arguments)
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
    // Match the Android app chrome and follow the device's system appearance.
    static let background = adaptiveColor(light: 0xF7F4EA, dark: 0x141812)
    static let backgroundGradientEnd = adaptiveColor(light: 0xF1EEE4, dark: 0x20261D)
    static let panel = adaptiveColor(light: 0xEBE8DE, dark: 0x20261D)
    static let gold = adaptiveColor(light: 0x66561A, dark: 0xC9B26F)
    static let onGold = adaptiveColor(light: 0xFFFFFF, dark: 0x251D07)
    static let secondaryText = adaptiveColor(light: 0x514D42, dark: 0xCED3C7)
    static let highContrastText = adaptiveColor(light: 0x11110F, dark: 0xF8F8F3)
    static let dangerText = adaptiveColor(light: 0x8B1A1A, dark: 0xFFB4AB)
    static let mint = adaptiveColor(light: 0x536348, dark: 0xAEB89E)
    static let lightSquare = Color(red: 0.91, green: 0.88, blue: 0.80)
    static let darkSquare = Color(red: 0.19, green: 0.31, blue: 0.26)
    static let lastMove = Color(red: 0.89, green: 0.70, blue: 0.22)
}

private func adaptiveColor(light: UInt32, dark: UInt32) -> Color {
    Color(uiColor: UIColor { traits in
        let rgb = traits.userInterfaceStyle == .dark ? dark : light
        return UIColor(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    })
}

private struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .padding(.vertical, 14)
            .padding(.horizontal, 18)
            .background(AppPalette.gold.opacity(configuration.isPressed ? 0.72 : 1),
                        in: RoundedRectangle(cornerRadius: 12))
            .foregroundStyle(AppPalette.onGold)
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

private struct DialogActionButtonStyle: ButtonStyle {
    let filled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 18)
            .padding(.vertical, 11)
            .background(
                filled
                    ? AppPalette.gold.opacity(configuration.isPressed ? 0.72 : 1)
                    : AppPalette.panel.opacity(configuration.isPressed ? 0.62 : 0),
                in: Capsule()
            )
            .foregroundStyle(filled ? AppPalette.onGold : AppPalette.gold)
    }
}

private struct CompactButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .frame(minHeight: 44)
            .background(AppPalette.panel.opacity(configuration.isPressed ? 0.65 : 1),
                        in: RoundedRectangle(cornerRadius: 11))
            .foregroundStyle(.primary)
    }
}

#Preview {
    ContentView()
}
