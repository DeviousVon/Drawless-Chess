import XCTest

/// Deterministic, simulator-only visual inventory for Android/iOS parity review.
///
/// Screenshots are kept as named PNG attachments in the `.xcresult` bundle. The launch
/// argument domain masks mutable player history without adding test behavior to the app.
@MainActor
final class DrawlessChessScreenshotCaptureUITests: XCTestCase {
    override func setUpWithError() throws {
        #if targetEnvironment(simulator)
        continueAfterFailure = false
        #else
        throw XCTSkip("Screenshot capture inventory is simulator-only")
        #endif
    }

    func testCapture01HomeSetupAndInformationScreens() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = launchDeterministicApp()
        discardSavedGameIfPresent(app)

        let opponentButton = app.buttons["home.quickOpponent"]
        XCTAssertTrue(scrollToHittable(opponentButton, in: app, direction: .up))
        let opponentPicker = app.scrollViews["home.quickOpponentPicker"]
        XCTAssertTrue(completeSimulatorTransition(
            context: "home-to-quick-opponent",
            trigger: { app.buttons["home.quickOpponent"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["home.header"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["home.header"].exists &&
                    !app.scrollViews["home.quickOpponentPicker"].exists
            },
            destinationIsCurrent: {
                app.scrollViews["home.quickOpponentPicker"].exists
            }
        ))
        let casual = app.buttons["home.quickOpponent.option.casual"]
        for _ in 0..<4 where !casual.isHittable {
            opponentPicker.swipeLeft()
        }
        XCTAssertTrue(casual.isHittable)
        casual.tap()
        let theoName = app.staticTexts["home.quickOpponent.detailName"]
        XCTAssertTrue(theoName.waitForExistence(timeout: 5))
        XCTAssertEqual(theoName.label, "Theo")
        capture("ios-sim-phone-opponent-theo-en-us.png")
        XCTAssertTrue(completeSimulatorTransition(
            context: "quick-opponent-to-home",
            trigger: { app.buttons["home.quickOpponent.done"] },
            sourceAnchors: { [app.scrollViews["home.quickOpponentPicker"]] },
            sourceIsCurrent: {
                app.scrollViews["home.quickOpponentPicker"].exists
            },
            destinationIsCurrent: {
                !app.scrollViews["home.quickOpponentPicker"].exists &&
                    app.descendants(matching: .any)["home.header"].exists
            }
        ))

        let themeButton = app.buttons["home.theme"]
        XCTAssertTrue(scrollToHittable(themeButton, in: app, direction: .up))
        XCTAssertTrue(completeSimulatorTransition(
            context: "home-to-theme-picker",
            trigger: { app.buttons["home.theme"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["home.header"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["home.header"].exists &&
                    !app.descendants(matching: .any)["theme.picker"].exists
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["theme.picker"].exists
            }
        ))
        let imperial = app.buttons["theme.option.imperial_marble"]
        XCTAssertTrue(imperial.waitForExistence(timeout: 5))
        capture("ios-sim-phone-theme-picker-en-us.png")
        XCTAssertTrue(completeSimulatorTransition(
            context: "theme-picker-to-home",
            trigger: { app.buttons["theme.dismiss"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["theme.picker"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["theme.picker"].exists
            },
            destinationIsCurrent: {
                !app.descendants(matching: .any)["theme.picker"].exists &&
                    app.descendants(matching: .any)["home.header"].exists
            }
        ))

        scrollToTop(in: app)
        XCTAssertFalse(app.buttons["home.resume"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].exists)
        capture("ios-sim-phone-home-clean-en-us.png")

        let dialogs: [(trigger: String, dialog: String, filename: String)] = [
            ("home.rules", "rules", "ios-sim-phone-dialog-rules-en-us.png"),
            ("home.license", "license", "ios-sim-phone-dialog-license-en-us.png"),
            ("home.privacy", "privacy", "ios-sim-phone-dialog-privacy-en-us.png"),
        ]
        for dialog in dialogs {
            let trigger = app.buttons[dialog.trigger]
            XCTAssertTrue(scrollToHittable(trigger, in: app, direction: .up))
            XCTAssertTrue(completeSimulatorTransition(
                context: "home-to-\(dialog.dialog)-dialog",
                trigger: { app.buttons[dialog.trigger] },
                sourceAnchors: {
                    [app.descendants(matching: .any)["home.header"]]
                },
                sourceIsCurrent: {
                    app.descendants(matching: .any)["home.header"].exists &&
                        !app.descendants(matching: .any)["dialog.\(dialog.dialog)"].exists
                },
                destinationIsCurrent: {
                    app.descendants(matching: .any)["dialog.\(dialog.dialog)"].exists
                }
            ))
            capture(dialog.filename)
            XCTAssertTrue(completeSimulatorTransition(
                context: "\(dialog.dialog)-dialog-to-home",
                trigger: { app.buttons["dialog.\(dialog.dialog).dismiss"] },
                sourceAnchors: {
                    [app.descendants(matching: .any)["dialog.\(dialog.dialog)"]]
                },
                sourceIsCurrent: {
                    app.descendants(matching: .any)["dialog.\(dialog.dialog)"].exists
                },
                destinationIsCurrent: {
                    !app.descendants(matching: .any)["dialog.\(dialog.dialog)"].exists &&
                        app.descendants(matching: .any)["home.header"].exists
                }
            ))
        }

        let customGame = app.buttons["home.newGame"]
        XCTAssertTrue(scrollToHittable(customGame, in: app, direction: .down))
        XCTAssertTrue(completeSimulatorTransition(
            context: "home-to-custom-setup-capture",
            trigger: { app.buttons["home.newGame"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["home.header"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["home.header"].exists &&
                    !app.descendants(matching: .any)["setup.clock"].exists
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["setup.clock"].exists
            },
            timeout: 10
        ))
        scrollToTop(in: app)
        capture("ios-sim-phone-custom-default-en-us.png")

        let advanced = app.buttons["setup.advanced.toggle"]
        let startGame = app.buttons["setup.start"]
        let setupScrollView = app.scrollViews.allElementsBoundByIndex.max {
            $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height
        } ?? app.scrollViews.firstMatch
        XCTAssertTrue(advanced.waitForExistence(timeout: 5))
        for _ in 0..<4 where advanced.frame.maxY >= startGame.frame.minY - 8 {
            setupScrollView.swipeUp()
        }
        // XCTest can report this control as hittable while it is still below the
        // fixed Start-game bar. Require the full button above the bar before tapping.
        XCTAssertLessThan(advanced.frame.maxY, startGame.frame.minY - 8)
        XCTAssertTrue(advanced.isHittable)
        advanced.tap()
        let rules = app.descendants(matching: .any)["setup.rules"]
        XCTAssertTrue(rules.waitForExistence(timeout: 5))
        XCTAssertTrue(scrollToVisibleCenter(rules, in: app, direction: .up))
        capture("ios-sim-phone-custom-advanced-en-us.png")

        let deadPosition = app.buttons["setup.deadPosition.material"]
        XCTAssertTrue(scrollToHittable(deadPosition, in: app, direction: .up, maximumSwipes: 12))
        capture("ios-sim-phone-custom-advanced-scrolled-en-us.png")
        app.buttons["Back"].tap()
    }

    func testCapture02OptionsAndStatisticsScreens() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = launchDeterministicApp()
        discardSavedGameIfPresent(app)

        let options = app.buttons["home.options"]
        XCTAssertTrue(scrollToHittable(options, in: app, direction: .up))
        XCTAssertTrue(completeSimulatorTransition(
            context: "home-to-options",
            trigger: { app.buttons["home.options"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["home.header"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["home.header"].exists &&
                    !app.descendants(matching: .any)["options.section.audio"].exists
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["options.section.audio"].exists
            },
            timeout: 10
        ))
        scrollToTop(in: app)
        capture("ios-sim-phone-options-top-en-us.png")

        let version = app.staticTexts["options.version"]
        XCTAssertTrue(scrollToVisibleCenter(version, in: app, direction: .up, maximumSwipes: 12))
        capture("ios-sim-phone-options-bottom-en-us.png")
        XCTAssertTrue(completeSimulatorTransition(
            context: "options-to-home",
            trigger: { app.buttons["Back"] },
            sourceAnchors: { [app.staticTexts["options.version"]] },
            sourceIsCurrent: {
                app.staticTexts["options.version"].exists &&
                    !app.descendants(matching: .any)["home.header"].exists
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["home.header"].exists
            },
            timeout: 10
        ))

        openStatistics(in: app)
        XCTAssertEqual(statisticsGameCount(in: app), 0)
        scrollToTop(in: app)
        capture("ios-sim-phone-player-stats-empty-en-us.png")
        XCTAssertTrue(completeSimulatorTransition(
            context: "empty-statistics-to-home",
            trigger: { app.buttons["Back"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["statistics.player"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["statistics.player"].exists &&
                    !app.descendants(matching: .any)["home.header"].exists
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["home.header"].exists
            },
            timeout: 10
        ))

        seedOneCompletedLoss(in: app)
        openStatistics(in: app)
        XCTAssertEqual(statisticsGameCount(in: app), 1)
        scrollToTop(in: app)
        capture("ios-sim-phone-player-stats-populated-en-us.png")
    }

    func testCapture03GameplayAndReviewScreens() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = launchDeterministicApp()
        discardSavedGameIfPresent(app)
        startWhiteCustomGame(in: app)

        let board = app.descendants(matching: .any)["game.board"]
        XCTAssertTrue(board.waitForExistence(timeout: 15))
        scrollToTop(in: app)
        capture("ios-sim-phone-gameplay-initial-en-us.png")

        let e2 = app.descendants(matching: .any)["square.e2"]
        let e4 = app.descendants(matching: .any)["square.e4"]
        XCTAssertTrue(scrollToHittable(e2, in: app, direction: .down))
        XCTAssertTrue(e4.isHittable)
        XCTAssertTrue(completeSimulatorTransition(
            context: "gameplay-select-e2",
            trigger: {
                app.descendants(matching: .any)["square.e2"]
            },
            sourceAnchors: {
                [
                    app.descendants(matching: .any)["game.board"],
                    app.descendants(matching: .any)["square.e2"],
                    app.descendants(matching: .any)["square.e4"],
                ]
            },
            sourceIsCurrent: {
                let liveE2 = app.descendants(matching: .any)["square.e2"]
                let history = app.descendants(matching: .any)["game.history"]
                return app.descendants(matching: .any)["game.board"].exists &&
                    liveE2.exists &&
                    !liveE2.label.localizedCaseInsensitiveContains("selected") &&
                    !history.label.contains("1. e4")
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["square.e2"].label
                    .localizedCaseInsensitiveContains("selected")
            }
        ))
        capture("ios-sim-phone-gameplay-selected-en-us.png")

        let history = app.descendants(matching: .any)["game.history"]
        XCTAssertTrue(completeSimulatorTransition(
            context: "gameplay-commit-e2-to-e4",
            trigger: {
                app.descendants(matching: .any)["square.e4"]
            },
            sourceAnchors: {
                [
                    app.descendants(matching: .any)["game.board"],
                    app.descendants(matching: .any)["square.e2"],
                    app.descendants(matching: .any)["square.e4"],
                    app.descendants(matching: .any)["game.history"],
                ]
            },
            sourceIsCurrent: {
                let liveE2 = app.descendants(matching: .any)["square.e2"]
                let liveE4 = app.descendants(matching: .any)["square.e4"]
                let liveHistory = app.descendants(matching: .any)["game.history"]
                return app.descendants(matching: .any)["game.board"].exists &&
                    liveE2.label.localizedCaseInsensitiveContains("selected") &&
                    liveE2.label.localizedCaseInsensitiveContains("white pawn") &&
                    liveE4.label.localizedCaseInsensitiveContains("empty") &&
                    !liveHistory.label.contains("1. e4")
            },
            destinationIsCurrent: {
                let liveE2 = app.descendants(matching: .any)["square.e2"]
                let liveE4 = app.descendants(matching: .any)["square.e4"]
                let liveHistory = app.descendants(matching: .any)["game.history"]
                return liveE2.label.localizedCaseInsensitiveContains("empty") &&
                    liveE4.label.localizedCaseInsensitiveContains("white pawn") &&
                    liveHistory.label.contains("1. e4")
            }
        ))
        let opponentMoved = XCTNSPredicateExpectation(
            predicate: NSPredicate { object, _ in
                guard let element = object as? XCUIElement else { return false }
                return element.label.contains("1. e4") && element.label != "1. e4"
            },
            object: history
        )
        XCTAssertEqual(XCTWaiter.wait(for: [opponentMoved], timeout: 30), .completed)
        scrollToTop(in: app)
        capture("ios-sim-phone-gameplay-in-progress-en-us.png")

        let hint = app.buttons["game.hint"]
        XCTAssertTrue(scrollToHittable(hint, in: app, direction: .up))
        hint.tap()
        XCTAssertTrue(app.descendants(matching: .any)["game.hintResult"].waitForExistence(timeout: 30))
        capture("ios-sim-phone-gameplay-hint-en-us.png")

        let pause = app.buttons["game.pause"]
        XCTAssertTrue(scrollToHittable(pause, in: app, direction: .up))
        pause.tap()
        let resume = app.buttons["game.resume"]
        XCTAssertTrue(resume.waitForExistence(timeout: 5))
        capture("ios-sim-phone-gameplay-paused-en-us.png")
        resume.tap()

        scrollToTop(in: app)
        let flip = app.buttons["Flip"].firstMatch
        XCTAssertTrue(flip.waitForExistence(timeout: 5))
        XCTAssertTrue(flip.isHittable)
        flip.tap()
        capture("ios-sim-phone-gameplay-flipped-en-us.png")

        let resign = app.buttons["game.resign"]
        XCTAssertTrue(scrollToHittable(resign, in: app, direction: .up))
        XCTAssertTrue(completeSimulatorTransition(
            context: "game-to-resign-dialog",
            trigger: { app.buttons["game.resign"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["game.board"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["game.board"].exists &&
                    !app.descendants(matching: .any)["dialog.resign"].exists
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["dialog.resign"].exists
            }
        ))
        capture("ios-sim-phone-dialog-resign-en-us.png")
        XCTAssertTrue(completeSimulatorTransition(
            context: "resign-dialog-to-game",
            trigger: { app.buttons["dialog.resign.cancel"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["dialog.resign"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["dialog.resign"].exists
            },
            destinationIsCurrent: {
                !app.descendants(matching: .any)["dialog.resign"].exists &&
                    app.descendants(matching: .any)["game.board"].exists
            }
        ))

        scrollToTop(in: app)
        XCTAssertTrue(completeSimulatorTransition(
            context: "game-to-home",
            trigger: { app.buttons["game.home"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["game.board"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["game.board"].exists &&
                    !app.buttons["home.resume"].exists
            },
            destinationIsCurrent: {
                app.buttons["home.resume"].exists &&
                    app.descendants(matching: .any)["home.header"].exists
            },
            timeout: 10
        ))
        scrollToTop(in: app)
        capture("ios-sim-phone-home-resume-en-us.png")

        XCTAssertTrue(completeSimulatorTransition(
            context: "home-to-resumed-game",
            trigger: { app.buttons["home.resume"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["home.header"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["home.header"].exists &&
                    app.buttons["home.resume"].exists &&
                    !app.descendants(matching: .any)["game.board"].exists
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["game.board"].exists
            },
            timeout: 15
        ))
        completeCurrentGameByResigning(in: app)

        let review = app.buttons["game.postGame.reviewGate"]
        XCTAssertTrue(review.waitForExistence(timeout: 15))
        XCTAssertTrue(review.isHittable)
        let reviewBoard = app.descendants(matching: .any)["review.board"]
        XCTAssertTrue(completeSimulatorTransition(
            context: "post-game-to-review",
            trigger: { app.buttons["game.postGame.reviewGate"] },
            sourceAnchors: {
                [app.buttons["game.postGame.reviewGate"]]
            },
            sourceIsCurrent: {
                app.buttons["game.postGame.reviewGate"].exists &&
                    !app.descendants(matching: .any)["review.board"].exists
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["review.board"].exists
            },
            timeout: 20
        ))
        XCTAssertTrue(app.buttons["review.move.1"].waitForExistence(timeout: 60))
        let opponentMove = app.buttons["review.move.2"]
        XCTAssertFalse(opponentMove.exists)
        let showOpponentMoves = app.switches["review.showOpponentMoves"]
        XCTAssertTrue(
            scrollToHittable(showOpponentMoves, in: app, direction: .up, maximumSwipes: 12)
        )
        setToggle(showOpponentMoves, to: true)
        XCTAssertTrue(opponentMove.waitForExistence(timeout: 20))
        XCTAssertTrue(scrollToHittable(opponentMove, in: app, direction: .up, maximumSwipes: 12))
        opponentMove.tap()
        let context = app.descendants(matching: .any)["review.detail.context"]
        XCTAssertTrue(context.waitForExistence(timeout: 5))
        XCTAssertTrue(scrollToVisibleCenter(context, in: app, direction: .down))
        capture("ios-sim-phone-review-context-en-us.png")

        XCTAssertTrue(scrollToVisibleCenter(reviewBoard, in: app, direction: .down, maximumSwipes: 12))
        let reviewFlip = app.buttons["review.flip"]
        XCTAssertTrue(reviewFlip.isHittable)
        reviewFlip.tap()
        capture("ios-sim-phone-review-flipped-en-us.png")

        XCTAssertTrue(app.descendants(matching: .any)["review.complete"].waitForExistence(timeout: 120))
        XCTAssertFalse(app.descendants(matching: .any)["review.error"].exists)
        let summary = app.descendants(matching: .any)["review.summary"]
        XCTAssertTrue(scrollToVisibleCenter(summary, in: app, direction: .up, maximumSwipes: 16))
        capture("ios-sim-phone-review-summary-en-us.png")
    }

    private func launchDeterministicApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US",
            "-AppleInterfaceStyle", "Light",
            "-quickPlayOpponentId", "casual",
            "-boardThemeId", "imperial_marble",
            // String values in the argument domain intentionally mask persisted Data values.
            "-completedGames.history.v1", "ui-test-empty",
            "-stats.legacyBaseline.v1", "ui-test-empty",
            "-stats.games", "0",
            "-stats.wins", "0",
            "-stats.losses", "0",
            "-stats.totalScore", "0",
        ]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 30))
        return app
    }

    private func discardSavedGameIfPresent(_ app: XCUIApplication) {
        let discard = app.buttons["home.discard"]
        if discard.exists {
            discard.tap()
            XCTAssertFalse(app.buttons["home.resume"].exists)
        }
    }

    private func startWhiteCustomGame(in app: XCUIApplication) {
        let customGame = app.buttons["home.newGame"]
        XCTAssertTrue(scrollToHittable(customGame, in: app, direction: .up))
        XCTAssertTrue(completeSimulatorTransition(
            context: "home-to-custom-game-setup",
            trigger: { app.buttons["home.newGame"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["home.header"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["home.header"].exists &&
                    !app.buttons["setup.side.white"].exists
            },
            destinationIsCurrent: {
                app.buttons["setup.side.white"].exists
            },
            timeout: 10
        ))
        XCTAssertTrue(completeSimulatorTransition(
            context: "custom-game-setup-select-white",
            trigger: { app.buttons["setup.side.white"] },
            sourceAnchors: {
                [
                    app.buttons["setup.side.white"],
                    app.buttons["setup.start"],
                ]
            },
            sourceIsCurrent: {
                let liveWhite = app.buttons["setup.side.white"]
                return liveWhite.exists &&
                    !liveWhite.isSelected &&
                    app.buttons["setup.start"].exists &&
                    !app.descendants(matching: .any)["game.board"].exists
            },
            destinationIsCurrent: {
                app.buttons["setup.side.white"].isSelected &&
                    !app.descendants(matching: .any)["game.board"].exists
            }
        ))
        XCTAssertTrue(completeSimulatorTransition(
            context: "custom-game-setup-to-game",
            trigger: { app.buttons["setup.start"] },
            sourceAnchors: {
                [app.buttons["setup.side.white"]]
            },
            sourceIsCurrent: {
                app.buttons["setup.side.white"].isSelected &&
                    !app.descendants(matching: .any)["game.board"].exists
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["game.board"].exists
            },
            timeout: 15
        ))
    }

    private func seedOneCompletedLoss(in app: XCUIApplication) {
        XCTAssertTrue(app.descendants(matching: .any)["home.header"].waitForExistence(timeout: 10))
        startWhiteCustomGame(in: app)
        completeCurrentGameByResigning(in: app)
        let review = app.buttons["game.postGame.reviewGate"]
        XCTAssertTrue(review.waitForExistence(timeout: 15))
        let saveExit = app.buttons["review.saveExit"]
        XCTAssertTrue(completeSimulatorTransition(
            context: "seed-post-game-to-review",
            trigger: { app.buttons["game.postGame.reviewGate"] },
            sourceAnchors: {
                [app.buttons["game.postGame.reviewGate"]]
            },
            sourceIsCurrent: {
                app.buttons["game.postGame.reviewGate"].exists &&
                    !app.buttons["review.saveExit"].exists
            },
            destinationIsCurrent: {
                app.buttons["review.saveExit"].exists
            },
            timeout: 20
        ))
        XCTAssertTrue(saveExit.isHittable)
        XCTAssertTrue(completeSimulatorTransition(
            context: "seed-review-to-home",
            trigger: { app.buttons["review.saveExit"] },
            sourceAnchors: { [app.buttons["review.saveExit"]] },
            sourceIsCurrent: {
                app.buttons["review.saveExit"].exists &&
                    !app.descendants(matching: .any)["home.header"].exists
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["home.header"].exists
            },
            timeout: 10
        ))
    }

    private func completeCurrentGameByResigning(in app: XCUIApplication) {
        let resign = app.buttons["game.resign"]
        XCTAssertTrue(scrollToHittable(resign, in: app, direction: .up))
        let confirm = app.buttons
            .matching(NSPredicate(
                format: "label == %@ AND identifier != %@",
                "Resign game",
                "game.resign"
            ))
            .firstMatch
        XCTAssertTrue(completeSimulatorTransition(
            context: "game-to-resign-confirmation",
            trigger: { app.buttons["game.resign"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["game.board"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["game.board"].exists &&
                    !app.buttons
                        .matching(NSPredicate(
                            format: "label == %@ AND identifier != %@",
                            "Resign game",
                            "game.resign"
                        ))
                        .firstMatch.exists
            },
            destinationIsCurrent: {
                app.buttons
                    .matching(NSPredicate(
                        format: "label == %@ AND identifier != %@",
                        "Resign game",
                        "game.resign"
                    ))
                    .firstMatch.exists
            }
        ))
        XCTAssertTrue(confirm.isHittable)
        XCTAssertTrue(completeSimulatorTransition(
            context: "resign-confirmation-to-post-game",
            trigger: {
                app.buttons
                    .matching(NSPredicate(
                        format: "label == %@ AND identifier != %@",
                        "Resign game",
                        "game.resign"
                    ))
                    .firstMatch
            },
            sourceAnchors: {
                [app.descendants(matching: .any)["dialog.resign"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["dialog.resign"].exists &&
                    !app.buttons["game.postGame.reviewGate"].exists
            },
            destinationIsCurrent: {
                app.buttons["game.postGame.reviewGate"].exists
            },
            timeout: 15
        ))
    }

    private func openStatistics(in app: XCUIApplication) {
        let statistics = app.buttons["home.statistics"]
        XCTAssertTrue(scrollToHittable(statistics, in: app, direction: .up))
        XCTAssertTrue(completeSimulatorTransition(
            context: "home-to-statistics",
            trigger: { app.buttons["home.statistics"] },
            sourceAnchors: {
                [app.descendants(matching: .any)["home.header"]]
            },
            sourceIsCurrent: {
                app.descendants(matching: .any)["home.header"].exists &&
                    !app.descendants(matching: .any)["statistics.player"].exists
            },
            destinationIsCurrent: {
                app.descendants(matching: .any)["statistics.player"].exists
            },
            timeout: 10
        ))
    }

    private func statisticsGameCount(in app: XCUIApplication) -> Int {
        let games = app.staticTexts["statistics.games"]
        XCTAssertTrue(games.waitForExistence(timeout: 10))
        return Int(games.label.split(whereSeparator: { !$0.isNumber }).first ?? "") ?? -1
    }

    /// Retries only simulator screenshot transitions whose first synthesized contact was
    /// observably lost while the exact source state is still intact. Physical execution is
    /// excluded by `setUpWithError`; keep the non-simulator branch inert as a second guard.
    private func completeSimulatorTransition(
        context: String,
        trigger: () -> XCUIElement,
        sourceAnchors: () -> [XCUIElement],
        sourceIsCurrent: () -> Bool,
        destinationIsCurrent: () -> Bool,
        timeout: TimeInterval = 5
    ) -> Bool {
        #if targetEnvironment(simulator)
        if destinationIsCurrent() { return true }

        for attempt in 1...2 {
            let liveElements = sourceAnchors() + [trigger()]
            guard sourceIsCurrent(),
                  liveElements.allSatisfy(\.exists),
                  waitForStableFrames({
                      sourceAnchors() + [trigger()]
                  })
            else { return false }

            // Re-query after the stability wait so the tap never uses an element object
            // captured before a SwiftUI navigation or accessibility-tree refresh.
            let liveTrigger = trigger()
            guard sourceIsCurrent(),
                  !destinationIsCurrent(),
                  liveTrigger.exists,
                  liveTrigger.isHittable
            else { return destinationIsCurrent() }

            if attempt == 2 {
                FileHandle.standardError.write(Data((
                    "DRAWLESS_SIMULATOR_INPUT_RETRY " +
                        "screenshot-transition=\(context) attempt=2\n"
                ).utf8))
            }

            liveTrigger.tap()
            if waitForCondition(timeout: timeout, destinationIsCurrent) { return true }

            guard attempt == 1,
                  !destinationIsCurrent(),
                  sourceIsCurrent()
            else { return false }

            let retryTrigger = trigger()
            let retryAnchors = sourceAnchors()
            guard retryTrigger.exists,
                  retryTrigger.isHittable,
                  retryAnchors.allSatisfy(\.exists)
            else { return false }
        }
        return false
        #else
        return false
        #endif
    }

    private func waitForCondition(
        timeout: TimeInterval,
        _ condition: () -> Bool
    ) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        return condition()
    }

    private func waitForStableFrames(
        _ elements: () -> [XCUIElement],
        timeout: TimeInterval = 3,
        sampleInterval: TimeInterval = 0.1,
        requiredStableSamples: Int = 3
    ) -> Bool {
        var previousFrames: [CGRect]?
        var stableSamples = 0
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(sampleInterval))
            let liveElements = elements()
            guard !liveElements.isEmpty,
                  liveElements.allSatisfy({ $0.exists && !$0.frame.isEmpty })
            else {
                previousFrames = nil
                stableSamples = 0
                continue
            }

            let frames = liveElements.map(\.frame)
            if frames == previousFrames {
                stableSamples += 1
                if stableSamples >= requiredStableSamples { return true }
            } else {
                previousFrames = frames
                stableSamples = 0
            }
        }
        return false
    }

    private func capture(_ filename: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = filename
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private enum SwipeDirection {
        case up
        case down
    }

    private func scrollToTop(in app: XCUIApplication, maximumSwipes: Int = 10) {
        let scrollView = app.scrollViews.firstMatch
        for _ in 0..<maximumSwipes {
            if scrollView.exists { scrollView.swipeDown() }
            else { app.swipeDown() }
        }
    }

    private func scrollToHittable(
        _ element: XCUIElement,
        in app: XCUIApplication,
        direction: SwipeDirection,
        maximumSwipes: Int = 10
    ) -> Bool {
        if element.exists && element.isHittable { return true }
        let scrollView = app.scrollViews.firstMatch
        for _ in 0..<maximumSwipes {
            swipe(direction, in: app, scrollView: scrollView)
            if element.exists && element.isHittable { return true }
        }
        return element.exists && element.isHittable
    }

    private func scrollToVisibleCenter(
        _ element: XCUIElement,
        in app: XCUIApplication,
        direction: SwipeDirection,
        maximumSwipes: Int = 10
    ) -> Bool {
        let window = app.windows.firstMatch
        func centerIsVisible() -> Bool {
            let frame = element.frame
            return element.exists && !frame.isEmpty &&
                window.frame.contains(CGPoint(x: frame.midX, y: frame.midY))
        }
        if centerIsVisible() { return true }
        let scrollView = app.scrollViews.firstMatch
        for _ in 0..<maximumSwipes {
            swipe(direction, in: app, scrollView: scrollView)
            if centerIsVisible() { return true }
        }
        return centerIsVisible()
    }

    private func swipe(
        _ direction: SwipeDirection,
        in app: XCUIApplication,
        scrollView: XCUIElement
    ) {
        switch (direction, scrollView.exists) {
        case (.up, true): scrollView.swipeUp()
        case (.down, true): scrollView.swipeDown()
        case (.up, false): app.swipeUp()
        case (.down, false): app.swipeDown()
        }
    }

    private func setToggle(_ toggle: XCUIElement, to expected: Bool) {
        let isOn = { (element: XCUIElement) -> Bool in
            let value = (element.value as? String)?.lowercased() ?? ""
            return value == "1" || value == "on" || value == "yes" || value == "true"
        }
        if isOn(toggle) == expected { return }

        XCTAssertTrue(toggle.isHittable)
        let trailingEdge = toggle.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 0.5))
        trailingEdge.withOffset(CGVector(dx: -24, dy: 0)).tap()

        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline, isOn(toggle) != expected {
            Thread.sleep(forTimeInterval: 0.05)
        }
        XCTAssertEqual(isOn(toggle), expected)
    }
}
