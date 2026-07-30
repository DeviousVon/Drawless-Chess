package com.drawlesschess.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drawlesschess.core.BareKingPolicy
import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.engine.UciSessionPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real-library acceptance: packaged asset -> production JNI -> native RulesContractV1 search. */
@RunWith(AndroidJUnit4::class)
class AndroidFairyEngineInstrumentedTest {
    @Test
    fun forcedRepetitionSearchClosesAndRestartsSequentially() {
        val factory = factory()
        factory.create().close()

        repeat(2) { index ->
            factory.create().use { engine ->
                val response = analyze(
                    engine, "forced-repetition-$index", FORCED_REPETITION_FEN,
                    FORCED_REPETITION_HISTORY, RulesContractV1.drawless(),
                )
                assertEquals(UciMove("h8g8"), response.bestMove)
                assertEquals(1, response.variations.first().mateIn)
                assertNativeV2Identity(response)
            }
        }
    }

    @Test
    fun nativePatchV2AppliesBareKingPolicyForBothMovers() = withEngine { engine ->
        BARE_KING_SCENARIOS.forEach { scenario ->
            val loses = drawlessRules(
                fiftyMove = FiftyMovePolicy.DISABLED,
                bareKing = BareKingPolicy.BARE_KING_LOSES,
            )
            assertMoveMate(
                analyzeScenario(engine, scenario, "bare-loses", loses),
                scenario.selectedMove,
                1,
            )
            assertMoveCentipawn(
                analyzeScenario(engine, scenario, "bare-continues", loses.copy(
                    bareKing = BareKingPolicy.CONTINUE,
                )),
                scenario.selectedMove,
            )
        }
    }

    @Test
    fun nativePatchV2ChangesDeadPositionWinnerForBothMovers() = withEngine { engine ->
        DEAD_POSITION_SCENARIOS.forEach { scenario ->
            val material = drawlessRules(
                deadPosition = DeadPositionPolicy.MATERIAL_VICTORY,
                fiftyMove = FiftyMovePolicy.DISABLED,
                bareKing = BareKingPolicy.CONTINUE,
            )
            assertMoveMate(
                analyzeScenario(engine, scenario, "dead-material", material),
                scenario.selectedMove,
                -1,
            )
            assertMoveMate(
                analyzeScenario(engine, scenario, "dead-final-capture", material.copy(
                    deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
                )),
                scenario.selectedMove,
                1,
            )
        }

        QUIET_DEAD_PROMOTION_SCENARIOS.forEach { scenario ->
            val position = ChessAdapter.replay(scenario.initialFen, scenario.moves)
            val transition = ChessAdapter.transition(position, scenario.selectedMove)
            assertTrue("${scenario.id}: underpromotion must create known-dead", transition.deadPositionAfter)
            assertFalse("${scenario.id}: underpromotion fallback must be non-capturing", transition.moveWasCapture)
            assertMoveMate(
                analyzeScenario(
                    engine,
                    scenario,
                    "dead-final-capture-quiet-promotion",
                    drawlessRules(
                        deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
                        fiftyMove = FiftyMovePolicy.DISABLED,
                        bareKing = BareKingPolicy.CONTINUE,
                    ),
                ),
                scenario.selectedMove,
                1,
            )
        }
    }

    @Test
    fun nativePatchV2AppliesEveryFiftyMovePolicyAndTwoPlyBoundaryForBothMovers() =
        withEngine { engine ->
            AVOIDABLE_FIFTY_SCENARIOS.forEach { scenario ->
                listOf(
                    FiftyMovePolicy.DISABLED to null,
                    FiftyMovePolicy.COMPLETING_PLAYER_LOSES to -1,
                    FiftyMovePolicy.FORCED_MOVE_EXCEPTION to -1,
                    FiftyMovePolicy.MATERIAL_VICTORY to 1,
                ).forEach { (policy, expectedMate) ->
                    val response = analyzeScenario(
                        engine,
                        scenario,
                        "fifty-avoidable-${policy.name.lowercase()}",
                        drawlessRules(policy, BareKingPolicy.CONTINUE),
                    )
                    if (expectedMate == null) assertMoveCentipawn(response, scenario.selectedMove)
                    else assertMoveMate(response, scenario.selectedMove, expectedMate)
                }
            }

            FORCED_FIFTY_SCENARIOS.forEach { scenario ->
                listOf(
                    FiftyMovePolicy.DISABLED to null,
                    FiftyMovePolicy.COMPLETING_PLAYER_LOSES to -1,
                    FiftyMovePolicy.FORCED_MOVE_EXCEPTION to 1,
                    FiftyMovePolicy.MATERIAL_VICTORY to 1,
                ).forEach { (policy, expectedMate) ->
                    val response = analyze(
                        engine, "${scenario.id}-${policy.name.lowercase()}", scenario.initialFen,
                        rules = drawlessRules(policy, BareKingPolicy.CONTINUE),
                    )
                    if (expectedMate == null) assertPrimaryCentipawn(response)
                    else assertPrimaryMate(response, expectedMate)
                }
            }

            TWO_PLY_FIFTY_SCENARIOS.forEach { scenario ->
                assertEquals(
                    "The qsearch boundary fixture must have one legal root move",
                    listOf(scenario.selectedMove),
                    ChessRules.legalUciMoves(ChessAdapter.replay(scenario.initialFen, scenario.moves)),
                )
                val response = analyzeScenario(
                    engine,
                    scenario,
                    "forced-two-ply-boundary",
                    drawlessRules(FiftyMovePolicy.FORCED_MOVE_EXCEPTION, BareKingPolicy.CONTINUE),
                )
                assertMoveMate(response, scenario.selectedMove, -1)
            }
        }

    @Test
    fun nativePatchV2CarriesLastCaptureHistoryIntoMaterialTieBreakForBothMovers() =
        withEngine { engine ->
            LAST_CAPTURE_SCENARIOS.forEach { scenario ->
                val rules = drawlessRules(
                    FiftyMovePolicy.MATERIAL_VICTORY,
                    BareKingPolicy.CONTINUE,
                )
                val current = ChessAdapter.replay(scenario.initialFen, scenario.moves)
                assertEquals("Fixture must stop at the native boundary", 99, current.halfmoveClock)

                assertPrimaryMate(
                    analyze(
                        engine, "${scenario.id}-with-history", scenario.initialFen,
                        scenario.moves, rules,
                    ),
                    1,
                )
                val withoutHistory = analyze(
                    engine, "${scenario.id}-without-history", current.fen(), rules = rules,
                )
                assertPrimaryCentipawn(withoutHistory)
                assertFalse(
                    "Without capture history the best move must reset the halfmove clock",
                    ChessRules.apply(current, withoutHistory.bestMove).halfmoveClock >= 100,
                )
            }
        }

    @Test
    fun nativePatchV2GivesMateAndStalematePrecedenceForBothMovers() = withEngine { engine ->
        CHECKMATE_PRECEDENCE_SCENARIOS.forEach { scenario ->
            val response = analyze(
                engine,
                "${scenario.id}-checkmate-over-fifty",
                scenario.initialFen,
                rules = drawlessRules(
                    FiftyMovePolicy.COMPLETING_PLAYER_LOSES,
                    BareKingPolicy.CONTINUE,
                ),
            )
            assertEquals(scenario.selectedMove, response.bestMove)
            assertPrimaryMate(response, 1)
        }

        STALEMATE_PRECEDENCE_SCENARIOS.forEach { scenario ->
            val drawless = drawlessRules(
                FiftyMovePolicy.COMPLETING_PLAYER_LOSES,
                BareKingPolicy.CONTINUE,
            )
            val escape = RulesContractV1.escape(
                DeadPositionPolicy.MATERIAL_VICTORY,
                FiftyMovePolicy.COMPLETING_PLAYER_LOSES,
            ).copy(bareKing = BareKingPolicy.CONTINUE)
            assertMoveMate(
                analyzeScenario(engine, scenario, "drawless-stalemate", drawless),
                scenario.selectedMove,
                1,
            )
            assertMoveMate(
                analyzeScenario(engine, scenario, "escape-stalemate", escape),
                scenario.selectedMove,
                -1,
            )
        }
    }

    private fun factory(): AndroidFairyEngineFactory {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AndroidFairyEngineFactory(
            context,
            uciPolicy = UciSessionPolicy(
                handshakeTimeoutMillis = ENGINE_TIMEOUT_MILLIS,
                synchronizationTimeoutMillis = ENGINE_TIMEOUT_MILLIS,
                searchGraceMillis = SEARCH_GRACE_MILLIS,
            ),
        )
    }

    private fun withEngine(block: (AndroidFairyEngineSession) -> Unit) {
        factory().create().use(block)
    }

    private fun analyzeScenario(
        engine: AndroidFairyEngineSession,
        scenario: SelectedMoveScenario,
        suffix: String,
        rules: RulesContractV1,
    ): EngineResponse {
        val position = ChessAdapter.replay(scenario.initialFen, scenario.moves)
        val legalMoves = ChessRules.legalUciMoves(position)
        assertTrue("${scenario.id}: selected move is illegal", scenario.selectedMove in legalMoves)
        assertTrue("${scenario.id}: fixture exceeds MultiPV 10", legalMoves.size <= 10)
        return analyze(
            engine, "${scenario.id}-$suffix", scenario.initialFen, scenario.moves, rules, 10,
        )
    }

    private fun analyze(
        engine: AndroidFairyEngineSession,
        id: String,
        initialFen: String,
        moves: List<UciMove> = emptyList(),
        rules: RulesContractV1,
        multiPv: Int = 1,
    ): EngineResponse {
        val response = AtomicReference<EngineResponse?>()
        val failure = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        val request = EngineRequest(
            requestId = "android-native-v2-$id",
            gameId = "android-native-v2-game-$id",
            positionId = "android-native-v2-position-$id",
            initialFen = initialFen,
            moves = moves,
            rules = rules,
            strength = EngineStrength.SkillLevel(20),
            limits = EngineLimits(SEARCH_MILLIS, multiPv),
        )
        engine.analyze(request) { result ->
            result.fold(response::set, failure::set)
            completed.countDown()
        }
        assertTrue(
            "Engine callback timed out for $id; protocol=${engine.protocolState}, " +
                "transport=${engine.transportState}",
            completed.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        failure.get()?.let { throw AssertionError("Native analysis failed for $id", it) }
        assertNotNull("Native analysis completed without a response for $id", response.get())
        return response.get()!!.also(::assertNativeV2Identity)
    }

    private fun assertMoveMate(response: EngineResponse, move: UciMove, expectedMate: Int) {
        val variation = response.variations.singleOrNull { it.moves.first() == move }
        assertNotNull("Missing scored variation for ${move.value}: ${response.variations}", variation)
        assertTrue("${move.value} used compatibility evidence", variation!!.evidenceAvailable)
        assertEquals("Unexpected native score for ${move.value}", expectedMate, variation.mateIn)
    }

    private fun assertMoveCentipawn(response: EngineResponse, move: UciMove) {
        val variation = response.variations.singleOrNull { it.moves.first() == move }
        assertNotNull("Missing scored variation for ${move.value}: ${response.variations}", variation)
        assertTrue("${move.value} used compatibility evidence", variation!!.evidenceAvailable)
        assertNull("${move.value} unexpectedly ended the game", variation.mateIn)
        assertNotNull("${move.value} lacks a centipawn score", variation.scoreCentipawns)
    }

    private fun assertPrimaryMate(response: EngineResponse, expectedMate: Int) {
        assertTrue(response.variations.first().evidenceAvailable)
        assertEquals(expectedMate, response.variations.first().mateIn)
    }

    private fun assertPrimaryCentipawn(response: EngineResponse) {
        val primary = response.variations.first()
        assertTrue(primary.evidenceAvailable)
        assertNull("Position unexpectedly ended", primary.mateIn)
        assertNotNull("Position lacks a centipawn score", primary.scoreCentipawns)
    }

    private fun assertNativeV2Identity(response: EngineResponse) {
        assertEquals(2, BuildConfig.DRAWLESS_PATCH_VERSION)
        assertEquals(BuildConfig.DRAWLESS_PATCH_VERSION, response.engine.drawlessPatch)
        assertTrue(
            response.engine.build.contains(
                "-tree-${BuildConfig.FAIRY_PATCHED_TREE.take(12)}-patch-2-",
            ),
        )
    }

    private data class SelectedMoveScenario(
        val id: String,
        val initialFen: String,
        val selectedMove: UciMove,
        val moves: List<UciMove> = emptyList(),
    )

    private data class PositionScenario(val id: String, val initialFen: String)

    private data class HistoryScenario(
        val id: String,
        val initialFen: String,
        val moves: List<UciMove>,
    )

    private companion object {
        const val SEARCH_MILLIS = 500L
        const val SEARCH_GRACE_MILLIS = 5_000L
        const val ENGINE_TIMEOUT_MILLIS = 30_000L
        const val CALLBACK_TIMEOUT_SECONDS = 45L

        // The history reaches both a third occurrence and halfmove 100. Material favors White,
        // so Black's mate +1 also proves repetition precedence over the lower 50-move policy.
        const val FORCED_REPETITION_FEN = "6k1/7p/5Q2/8/8/8/8/6K1 w - - 92 1"
        val FORCED_REPETITION_HISTORY = moves("f6f7 g8h8 f7f6 h8g8 f6f7 g8h8 f7f6")

        val BARE_KING_SCENARIOS = listOf(
            SelectedMoveScenario(
                "bare-black-after-white-capture",
                "4k3/8/8/8/3r4/4K3/P7/8 w - - 0 1",
                UciMove("e3d4"),
            ),
            SelectedMoveScenario(
                "bare-white-after-black-capture",
                "8/7p/3k4/4R3/8/8/8/3K4 b - - 0 1",
                UciMove("d6e5"),
            ),
        )

        val DEAD_POSITION_SCENARIOS = listOf(
            SelectedMoveScenario(
                "dead-after-white-capture",
                "4k3/8/8/8/3r4/4K3/7b/8 w - - 0 1",
                UciMove("e3d4"),
            ),
            SelectedMoveScenario(
                "dead-after-black-capture",
                "8/B7/3k4/4R3/8/8/8/3K4 b - - 0 1",
                UciMove("d6e5"),
            ),
        )

        val QUIET_DEAD_PROMOTION_SCENARIOS = listOf(
            SelectedMoveScenario(
                "dead-after-white-quiet-underpromotion",
                "7k/1P6/2K5/8/8/b7/8/8 w - - 0 1",
                UciMove("b7b8b"),
            ),
            SelectedMoveScenario(
                "dead-after-black-quiet-underpromotion",
                "8/8/7B/8/8/5k2/6p1/K7 b - - 0 1",
                UciMove("g2g1b"),
            ),
            SelectedMoveScenario(
                "dead-after-white-quiet-knight-underpromotion",
                "7k/1P6/K7/8/8/8/8/8 w - - 0 1",
                UciMove("b7b8n"),
            ),
            SelectedMoveScenario(
                "dead-after-black-quiet-knight-underpromotion",
                "8/8/8/8/8/7k/6p1/K7 b - - 0 1",
                UciMove("g2g1n"),
            ),
        )

        val AVOIDABLE_FIFTY_SCENARIOS = listOf(
            SelectedMoveScenario(
                "fifty-avoidable-white",
                "4k3/7p/8/8/8/8/P7/N3K3 w - - 99 1",
                UciMove("a1b3"),
            ),
            SelectedMoveScenario(
                "fifty-avoidable-black",
                "3k3n/7p/8/8/8/8/P7/3K4 b - - 99 1",
                UciMove("h8g6"),
            ),
        )

        val FORCED_FIFTY_SCENARIOS = listOf(
            PositionScenario("fifty-forced-white", "4k2r/8/8/8/8/8/8/R3K3 w - - 99 1"),
            PositionScenario("fifty-forced-black", "4k2r/8/8/8/8/8/8/R3K3 b - - 99 1"),
        )

        val TWO_PLY_FIFTY_SCENARIOS = listOf(
            SelectedMoveScenario(
                "fifty-two-ply-white",
                "6k1/8/8/8/R7/7q/8/7K w - - 98 1",
                UciMove("h1g1"),
            ),
            SelectedMoveScenario(
                "fifty-two-ply-black",
                "k7/8/Q7/7r/8/8/8/1K6 b - - 98 1",
                UciMove("a8b8"),
            ),
        )

        val CHECKMATE_PRECEDENCE_SCENARIOS = listOf(
            SelectedMoveScenario(
                "checkmate-black",
                "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 99 2",
                UciMove("d8h4"),
            ),
            SelectedMoveScenario(
                "checkmate-white",
                "rnbqkbnr/ppppp2p/5p2/6p1/4P3/8/PPPP1PPP/RNBQKBNR w KQkq g6 99 2",
                UciMove("d1h5"),
            ),
        )

        val STALEMATE_PRECEDENCE_SCENARIOS = listOf(
            SelectedMoveScenario(
                "stalemate-black",
                "k1K5/n7/8/8/8/8/8/R7 w - - 99 1",
                UciMove("c8c7"),
            ),
            SelectedMoveScenario(
                "stalemate-white",
                "7r/8/8/8/8/8/7N/5k1K b - - 99 1",
                UciMove("f1f2"),
            ),
        )

        val LAST_CAPTURE_SCENARIOS = listOf(
            HistoryScenario(
                "last-capture-white",
                "r3k3/7p/8/8/3p4/4K3/7P/R7 w - - 0 1",
                moves(
                    """
                    e3d4 a8a2 a1b1 a2a1 b1b2 a1a2 b2b3 a2a1 b3a3 a1a2 a3a4 a2a1
                    a4a2 a1b1 a2a1 b1b2 a1a2 b2b3 a2a1 b3a3 a1a2 a3a4 d4c3 a4a3
                    c3b2 a3a4 a2a1 a4a2 b2b1 a2a3 a1a2 a3a4 a2a1 a4a2 b1c1 a2a3
                    a1a2 a3a4 a2a1 a4a2 a1b1 a2a1 c1b2 a1a2 b2b3 a2a1 b1b2 a1a2
                    b2c2 a2a1 b3b2 a1a2 b2b1 a2a1 b1b2 a1a3 b2b1 a3a2 b1c1 a2a1
                    c1d2 a1a2 c2b2 a2a1 b2a2 a1b1 a2a1 b1b2 d2c1 b2b1 c1c2 b1b2
                    c2c3 b2a2 a1b1 a2a1 b1b2 a1a2 b2b3 a2a1 b3a3 a1a2 a3a4 a2a1
                    a4a2 a1b1 a2a1 b1b2 a1a2 b2b3 c3c2 b3a3 a2a1 a3a2 c2b3 a2a3
                    b3b2 a3a4 a1a2 a4a3
                    """,
                ),
            ),
            HistoryScenario(
                "last-capture-black",
                "r7/7p/4k3/3P4/8/8/7P/R3K3 b - - 0 1",
                moves(
                    """
                    e6d5 a1a2 a8a3 a2a1 a3a2 a1b1 a2a1 b1c1 a1a2 c1a1 a2a3 a1a2
                    a3a4 a2a1 a4a5 a1a2 a5a6 a2a1 a6a4 a1a2 a4a5 a2a1 a5a6 a1a2
                    a6a7 a2a1 a7b7 a1a2 b7b1 e1d2 b1a1 a2a3 a1a2 d2c1 a2a1 c1b2
                    a1a2 b2b1 a2a1 b1c2 a1a2 c2b3 a2a1 a3a2 a1b1 a2b2 b1a1 b2b1
                    a1a2 b1a1 a2a3 b3b2 a3a2 b2b1 a2a3 a1a2 a3a4 a2a1 a4a2 b1c1
                    a2a3 a1a2 a3a4 a2a1 a4a2 a1b1 a2a1 c1b2 a1a2 b2b3 a2a1 b1b2
                    a1a2 b2c2 a2a1 b3b2 a1a2 b2b1 a2a1 b1b2 a1a3 b2b1 a3a2 b1c1
                    a2a1 c1d2 a1a2 c2b2 a2a1 b2a2 a1b1 a2a1 b1b2 d2c1 b2b1 c1c2
                    b1b2 c2c3 b2a2 a1b1
                    """,
                ),
            ),
        )

        private fun drawlessRules(
            fiftyMove: FiftyMovePolicy,
            bareKing: BareKingPolicy,
        ) = drawlessRules(DeadPositionPolicy.MATERIAL_VICTORY, fiftyMove, bareKing)

        private fun drawlessRules(
            deadPosition: DeadPositionPolicy = DeadPositionPolicy.MATERIAL_VICTORY,
            fiftyMove: FiftyMovePolicy = FiftyMovePolicy.MATERIAL_VICTORY,
            bareKing: BareKingPolicy = BareKingPolicy.BARE_KING_LOSES,
        ) = RulesContractV1.drawless(deadPosition, fiftyMove).copy(bareKing = bareKing)

        private fun moves(encoded: String): List<UciMove> =
            encoded.trim().split(Regex("\\s+")).filter(String::isNotBlank).map(::UciMove)
    }
}
