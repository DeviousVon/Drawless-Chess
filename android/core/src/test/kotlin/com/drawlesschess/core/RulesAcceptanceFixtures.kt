package com.drawlesschess.core

import com.drawlesschess.core.chess.ChessPosition

/**
 * Test-only acceptance catalog for proving that every v1 rule input reaches adjudication.
 * Scenarios are deliberately mirrored by color. Expectations on the same scenario are the
 * policy discriminator: an engine that merely plays ordinary chess cannot satisfy both.
 */
internal enum class RulesAcceptanceProbe {
    STALEMATE_TRAPPED_PLAYER_LOSES,
    STALEMATE_TRAPPED_PLAYER_WINS,
    REPETITION_AVOIDABLE,
    REPETITION_FORCED_EXCEPTION,
    BARE_KING_LOSES,
    BARE_KING_CONTINUES,
    DEAD_POSITION_MATERIAL,
    DEAD_POSITION_FINAL_CAPTURE,
    DEAD_POSITION_FINAL_CAPTURE_QUIET_PROMOTION,
    FIFTY_MOVE_DISABLED,
    FIFTY_MOVE_COMPLETING_PLAYER_LOSES,
    FIFTY_MOVE_FORCED_AVOIDABLE,
    FIFTY_MOVE_FORCED_EXCEPTION,
    FIFTY_MOVE_MATERIAL,
    FIFTY_MOVE_MATERIAL_LAST_CAPTURE,
    PRECEDENCE_TERMINAL_OVER_REPETITION,
    PRECEDENCE_TERMINAL_OVER_LOWER_POLICIES,
    PRECEDENCE_REPETITION_OVER_LOWER_POLICIES,
    PRECEDENCE_BARE_KING_OVER_DEAD_POSITION,
    PRECEDENCE_BARE_KING_OVER_FIFTY_MOVE,
    PRECEDENCE_DEAD_POSITION_OVER_FIFTY_MOVE,
}

internal enum class OrdinaryChessContrast {
    /** The expected winner or continuation is not an ordinary-chess draw/continuation. */
    TRUE_POLICY_DISCRIMINATOR,

    /** The ordinary behavior is useful as the control half of a policy pair. */
    POLICY_CONTROL,

    /** Winner agrees with ordinary chess; only versioned precedence is being protected. */
    PRECEDENCE_GUARD,
}

internal data class RulesAcceptanceExpectation(
    val id: String,
    val rules: RulesContractV1,
    val expectedOutcome: GameOutcome?,
    val probes: Set<RulesAcceptanceProbe>,
    val ordinaryChessContrast: OrdinaryChessContrast,
)

internal data class RulesAcceptanceScenario(
    val id: String,
    val mover: Side,
    val initialFen: String,
    val prefixMoves: List<UciMove> = emptyList(),
    val selectedMove: UciMove,
    val expectations: List<RulesAcceptanceExpectation>,
)

private fun win(side: Side, reason: EndReason) = GameOutcome(side, reason = reason)

private fun drawlessRules(
    deadPosition: DeadPositionPolicy = DeadPositionPolicy.MATERIAL_VICTORY,
    fiftyMove: FiftyMovePolicy = FiftyMovePolicy.MATERIAL_VICTORY,
    bareKing: BareKingPolicy = BareKingPolicy.BARE_KING_LOSES,
) = RulesContractV1.drawless(deadPosition, fiftyMove).copy(bareKing = bareKing)

private fun escapeRules(
    deadPosition: DeadPositionPolicy = DeadPositionPolicy.MATERIAL_VICTORY,
    fiftyMove: FiftyMovePolicy = FiftyMovePolicy.MATERIAL_VICTORY,
    bareKing: BareKingPolicy = BareKingPolicy.BARE_KING_LOSES,
) = RulesContractV1.escape(deadPosition, fiftyMove).copy(bareKing = bareKing)

private fun expectation(
    id: String,
    rules: RulesContractV1,
    outcome: GameOutcome?,
    contrast: OrdinaryChessContrast,
    vararg probes: RulesAcceptanceProbe,
) = RulesAcceptanceExpectation(id, rules, outcome, probes.toSet(), contrast)

private val whiteAvoidableRepetitionPrefix =
    listOf("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1").map(::UciMove)
private val blackAvoidableRepetitionPrefix =
    listOf("g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8").map(::UciMove)
private val blackForcedRepetitionPrefix =
    listOf("f6f7", "g8h8", "f7f6", "h8g8", "f6f7", "g8h8", "f7f6").map(::UciMove)
private val whiteForcedRepetitionPrefix =
    listOf("c3c2", "b1a1", "c2c3", "a1b1", "c3c2", "b1a1", "c2c3").map(::UciMove)

private fun encodedMoves(value: String): List<UciMove> = value.trim().split(Regex("\\s+")).map(::UciMove)

/** Capture followed by 100 quiet, non-third-repeating halfmoves; the last move reaches 100. */
private val whiteLastCaptureTieHistory = encodedMoves(
    """
    e3d4 a8a2 a1b1 a2a1 b1b2 a1a2 b2b3 a2a1 b3a3 a1a2 a3a4 a2a1 a4a2 a1b1
    a2a1 b1b2 a1a2 b2b3 a2a1 b3a3 a1a2 a3a4 d4c3 a4a3 c3b2 a3a4 a2a1 a4a2
    b2b1 a2a3 a1a2 a3a4 a2a1 a4a2 b1c1 a2a3 a1a2 a3a4 a2a1 a4a2 a1b1 a2a1
    c1b2 a1a2 b2b3 a2a1 b1b2 a1a2 b2c2 a2a1 b3b2 a1a2 b2b1 a2a1 b1b2 a1a3
    b2b1 a3a2 b1c1 a2a1 c1d2 a1a2 c2b2 a2a1 b2a2 a1b1 a2a1 b1b2 d2c1 b2b1
    c1c2 b1b2 c2c3 b2a2 a1b1 a2a1 b1b2 a1a2 b2b3 a2a1 b3a3 a1a2 a3a4 a2a1
    a4a2 a1b1 a2a1 b1b2 a1a2 b2b3 c3c2 b3a3 a2a1 a3a2 c2b3 a2a3 b3b2 a3a4
    a1a2 a4a3 b2a1
    """,
)

private val blackLastCaptureTieHistory = encodedMoves(
    """
    e6d5 a1a2 a8a3 a2a1 a3a2 a1b1 a2a1 b1c1 a1a2 c1a1 a2a3 a1a2 a3a4 a2a1
    a4a5 a1a2 a5a6 a2a1 a6a4 a1a2 a4a5 a2a1 a5a6 a1a2 a6a7 a2a1 a7b7 a1a2
    b7b1 e1d2 b1a1 a2a3 a1a2 d2c1 a2a1 c1b2 a1a2 b2b1 a2a1 b1c2 a1a2 c2b3
    a2a1 a3a2 a1b1 a2b2 b1a1 b2b1 a1a2 b1a1 a2a3 b3b2 a3a2 b2b1 a2a3 a1a2
    a3a4 a2a1 a4a2 b1c1 a2a3 a1a2 a3a4 a2a1 a4a2 a1b1 a2a1 c1b2 a1a2 b2b3
    a2a1 b1b2 a1a2 b2c2 a2a1 b3b2 a1a2 b2b1 a2a1 b1b2 a1a3 b2b1 a3a2 b1c1
    a2a1 c1d2 a1a2 c2b2 a2a1 b2a2 a1b1 a2a1 b1b2 d2c1 b2b1 c1c2 b1b2 c2c3
    b2a2 a1b1 a2a1
    """,
)

internal val rulesAcceptanceScenarios: List<RulesAcceptanceScenario> = listOf(
    RulesAcceptanceScenario(
        id = "stalemate-black-trapped",
        mover = Side.WHITE,
        initialFen = "k7/8/2QK4/8/8/8/8/8 w - - 99 1",
        selectedMove = UciMove("c6c7"),
        expectations = listOf(
            expectation(
                "drawless", drawlessRules(), win(Side.WHITE, EndReason.STALEMATE),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.STALEMATE_TRAPPED_PLAYER_LOSES,
                RulesAcceptanceProbe.PRECEDENCE_TERMINAL_OVER_LOWER_POLICIES,
            ),
            expectation(
                "escape", escapeRules(), win(Side.BLACK, EndReason.STALEMATE),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.STALEMATE_TRAPPED_PLAYER_WINS,
                RulesAcceptanceProbe.PRECEDENCE_TERMINAL_OVER_LOWER_POLICIES,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "stalemate-white-trapped",
        mover = Side.BLACK,
        initialFen = "8/8/8/8/8/2qk4/8/K7 b - - 99 1",
        selectedMove = UciMove("c3c2"),
        expectations = listOf(
            expectation(
                "drawless", drawlessRules(), win(Side.BLACK, EndReason.STALEMATE),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.STALEMATE_TRAPPED_PLAYER_LOSES,
                RulesAcceptanceProbe.PRECEDENCE_TERMINAL_OVER_LOWER_POLICIES,
            ),
            expectation(
                "escape", escapeRules(), win(Side.WHITE, EndReason.STALEMATE),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.STALEMATE_TRAPPED_PLAYER_WINS,
                RulesAcceptanceProbe.PRECEDENCE_TERMINAL_OVER_LOWER_POLICIES,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "avoidable-repetition-black",
        mover = Side.BLACK,
        initialFen = ChessPosition.START_FEN.replace(" 0 1", " 92 1"),
        prefixMoves = whiteAvoidableRepetitionPrefix,
        selectedMove = UciMove("f6g8"),
        expectations = listOf(
            expectation(
                "third-occurrence", drawlessRules(fiftyMove = FiftyMovePolicy.MATERIAL_VICTORY),
                win(Side.WHITE, EndReason.REPETITION), OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.REPETITION_AVOIDABLE,
                RulesAcceptanceProbe.PRECEDENCE_REPETITION_OVER_LOWER_POLICIES,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "avoidable-repetition-white",
        mover = Side.WHITE,
        initialFen = "rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 92 1",
        prefixMoves = blackAvoidableRepetitionPrefix,
        selectedMove = UciMove("g1f3"),
        expectations = listOf(
            expectation(
                "third-occurrence", drawlessRules(fiftyMove = FiftyMovePolicy.MATERIAL_VICTORY),
                win(Side.BLACK, EndReason.REPETITION), OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.REPETITION_AVOIDABLE,
                RulesAcceptanceProbe.PRECEDENCE_REPETITION_OVER_LOWER_POLICIES,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "forced-repetition-black",
        mover = Side.BLACK,
        initialFen = "6k1/7p/5Q2/8/8/8/8/6K1 w - - 92 1",
        prefixMoves = blackForcedRepetitionPrefix,
        selectedMove = UciMove("h8g8"),
        expectations = listOf(
            expectation(
                "only-legal", drawlessRules(fiftyMove = FiftyMovePolicy.DISABLED),
                win(Side.BLACK, EndReason.REPETITION), OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.REPETITION_FORCED_EXCEPTION,
                RulesAcceptanceProbe.PRECEDENCE_REPETITION_OVER_LOWER_POLICIES,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "forced-repetition-white",
        mover = Side.WHITE,
        initialFen = "1k6/8/8/8/8/2q5/P7/1K6 b - - 92 1",
        prefixMoves = whiteForcedRepetitionPrefix,
        selectedMove = UciMove("a1b1"),
        expectations = listOf(
            expectation(
                "only-legal", drawlessRules(fiftyMove = FiftyMovePolicy.DISABLED),
                win(Side.WHITE, EndReason.REPETITION), OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.REPETITION_FORCED_EXCEPTION,
                RulesAcceptanceProbe.PRECEDENCE_REPETITION_OVER_LOWER_POLICIES,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "bare-black-king",
        mover = Side.WHITE,
        initialFen = "4k3/8/8/8/8/8/8/R3K3 w - - 0 1",
        selectedMove = UciMove("a1a2"),
        expectations = listOf(
            expectation(
                "loses", drawlessRules(fiftyMove = FiftyMovePolicy.DISABLED),
                win(Side.WHITE, EndReason.BARE_KING), OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.BARE_KING_LOSES,
            ),
            expectation(
                "continues", drawlessRules(
                    fiftyMove = FiftyMovePolicy.DISABLED, bareKing = BareKingPolicy.CONTINUE,
                ), null, OrdinaryChessContrast.POLICY_CONTROL,
                RulesAcceptanceProbe.BARE_KING_CONTINUES,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "bare-white-king",
        mover = Side.BLACK,
        initialFen = "r3k3/8/8/8/8/8/8/4K3 b - - 0 1",
        selectedMove = UciMove("a8a7"),
        expectations = listOf(
            expectation(
                "loses", drawlessRules(fiftyMove = FiftyMovePolicy.DISABLED),
                win(Side.BLACK, EndReason.BARE_KING), OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.BARE_KING_LOSES,
            ),
            expectation(
                "continues", drawlessRules(
                    fiftyMove = FiftyMovePolicy.DISABLED, bareKing = BareKingPolicy.CONTINUE,
                ), null, OrdinaryChessContrast.POLICY_CONTROL,
                RulesAcceptanceProbe.BARE_KING_CONTINUES,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "dead-position-white-captures",
        mover = Side.WHITE,
        initialFen = "4k3/8/8/8/3b4/4K3/7b/8 w - - 0 1",
        selectedMove = UciMove("e3d4"),
        expectations = listOf(
            expectation(
                "material", drawlessRules(
                    deadPosition = DeadPositionPolicy.MATERIAL_VICTORY,
                    fiftyMove = FiftyMovePolicy.DISABLED,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.BLACK, EndReason.DEAD_POSITION_MATERIAL),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.DEAD_POSITION_MATERIAL,
            ),
            expectation(
                "final-capture", drawlessRules(
                    deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
                    fiftyMove = FiftyMovePolicy.DISABLED,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.WHITE, EndReason.DEAD_POSITION_FINAL_CAPTURE),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.DEAD_POSITION_FINAL_CAPTURE,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "dead-position-black-captures",
        mover = Side.BLACK,
        initialFen = "8/7B/4k3/3B4/8/8/8/4K3 b - - 0 1",
        selectedMove = UciMove("e6d5"),
        expectations = listOf(
            expectation(
                "material", drawlessRules(
                    deadPosition = DeadPositionPolicy.MATERIAL_VICTORY,
                    fiftyMove = FiftyMovePolicy.DISABLED,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.WHITE, EndReason.DEAD_POSITION_MATERIAL),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.DEAD_POSITION_MATERIAL,
            ),
            expectation(
                "final-capture", drawlessRules(
                    deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
                    fiftyMove = FiftyMovePolicy.DISABLED,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.BLACK, EndReason.DEAD_POSITION_FINAL_CAPTURE),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.DEAD_POSITION_FINAL_CAPTURE,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "dead-position-white-quiet-underpromotion",
        mover = Side.WHITE,
        initialFen = "7k/1P6/2K5/8/8/b7/8/8 w - - 0 1",
        selectedMove = UciMove("b7b8b"),
        expectations = listOf(
            expectation(
                "final-capture-quiet-promotion", drawlessRules(
                    deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
                    fiftyMove = FiftyMovePolicy.DISABLED,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.WHITE, EndReason.DEAD_POSITION_FINAL_CAPTURE),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.DEAD_POSITION_FINAL_CAPTURE_QUIET_PROMOTION,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "dead-position-black-quiet-underpromotion",
        mover = Side.BLACK,
        initialFen = "8/8/7B/8/8/5k2/6p1/K7 b - - 0 1",
        selectedMove = UciMove("g2g1b"),
        expectations = listOf(
            expectation(
                "final-capture-quiet-promotion", drawlessRules(
                    deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
                    fiftyMove = FiftyMovePolicy.DISABLED,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.BLACK, EndReason.DEAD_POSITION_FINAL_CAPTURE),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.DEAD_POSITION_FINAL_CAPTURE_QUIET_PROMOTION,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "dead-position-white-quiet-knight-underpromotion",
        mover = Side.WHITE,
        initialFen = "7k/1P6/K7/8/8/8/8/8 w - - 0 1",
        selectedMove = UciMove("b7b8n"),
        expectations = listOf(
            expectation(
                "final-capture-quiet-promotion", drawlessRules(
                    deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
                    fiftyMove = FiftyMovePolicy.DISABLED,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.WHITE, EndReason.DEAD_POSITION_FINAL_CAPTURE),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.DEAD_POSITION_FINAL_CAPTURE_QUIET_PROMOTION,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "dead-position-black-quiet-knight-underpromotion",
        mover = Side.BLACK,
        initialFen = "8/8/8/8/8/7k/6p1/K7 b - - 0 1",
        selectedMove = UciMove("g2g1n"),
        expectations = listOf(
            expectation(
                "final-capture-quiet-promotion", drawlessRules(
                    deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
                    fiftyMove = FiftyMovePolicy.DISABLED,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.BLACK, EndReason.DEAD_POSITION_FINAL_CAPTURE),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.DEAD_POSITION_FINAL_CAPTURE_QUIET_PROMOTION,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "fifty-avoidable-white",
        mover = Side.WHITE,
        initialFen = "4k3/8/8/8/8/8/P6R/4K3 w - - 99 1",
        selectedMove = UciMove("h2h3"),
        expectations = fiftyMoveExpectations(Side.WHITE),
    ),
    RulesAcceptanceScenario(
        id = "fifty-avoidable-black",
        mover = Side.BLACK,
        initialFen = "4k3/p6r/8/8/8/8/8/4K3 b - - 99 1",
        selectedMove = UciMove("h7h6"),
        expectations = fiftyMoveExpectations(Side.BLACK),
    ),
    RulesAcceptanceScenario(
        id = "fifty-forced-white",
        mover = Side.WHITE,
        initialFen = "4k2r/8/8/8/8/8/8/R3K3 w - - 99 1",
        selectedMove = UciMove("e1e2"),
        expectations = forcedFiftyMoveExpectations(Side.WHITE),
    ),
    RulesAcceptanceScenario(
        id = "fifty-forced-black",
        mover = Side.BLACK,
        initialFen = "r3k3/8/8/8/8/8/8/4K2R b - - 99 1",
        selectedMove = UciMove("e8e7"),
        expectations = forcedFiftyMoveExpectations(Side.BLACK),
    ),
    RulesAcceptanceScenario(
        id = "fifty-material-tie-prior-white-capture",
        mover = Side.WHITE,
        initialFen = "r3k3/8/8/8/3p4/4K3/8/R7 w - - 0 1",
        prefixMoves = whiteLastCaptureTieHistory.dropLast(1),
        selectedMove = whiteLastCaptureTieHistory.last(),
        expectations = listOf(
            expectation(
                "last-capturer-wins", drawlessRules(
                    fiftyMove = FiftyMovePolicy.MATERIAL_VICTORY,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.WHITE, EndReason.FIFTY_MOVE_LIMIT),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.FIFTY_MOVE_MATERIAL_LAST_CAPTURE,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "fifty-material-tie-prior-black-capture",
        mover = Side.BLACK,
        initialFen = "r7/8/4k3/3P4/8/8/8/R3K3 b - - 0 1",
        prefixMoves = blackLastCaptureTieHistory.dropLast(1),
        selectedMove = blackLastCaptureTieHistory.last(),
        expectations = listOf(
            expectation(
                "last-capturer-wins", drawlessRules(
                    fiftyMove = FiftyMovePolicy.MATERIAL_VICTORY,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.BLACK, EndReason.FIFTY_MOVE_LIMIT),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.FIFTY_MOVE_MATERIAL_LAST_CAPTURE,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "checkmate-over-fifty-black",
        mover = Side.BLACK,
        initialFen = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 99 2",
        selectedMove = UciMove("d8h4"),
        expectations = listOf(
            expectation(
                "checkmate", drawlessRules(
                    fiftyMove = FiftyMovePolicy.COMPLETING_PLAYER_LOSES,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.BLACK, EndReason.CHECKMATE), OrdinaryChessContrast.PRECEDENCE_GUARD,
                RulesAcceptanceProbe.PRECEDENCE_TERMINAL_OVER_LOWER_POLICIES,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "checkmate-over-fifty-white",
        mover = Side.WHITE,
        initialFen = "rnbqkbnr/ppppp2p/5p2/6p1/4P3/8/PPPP1PPP/RNBQKBNR w KQkq g6 99 2",
        selectedMove = UciMove("d1h5"),
        expectations = listOf(
            expectation(
                "checkmate", drawlessRules(
                    fiftyMove = FiftyMovePolicy.COMPLETING_PLAYER_LOSES,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.WHITE, EndReason.CHECKMATE), OrdinaryChessContrast.PRECEDENCE_GUARD,
                RulesAcceptanceProbe.PRECEDENCE_TERMINAL_OVER_LOWER_POLICIES,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "bare-king-over-dead-white",
        mover = Side.WHITE,
        initialFen = "4k3/8/8/8/3b4/4K3/7b/8 w - - 0 1",
        selectedMove = UciMove("e3d4"),
        expectations = listOf(
            expectation(
                "bare-first", drawlessRules(
                    deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
                    fiftyMove = FiftyMovePolicy.DISABLED,
                ), win(Side.BLACK, EndReason.BARE_KING),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.PRECEDENCE_BARE_KING_OVER_DEAD_POSITION,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "bare-king-over-dead-black",
        mover = Side.BLACK,
        initialFen = "8/7B/4k3/3B4/8/8/8/4K3 b - - 0 1",
        selectedMove = UciMove("e6d5"),
        expectations = listOf(
            expectation(
                "bare-first", drawlessRules(
                    deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
                    fiftyMove = FiftyMovePolicy.DISABLED,
                ), win(Side.WHITE, EndReason.BARE_KING),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.PRECEDENCE_BARE_KING_OVER_DEAD_POSITION,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "bare-king-over-fifty-white",
        mover = Side.WHITE,
        initialFen = "4k2r/8/8/8/8/8/8/4K3 w - - 99 1",
        selectedMove = UciMove("e1e2"),
        expectations = listOf(
            expectation(
                "bare-first", drawlessRules(fiftyMove = FiftyMovePolicy.FORCED_MOVE_EXCEPTION),
                win(Side.BLACK, EndReason.BARE_KING), OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.PRECEDENCE_BARE_KING_OVER_FIFTY_MOVE,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "bare-king-over-fifty-black",
        mover = Side.BLACK,
        initialFen = "4k3/8/8/8/8/8/8/4K2R b - - 99 1",
        selectedMove = UciMove("e8e7"),
        expectations = listOf(
            expectation(
                "bare-first", drawlessRules(fiftyMove = FiftyMovePolicy.FORCED_MOVE_EXCEPTION),
                win(Side.WHITE, EndReason.BARE_KING), OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.PRECEDENCE_BARE_KING_OVER_FIFTY_MOVE,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "dead-position-over-fifty-white",
        mover = Side.WHITE,
        initialFen = "4k3/8/8/8/8/8/4B3/4K3 w - - 99 1",
        selectedMove = UciMove("e2f3"),
        expectations = listOf(
            expectation(
                "dead-first", drawlessRules(
                    fiftyMove = FiftyMovePolicy.COMPLETING_PLAYER_LOSES,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.WHITE, EndReason.DEAD_POSITION_MATERIAL),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.PRECEDENCE_DEAD_POSITION_OVER_FIFTY_MOVE,
            ),
        ),
    ),
    RulesAcceptanceScenario(
        id = "dead-position-over-fifty-black",
        mover = Side.BLACK,
        initialFen = "4k3/4b3/8/8/8/8/8/4K3 b - - 99 1",
        selectedMove = UciMove("e7f6"),
        expectations = listOf(
            expectation(
                "dead-first", drawlessRules(
                    fiftyMove = FiftyMovePolicy.COMPLETING_PLAYER_LOSES,
                    bareKing = BareKingPolicy.CONTINUE,
                ), win(Side.BLACK, EndReason.DEAD_POSITION_MATERIAL),
                OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
                RulesAcceptanceProbe.PRECEDENCE_DEAD_POSITION_OVER_FIFTY_MOVE,
            ),
        ),
    ),
)

private fun fiftyMoveExpectations(mover: Side): List<RulesAcceptanceExpectation> {
    val opponent = mover.opposite()
    return listOf(
        expectation(
            "disabled", drawlessRules(
                fiftyMove = FiftyMovePolicy.DISABLED, bareKing = BareKingPolicy.CONTINUE,
            ), null, OrdinaryChessContrast.POLICY_CONTROL,
            RulesAcceptanceProbe.FIFTY_MOVE_DISABLED,
        ),
        expectation(
            "completing-loses", drawlessRules(
                fiftyMove = FiftyMovePolicy.COMPLETING_PLAYER_LOSES,
                bareKing = BareKingPolicy.CONTINUE,
            ), win(opponent, EndReason.FIFTY_MOVE_LIMIT),
            OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
            RulesAcceptanceProbe.FIFTY_MOVE_COMPLETING_PLAYER_LOSES,
        ),
        expectation(
            "forced-policy-avoidable", drawlessRules(
                fiftyMove = FiftyMovePolicy.FORCED_MOVE_EXCEPTION,
                bareKing = BareKingPolicy.CONTINUE,
            ), win(opponent, EndReason.FIFTY_MOVE_LIMIT),
            OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
            RulesAcceptanceProbe.FIFTY_MOVE_FORCED_AVOIDABLE,
        ),
        expectation(
            "material", drawlessRules(
                fiftyMove = FiftyMovePolicy.MATERIAL_VICTORY,
                bareKing = BareKingPolicy.CONTINUE,
            ), win(mover, EndReason.FIFTY_MOVE_LIMIT),
            OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
            RulesAcceptanceProbe.FIFTY_MOVE_MATERIAL,
        ),
    )
}

private fun forcedFiftyMoveExpectations(mover: Side): List<RulesAcceptanceExpectation> {
    val opponent = mover.opposite()
    return listOf(
        expectation(
            "disabled", drawlessRules(
                fiftyMove = FiftyMovePolicy.DISABLED, bareKing = BareKingPolicy.CONTINUE,
            ), null, OrdinaryChessContrast.POLICY_CONTROL,
            RulesAcceptanceProbe.FIFTY_MOVE_DISABLED,
        ),
        expectation(
            "completing-loses", drawlessRules(
                fiftyMove = FiftyMovePolicy.COMPLETING_PLAYER_LOSES,
                bareKing = BareKingPolicy.CONTINUE,
            ), win(opponent, EndReason.FIFTY_MOVE_LIMIT),
            OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
            RulesAcceptanceProbe.FIFTY_MOVE_COMPLETING_PLAYER_LOSES,
        ),
        expectation(
            "forced-exception", drawlessRules(
                fiftyMove = FiftyMovePolicy.FORCED_MOVE_EXCEPTION,
                bareKing = BareKingPolicy.CONTINUE,
            ), win(mover, EndReason.FIFTY_MOVE_LIMIT),
            OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
            RulesAcceptanceProbe.FIFTY_MOVE_FORCED_EXCEPTION,
        ),
        expectation(
            "material-tie-forced", drawlessRules(
                fiftyMove = FiftyMovePolicy.MATERIAL_VICTORY,
                bareKing = BareKingPolicy.CONTINUE,
            ), win(mover, EndReason.FIFTY_MOVE_LIMIT),
            OrdinaryChessContrast.TRUE_POLICY_DISCRIMINATOR,
            RulesAcceptanceProbe.FIFTY_MOVE_MATERIAL,
        ),
    )
}

internal data class RulesPrecedenceFactsFixture(
    val id: String,
    val mover: Side,
    val rules: RulesContractV1,
    val facts: PositionFacts,
    val expectedOutcome: GameOutcome,
    val probe: RulesAcceptanceProbe,
)

private fun collisionFacts(
    mover: Side,
    legalMoves: Int,
    inCheck: Boolean,
    occurrences: Int,
    repetitionAvoiding: Int,
    material: MaterialScore,
) = PositionFacts(
    mover = mover,
    legalMovesAfter = legalMoves,
    sideToMoveInCheck = inCheck,
    positionOccurrenceCount = occurrences,
    repetitionAvoidingAlternativesBeforeMove = repetitionAvoiding,
    halfmoveClockAfter = 100,
    fiftyMoveAvoidingAlternativesBeforeMove = 1,
    deadPositionAfter = true,
    moveWasCapture = true,
    materialAfter = material,
    lastCaptureBy = mover,
)

/**
 * Some precedence collisions cannot arise in a valid replay: a repeated checkmate/dead position
 * would have ended on its first arrival. These fact-level fixtures protect the versioned ordering
 * without pretending that an unreachable move history is a native-search position.
 */
internal val rulesPrecedenceFactsFixtures: List<RulesPrecedenceFactsFixture> = Side.entries.flatMap { mover ->
    val moverMaterial = if (mover == Side.WHITE) MaterialScore(3, 0) else MaterialScore(0, 3)
    listOf(
        RulesPrecedenceFactsFixture(
            id = "checkmate-over-repetition-${mover.name.lowercase()}",
            mover = mover,
            rules = drawlessRules(),
            facts = collisionFacts(mover, 0, true, 3, 1, moverMaterial),
            expectedOutcome = win(mover, EndReason.CHECKMATE),
            probe = RulesAcceptanceProbe.PRECEDENCE_TERMINAL_OVER_REPETITION,
        ),
        RulesPrecedenceFactsFixture(
            id = "stalemate-over-repetition-${mover.name.lowercase()}",
            mover = mover,
            rules = drawlessRules(),
            facts = collisionFacts(mover, 0, false, 3, 1, moverMaterial),
            expectedOutcome = win(mover, EndReason.STALEMATE),
            probe = RulesAcceptanceProbe.PRECEDENCE_TERMINAL_OVER_REPETITION,
        ),
        RulesPrecedenceFactsFixture(
            id = "repetition-over-bare-dead-${mover.name.lowercase()}",
            mover = mover,
            rules = drawlessRules(deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY),
            facts = collisionFacts(mover, 1, false, 3, 1, moverMaterial),
            expectedOutcome = win(mover.opposite(), EndReason.REPETITION),
            probe = RulesAcceptanceProbe.PRECEDENCE_REPETITION_OVER_LOWER_POLICIES,
        ),
    )
}
