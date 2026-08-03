import XCTest

@MainActor
final class DrawlessChessUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testPlayableGameUsesSharedCoordinatorAndBoardReducer() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        let header = app.descendants(matching: .any)["home.header"]
        XCTAssertTrue(header.waitForExistence(timeout: 30))
        XCTAssertTrue(header.label.contains("DRAWLESS CHESS"))
        discardSavedGameIfPresent(app)

        let status = app.descendants(matching: .any)["sharedCore.status"]
        XCTAssertTrue(status.exists)
        XCTAssertTrue(status.label.contains("20 legal moves"))
        XCTAssertTrue(status.label.contains("perft(2) 400"))

        app.buttons["home.newGame"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["setup.side"].waitForExistence(timeout: 5))
        app.buttons["White"].tap()
        app.buttons["setup.start"].tap()

        let board = app.descendants(matching: .any)["game.board"]
        XCTAssertTrue(board.waitForExistence(timeout: 10))
        XCTAssertTrue(app.descendants(matching: .any)["game.status"].label.contains("Your turn"))

        let hint = app.buttons["game.hint"]
        XCTAssertTrue(hint.isEnabled)
        hint.tap()
        XCTAssertTrue(app.descendants(matching: .any)["game.hintResult"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.descendants(matching: .any)["game.engineError"].exists)

        let e2 = app.descendants(matching: .any)["square.e2"]
        let e4 = app.descendants(matching: .any)["square.e4"]
        XCTAssertTrue(e2.exists)
        XCTAssertTrue(e2.label.contains("White pawn"))
        e2.tap()
        XCTAssertTrue(e4.label.contains("legal move"))
        e4.tap()

        let history = app.descendants(matching: .any)["game.history"]
        XCTAssertTrue(history.waitForExistence(timeout: 5))
        XCTAssertTrue(history.label.contains("1. e4"))
        let opponentMoved = XCTNSPredicateExpectation(
            predicate: NSPredicate { object, _ in
                guard let element = object as? XCUIElement else { return false }
                return element.label.contains("1. e4") && element.label != "1. e4"
            },
            object: history
        )
        XCTAssertEqual(XCTWaiter.wait(for: [opponentMoved], timeout: 15), .completed)
        XCTAssertFalse(app.descendants(matching: .any)["game.engineError"].exists)
        XCTAssertTrue(app.buttons["game.undo"].isEnabled)
        app.buttons["game.undo"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["square.e2"].label.contains("White pawn"))
    }

    @MainActor
    func testResponsiveGameLayoutInPortraitAndLandscape() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 30))
        discardSavedGameIfPresent(app)
        app.buttons["home.newGame"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["setup.side"].waitForExistence(timeout: 5))
        app.buttons["White"].tap()
        app.buttons["setup.start"].tap()

        let board = app.descendants(matching: .any)["game.board"]
        let sidePanel = app.descendants(matching: .any)["game.sidePanel"]
        XCTAssertTrue(board.waitForExistence(timeout: 10))
        XCTAssertTrue(sidePanel.waitForExistence(timeout: 10))
        XCTAssertGreaterThanOrEqual(sidePanel.frame.minY, board.frame.maxY - 2)

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.descendants(matching: .any)["square.e2"].waitForExistence(timeout: 10))
        let landscapeLayout = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in
                sidePanel.frame.minX >= board.frame.maxX - 2
            },
            object: sidePanel
        )
        XCTAssertEqual(XCTWaiter.wait(for: [landscapeLayout], timeout: 10), .completed)
        XCTAssertTrue(app.buttons["game.undo"].exists)
        XCTAssertTrue(app.buttons["game.home"].isHittable)
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testSavedGameResumesFromAndroidCompatibleCheckpointPayload() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 30))
        if app.buttons["home.discard"].exists {
            app.buttons["home.discard"].tap()
        }

        app.buttons["home.newGame"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["setup.side"].waitForExistence(timeout: 5))
        app.buttons["White"].tap()
        app.buttons["setup.start"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["game.board"].waitForExistence(timeout: 10))
        app.descendants(matching: .any)["square.e2"].tap()
        app.descendants(matching: .any)["square.e4"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["game.history"].label.contains("1. e4"))

        app.buttons["game.home"].tap()
        XCTAssertTrue(app.buttons["home.resume"].waitForExistence(timeout: 10))
        app.buttons["home.resume"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["game.board"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.descendants(matching: .any)["game.history"].label.contains("1. e4"))
        XCTAssertTrue(app.descendants(matching: .any)["square.e4"].label.contains("White pawn"))
    }

    @MainActor
    func testAllBoardThemesAreSelectableAndPersist() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 30))
        app.buttons["home.options"].tap()

        let picker = app.buttons.matching(identifier: "options.boardTheme").firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        for theme in [
            "Imperial Marble",
            "Desert Sandstone",
            "Glacier Slate",
            "Verdigris Copper",
            "Amethyst Geode"
        ] {
            if selectionText(of: picker).contains(theme) { continue }
            openPicker(picker)
            let option = app.buttons[theme]
            XCTAssertTrue(option.waitForExistence(timeout: 5), "Missing theme option: \(theme)")
            option.tap()
            XCTAssertTrue(app.staticTexts[theme].waitForExistence(timeout: 5), "Theme picker did not select: \(theme)")
        }

        app.buttons["Back"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 10))
        app.terminate()
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 30))
        app.buttons["home.options"].tap()
        let persistedPicker = app.buttons.matching(identifier: "options.boardTheme").firstMatch
        XCTAssertTrue(app.staticTexts["Amethyst Geode"].waitForExistence(timeout: 10))
        openPicker(persistedPicker)
        app.buttons["Imperial Marble"].tap()
        app.buttons["Back"].tap()
    }

    @MainActor
    func testQuickPlayUsesSelectedOpponentAndPersistsSelection() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 30))
        discardSavedGameIfPresent(app)

        let picker = app.buttons.matching(identifier: "home.quickOpponent").firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        picker.tap()
        let grandmaster = app.buttons["Lucian · 2550"]
        XCTAssertTrue(grandmaster.waitForExistence(timeout: 5))
        grandmaster.tap()
        XCTAssertTrue(selectedOpponentSummary(in: app, contains: "Lucian").waitForExistence(timeout: 5))

        app.buttons["home.quickPlay"].tap()
        let opponent = app.staticTexts
            .matching(identifier: "game.opponent")
            .matching(NSPredicate(format: "label CONTAINS[c] %@", "Lucian"))
            .firstMatch
        XCTAssertTrue(opponent.waitForExistence(timeout: 15))

        app.buttons["game.home"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 10))
        XCTAssertTrue(selectedOpponentSummary(in: app, contains: "Lucian").waitForExistence(timeout: 5))
        discardSavedGameIfPresent(app)

        picker.tap()
        app.buttons["Theo · 800"].tap()
    }

    @MainActor
    func testAdvancedSetupOffersEveryOpponentAndConfiguration() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 30))
        discardSavedGameIfPresent(app)
        app.buttons["home.newGame"].tap()

        let opponentPicker = app.buttons.matching(identifier: "setup.opponent").firstMatch
        XCTAssertTrue(opponentPicker.waitForExistence(timeout: 10))
        for opponent in [
            "Vesper · 800",
            "Mira · 550",
            "Theo · 800",
            "Rhea · 1000",
            "Mateo · 1300",
            "Yuna · 1675",
            "Amara · 2100",
            "Lucian · 2550"
        ] {
            opponentPicker.tap()
            let option = app.buttons[opponent]
            XCTAssertTrue(option.waitForExistence(timeout: 5), "Missing opponent: \(opponent)")
            option.tap()
            XCTAssertTrue(app.staticTexts[opponent].waitForExistence(timeout: 5))
        }
        app.buttons["Escape"].tap()
        XCTAssertTrue(app.buttons["Escape"].isSelected)
        app.buttons["Black"].tap()
        XCTAssertTrue(app.buttons["Black"].isSelected)
        let threats = app.switches["setup.threats"]
        XCTAssertTrue(threats.exists)
        threats.tap()
        XCTAssertTrue(isOn(threats))
        app.buttons["Back"].tap()
    }

    @MainActor
    func testPresentationOptionsPersistAcrossRelaunch() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 30))
        app.buttons["home.options"].tap()

        let coordinates = app.switches["options.coordinates"]
        let threats = app.switches["options.threats"]
        XCTAssertTrue(coordinates.waitForExistence(timeout: 10))
        let originalCoordinates = isOn(coordinates)
        setToggle(coordinates, to: !originalCoordinates)
        XCTAssertTrue(scrollToExistence(threats, in: app))
        let originalThreats = isOn(threats)
        setToggle(threats, to: !originalThreats)
        app.buttons["Back"].tap()

        app.terminate()
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 30))
        app.buttons["home.options"].tap()
        let persistedCoordinates = app.switches["options.coordinates"]
        let persistedThreats = app.switches["options.threats"]
        XCTAssertTrue(persistedCoordinates.waitForExistence(timeout: 10))
        XCTAssertEqual(isOn(persistedCoordinates), !originalCoordinates)
        XCTAssertTrue(scrollToExistence(persistedThreats, in: app))
        XCTAssertEqual(isOn(persistedThreats), !originalThreats)

        setToggle(persistedThreats, to: originalThreats)
        for _ in 0..<4 {
            if persistedCoordinates.isHittable { break }
            app.swipeDown()
        }
        XCTAssertTrue(persistedCoordinates.isHittable)
        setToggle(persistedCoordinates, to: originalCoordinates)
        app.buttons["Back"].tap()
    }

    @MainActor
    func testForfeitSavedGameRecordsLossAndStartsReplacement() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 30))
        discardSavedGameIfPresent(app)

        let baseline = statisticsCounts(in: app)
        app.buttons["Back"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 10))

        app.buttons["home.newGame"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["setup.side"].waitForExistence(timeout: 5))
        app.buttons["White"].tap()
        app.buttons["setup.start"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["game.board"].waitForExistence(timeout: 10))
        app.buttons["game.home"].tap()
        XCTAssertTrue(app.buttons["home.resume"].waitForExistence(timeout: 10))

        app.buttons["home.quickPlay"].tap()
        let forfeit = app.buttons["Forfeit & start new game"]
        XCTAssertTrue(forfeit.waitForExistence(timeout: 5))
        forfeit.tap()
        XCTAssertTrue(app.descendants(matching: .any)["game.board"].waitForExistence(timeout: 15))
        app.buttons["game.home"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 10))
        discardSavedGameIfPresent(app)

        let updated = statisticsCounts(in: app)
        XCTAssertEqual(updated.games, baseline.games + 1)
        XCTAssertEqual(updated.losses, baseline.losses + 1)
    }

    private func discardSavedGameIfPresent(_ app: XCUIApplication) {
        let discard = app.buttons["home.discard"]
        if discard.exists { discard.tap() }
    }

    private func selectedOpponentSummary(in app: XCUIApplication, contains name: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(identifier: "home.quickOpponentSummary")
            .matching(NSPredicate(format: "label CONTAINS[c] %@", name))
            .firstMatch
    }

    private func statisticsCounts(in app: XCUIApplication) -> (games: Int, losses: Int) {
        app.buttons["home.statistics"].tap()
        let games = app.staticTexts.matching(identifier: "statistics.games").firstMatch
        let losses = app.staticTexts.matching(identifier: "statistics.losses").firstMatch
        XCTAssertTrue(games.waitForExistence(timeout: 10))
        XCTAssertTrue(losses.waitForExistence(timeout: 10))
        return (integer(in: games.label), integer(in: losses.label))
    }

    private func integer(in label: String) -> Int {
        Int(label.split(whereSeparator: { !$0.isNumber }).first ?? "") ?? -1
    }

    private func isOn(_ toggle: XCUIElement) -> Bool {
        let value = (toggle.value as? String)?.lowercased() ?? ""
        return value == "1" || value == "on" || value == "yes" || value == "true"
    }

    private func selectionText(of element: XCUIElement) -> String {
        [element.label, element.value as? String]
            .compactMap { $0 }
            .joined(separator: " ")
    }

    private func openPicker(_ picker: XCUIElement) {
        if picker.frame.width < 500 {
            picker.tap()
            return
        }
        let leadingEdge = picker.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.5))
        leadingEdge.withOffset(CGVector(dx: 24, dy: 0)).tap()
    }

    private func scrollToExistence(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        for _ in 0..<4 {
            if element.exists { return true }
            app.swipeUp()
        }
        return element.exists
    }

    private func setToggle(_ toggle: XCUIElement, to expected: Bool) {
        if isOn(toggle) == expected { return }
        let physicalSwitch = toggle.switches.firstMatch
        if physicalSwitch.exists {
            physicalSwitch.tap()
        } else {
            let trailingEdge = toggle.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 0.5))
            trailingEdge.withOffset(CGVector(dx: -24, dy: 0)).tap()
        }
        let changed = XCTNSPredicateExpectation(
            predicate: NSPredicate { [self] object, _ in
                guard let element = object as? XCUIElement else { return false }
                return isOn(element) == expected
            },
            object: toggle
        )
        XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 5), .completed)
    }
}
