package com.drawlesschess.core

import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey

private data class AcceptanceReplay(
    val outcome: GameOutcome?,
    val facts: PositionFacts,
)

internal fun registerRulesAcceptanceFixtureTests(suite: TestSuite) {
    suite.test("rules acceptance catalog covers every policy and precedence probe for both movers") {
        val scenarioIds = rulesAcceptanceScenarios.map { it.id }
        assertThat(scenarioIds.distinct().size == scenarioIds.size)
        val expectationIds = rulesAcceptanceScenarios.flatMap { scenario ->
            scenario.expectations.map { expectation -> "${scenario.id}/${expectation.id}" }
        }
        assertThat(expectationIds.distinct().size == expectationIds.size)

        val coverage = mutableMapOf<RulesAcceptanceProbe, MutableSet<Side>>()
        rulesAcceptanceScenarios.forEach { scenario ->
            scenario.expectations.flatMap { it.probes }.forEach { probe ->
                coverage.getOrPut(probe, ::mutableSetOf) += scenario.mover
            }
        }
        rulesPrecedenceFactsFixtures.forEach { fixture ->
            coverage.getOrPut(fixture.probe, ::mutableSetOf) += fixture.mover
        }
        RulesAcceptanceProbe.entries.forEach { probe ->
            assertThat(
                coverage[probe] == Side.entries.toSet(),
                "$probe does not have both White- and Black-mover acceptance coverage",
            )
        }
    }

    suite.test("same-position policy pairs actually produce different adjudication") {
        rulesAcceptanceScenarios.filter { it.expectations.size > 1 }.forEach { scenario ->
            val expected = scenario.expectations.map { it.expectedOutcome }.toSet()
            assertThat(expected.size > 1, "${scenario.id} is policy-blind despite multiple expectations")
        }
    }

    rulesAcceptanceScenarios.forEach { scenario ->
        suite.test("rules acceptance board fixture ${scenario.id}") {
            scenario.expectations.forEach { expectation ->
                val replay = replay(scenario, expectation.rules)
                assertThat(
                    replay.outcome == expectation.expectedOutcome,
                    "${scenario.id}/${expectation.id}: expected ${expectation.expectedOutcome}, got ${replay.outcome}",
                )
                verifyProbeFacts(scenario, expectation, replay.facts)
            }
        }
    }

    suite.test("unreachable precedence collisions stay guarded at the adjudicator boundary") {
        val adjudicator = DrawlessAdjudicator()
        rulesPrecedenceFactsFixtures.forEach { fixture ->
            val outcome = adjudicator.adjudicate(fixture.rules, fixture.facts)
            assertThat(outcome == fixture.expectedOutcome, "${fixture.id}: got $outcome")
        }
    }

    suite.test("catalog separates true native discriminators from controls and precedence guards") {
        val expectations = rulesAcceptanceScenarios.flatMap { it.expectations }
        assertThat(expectations.any {
            it.ordinaryChessContrast == OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR
        })
        assertThat(expectations.any {
            it.ordinaryChessContrast == OrdinaryChessContrast.POLICY_CONTROL
        })
        assertThat(expectations.any {
            it.ordinaryChessContrast == OrdinaryChessContrast.PRECEDENCE_GUARD
        })
        assertThat(expectations.filter {
            it.ordinaryChessContrast == OrdinaryChessContrast.PRECEDENCE_GUARD
        }.all { expectation -> expectation.expectedOutcome?.reason == EndReason.CHECKMATE })
    }
}

private fun replay(
    scenario: RulesAcceptanceScenario,
    rules: RulesContractV1,
): AcceptanceReplay {
    var position = ChessPosition.fromFen(scenario.initialFen)
    var session = GameSession.newGame(
        gameId = "acceptance-${scenario.id}",
        rules = rules,
        initialPositionKey = RepetitionKey.of(position),
        sideToMove = position.sideToMove,
    )
    scenario.prefixMoves.forEachIndexed { index, move ->
        assertThat(session.outcome == null, "${scenario.id} ended before prefix ply ${index + 1}")
        val transition = ChessAdapter.transition(position, move)
        position = ChessRules.apply(position, move)
        session = session.apply(transition)
    }
    assertThat(session.outcome == null, "${scenario.id} ended before its selected move")
    assertThat(position.sideToMove == scenario.mover, "${scenario.id} mover metadata is wrong")
    val transition = ChessAdapter.transition(position, scenario.selectedMove)
    val facts = factsFor(session, transition)
    position = ChessRules.apply(position, scenario.selectedMove)
    session = session.apply(transition)
    assertThat(session.sideToMove == position.sideToMove)
    assertThat(session.adjudicationFacts == facts.takeIf { session.outcome != null })
    return AcceptanceReplay(session.outcome, facts)
}

private fun factsFor(session: GameSession, transition: MoveTransition): PositionFacts = PositionFacts(
    mover = transition.mover,
    legalMovesAfter = transition.legalMovesAfter,
    sideToMoveInCheck = transition.sideToMoveInCheck,
    positionOccurrenceCount = session.history.occurrences(transition.resultingPositionKey) + 1,
    repetitionAvoidingAlternativesBeforeMove = transition.legalAlternativesBeforeMove.count { alternative ->
        session.history.occurrences(alternative.resultingPositionKey) + 1 < session.rules.repetitionThreshold
    },
    halfmoveClockAfter = transition.halfmoveClockAfter,
    fiftyMoveAvoidingAlternativesBeforeMove = transition.legalAlternativesBeforeMove.count { alternative ->
        alternative.resultingHalfmoveClock < 100
    },
    deadPositionAfter = transition.deadPositionAfter,
    moveWasCapture = transition.moveWasCapture,
    materialAfter = transition.materialAfter,
    lastCaptureBy = if (transition.moveWasCapture) transition.mover else session.lastCaptureBy,
)

private fun verifyProbeFacts(
    scenario: RulesAcceptanceScenario,
    expectation: RulesAcceptanceExpectation,
    facts: PositionFacts,
) {
    expectation.probes.forEach { probe ->
        when (probe) {
            RulesAcceptanceProbe.STALEMATE_TRAPPED_PLAYER_LOSES,
            RulesAcceptanceProbe.STALEMATE_TRAPPED_PLAYER_WINS ->
                assertThat(facts.legalMovesAfter == 0 && !facts.sideToMoveInCheck)

            RulesAcceptanceProbe.REPETITION_AVOIDABLE ->
                assertThat(facts.positionOccurrenceCount >= 3 && facts.repetitionAvoidingAlternativesBeforeMove > 0)

            RulesAcceptanceProbe.REPETITION_FORCED_EXCEPTION ->
                assertThat(facts.positionOccurrenceCount >= 3 && facts.repetitionAvoidingAlternativesBeforeMove == 0)

            RulesAcceptanceProbe.BARE_KING_LOSES,
            RulesAcceptanceProbe.BARE_KING_CONTINUES -> assertThat(facts.hasOneBareKing())

            RulesAcceptanceProbe.DEAD_POSITION_MATERIAL,
            RulesAcceptanceProbe.DEAD_POSITION_FINAL_CAPTURE ->
                assertThat(facts.deadPositionAfter && facts.moveWasCapture)

            RulesAcceptanceProbe.DEAD_POSITION_FINAL_CAPTURE_QUIET_PROMOTION ->
                assertThat(facts.deadPositionAfter && !facts.moveWasCapture)

            RulesAcceptanceProbe.FIFTY_MOVE_DISABLED,
            RulesAcceptanceProbe.FIFTY_MOVE_COMPLETING_PLAYER_LOSES,
            RulesAcceptanceProbe.FIFTY_MOVE_MATERIAL -> assertThat(facts.halfmoveClockAfter >= 100)

            RulesAcceptanceProbe.FIFTY_MOVE_MATERIAL_LAST_CAPTURE -> assertThat(
                facts.halfmoveClockAfter >= 100 &&
                    facts.materialAfter.white == facts.materialAfter.black &&
                    facts.lastCaptureBy == scenario.mover,
            )

            RulesAcceptanceProbe.FIFTY_MOVE_FORCED_AVOIDABLE ->
                assertThat(facts.halfmoveClockAfter >= 100 && facts.fiftyMoveAvoidingAlternativesBeforeMove > 0)

            RulesAcceptanceProbe.FIFTY_MOVE_FORCED_EXCEPTION ->
                assertThat(facts.halfmoveClockAfter >= 100 && facts.fiftyMoveAvoidingAlternativesBeforeMove == 0)

            RulesAcceptanceProbe.PRECEDENCE_TERMINAL_OVER_REPETITION ->
                error("${scenario.id}: terminal-over-repetition is intentionally fact-only")

            RulesAcceptanceProbe.PRECEDENCE_TERMINAL_OVER_LOWER_POLICIES ->
                assertThat(facts.legalMovesAfter == 0 && facts.halfmoveClockAfter >= 100)

            RulesAcceptanceProbe.PRECEDENCE_REPETITION_OVER_LOWER_POLICIES ->
                assertThat(facts.positionOccurrenceCount >= 3 && facts.halfmoveClockAfter >= 100)

            RulesAcceptanceProbe.PRECEDENCE_BARE_KING_OVER_DEAD_POSITION ->
                assertThat(facts.hasOneBareKing() && facts.deadPositionAfter)

            RulesAcceptanceProbe.PRECEDENCE_BARE_KING_OVER_FIFTY_MOVE ->
                assertThat(facts.hasOneBareKing() && facts.halfmoveClockAfter >= 100)

            RulesAcceptanceProbe.PRECEDENCE_DEAD_POSITION_OVER_FIFTY_MOVE ->
                assertThat(facts.deadPositionAfter && facts.halfmoveClockAfter >= 100)
        }
    }
}

private fun PositionFacts.hasOneBareKing(): Boolean =
    (materialAfter.white == 0 && materialAfter.black > 0) ||
        (materialAfter.black == 0 && materialAfter.white > 0)
