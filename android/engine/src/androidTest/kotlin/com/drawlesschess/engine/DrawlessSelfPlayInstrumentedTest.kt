package com.drawlesschess.engine

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.GameSession
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey
import com.drawlesschess.core.chess.SanNotation
import com.drawlesschess.core.engine.UciSessionPolicy
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic bot-v-bot play through the packaged JNI engine and the exact app adjudicator.
 *
 * Instrumentation arguments:
 * - selfPlayGames (1..40, default 2)
 * - selfPlayOpeningOffset (0..7, default 0)
 * - selfPlayMoveTimeMillis (50..5000, default 350)
 * - selfPlayMaxPlies (20..500, default 300)
 * - selfPlayLane: app or ceiling
 * - selfPlayWhiteElo / selfPlayBlackElo (500..2850; app lane only)
 * - selfPlayRules: preset-deadPolicy-fiftyPolicy, for example drawless-material-off
 * - selfPlayPairAsymmetricStrengths (true/false, default true)
 *
 * A ply cap is recorded as inconclusive, never converted into a game result. Any illegal move,
 * identity mismatch, callback failure, timeout, or native failure fails the test immediately.
 */
@RunWith(AndroidJUnit4::class)
class DrawlessSelfPlayInstrumentedTest {
    @Test
    fun botsPlayConfiguredGamesThroughProductionEngineAndRules() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val arguments = InstrumentationRegistry.getArguments()
        val config = SelfPlayConfig.from(arguments)
        val factory = AndroidFairyEngineFactory(
            context = context,
            uciPolicy = UciSessionPolicy(
                handshakeTimeoutMillis = ENGINE_TIMEOUT_MILLIS,
                synchronizationTimeoutMillis = ENGINE_TIMEOUT_MILLIS,
                searchGraceMillis = SEARCH_GRACE_MILLIS,
            ),
        )
        val report = createReport(context, config)
        Log.i(
            LOG_TAG,
            JSONObject()
                .put("event", "self_play_started")
                .put("report_path", report.absolutePath)
                .toString(),
        )

        val results = mutableListOf<SelfPlayGameResult>()
        repeat(config.games) { index ->
            val openingIndex = config.openingIndex(index)
            val result = playGame(factory, config, openingIndex, index)
            results += result
            report.appendText(result.fullJson().toString() + "\n", Charsets.UTF_8)
            Log.i(LOG_TAG, result.summaryJson().toString())
        }
        val completed = results.count { it.outcomeReason != null }
        val capped = results.count(SelfPlayGameResult::capped)
        val summary = JSONObject()
            .put("event", "self_play_summary")
            .put("games", results.size)
            .put("completed", completed)
            .put("capped_inconclusive", capped)
            .put("end_reasons", endReasonCounts(results))
            .put("white_wins", results.count { it.winner == Side.WHITE })
            .put("black_wins", results.count { it.winner == Side.BLACK })
            .put("report_path", report.absolutePath)
            .put(
                "interpretation",
                "Stalemate/repetition searches are variant-aware; dead-position and " +
                    "50-move policies are app-adjudicated after policy-blind engine search.",
            )
        report.appendText(summary.toString() + "\n", Charsets.UTF_8)
        Log.i(LOG_TAG, summary.toString())

        assertTrue("The configured self-play run produced no games", results.isNotEmpty())
    }

    private fun playGame(
        factory: AndroidFairyEngineFactory,
        config: SelfPlayConfig,
        openingIndex: Int,
        gameIndex: Int,
    ): SelfPlayGameResult {
        val opening = OPENINGS[openingIndex]
        val gameId = "selfplay-${config.rulesLabel}-$openingIndex-$gameIndex"
        var position = ChessPosition.fromFen(ChessPosition.START_FEN)
        var game = GameSession.newGame(
            gameId = gameId,
            rules = config.rules,
            initialPositionKey = RepetitionKey.of(position),
            sideToMove = position.sideToMove,
        )
        val uciMoves = mutableListOf<String>()
        val sanMoves = mutableListOf<String>()

        opening.moves.forEach { encoded ->
            val move = UciMove(encoded)
            sanMoves += SanNotation.format(position, move)
            val transition = ChessAdapter.transition(position, move)
            game = game.apply(transition)
            position = ChessRules.apply(position, move)
            uciMoves += encoded
            check(game.outcome == null) { "Opening ${opening.name} ended before self-play began" }
        }

        val latencies = mutableListOf<Long>()
        var maximumDepth = 0
        var maximumNodes = 0L
        var engineBuild: String? = null
        val engine = factory.create()
        try {
            while (game.outcome == null && game.moves.size < config.maxPlies) {
                val request = EngineRequest(
                    requestId = "$gameId-ply-${game.moves.size + 1}",
                    gameId = gameId,
                    positionId = game.positionId,
                    initialFen = ChessPosition.START_FEN,
                    moves = game.moves.map { it.move },
                    rules = config.rules,
                    strength = config.strengthFor(game.sideToMove, gameIndex),
                    limits = EngineLimits(moveTimeMillis = config.moveTimeMillis),
                )
                val timed = analyze(engine, request, config.callbackTimeoutMillis)
                check(timed.response.matches(request)) {
                    "Engine response identity does not match ${request.requestId}"
                }
                val move = timed.response.bestMove
                val san = SanNotation.format(position, move)
                val transition = ChessAdapter.transition(position, move)
                game = game.apply(transition)
                position = ChessRules.apply(position, move)
                uciMoves += move.value
                sanMoves += san
                latencies += timed.elapsedMillis
                maximumDepth = maxOf(maximumDepth, timed.response.depth)
                maximumNodes = maxOf(maximumNodes, timed.response.nodes)
                engineBuild = timed.response.engine.build
            }
        } finally {
            engine.close()
        }

        val outcome = game.outcome
        val facts = game.adjudicationFacts
        return SelfPlayGameResult(
            gameId = gameId,
            opening = opening.name,
            openingIndex = openingIndex,
            whiteStrength = config.strengthLabel(Side.WHITE, gameIndex),
            blackStrength = config.strengthLabel(Side.BLACK, gameIndex),
            uciMoves = uciMoves,
            sanMoves = sanMoves,
            plies = game.moves.size,
            capped = outcome == null,
            outcomeReason = outcome?.reason,
            winner = outcome?.winner,
            lastFen = position.fen(),
            medianLatencyMillis = percentile(latencies, 0.50),
            p95LatencyMillis = percentile(latencies, 0.95),
            maximumDepth = maximumDepth,
            maximumNodes = maximumNodes,
            engineBuild = engineBuild,
            repetitionAvoidingMoves = facts?.repetitionAvoidingAlternativesBeforeMove,
            fiftyMoveAvoidingMoves = facts?.fiftyMoveAvoidingAlternativesBeforeMove,
            materialWhite = facts?.materialAfter?.white,
            materialBlack = facts?.materialAfter?.black,
        )
    }

    private fun analyze(
        engine: AndroidFairyEngineSession,
        request: EngineRequest,
        callbackTimeoutMillis: Long,
    ): TimedResponse {
        val response = AtomicReference<EngineResponse?>()
        val failure = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        val started = SystemClock.elapsedRealtime()
        val cancellation = engine.analyze(request) { result ->
            result.fold(response::set, failure::set)
            completed.countDown()
        }
        if (!completed.await(callbackTimeoutMillis, TimeUnit.MILLISECONDS)) {
            cancellation.cancel()
            error(
                "Engine callback timed out for ${request.requestId}; " +
                    "protocol=${engine.protocolState}, transport=${engine.transportState}",
            )
        }
        failure.get()?.let { throw AssertionError("Engine failed for ${request.requestId}", it) }
        return TimedResponse(
            response = checkNotNull(response.get()) {
                "Engine completed ${request.requestId} without a response"
            },
            elapsedMillis = SystemClock.elapsedRealtime() - started,
        )
    }

    private fun createReport(
        context: Context,
        config: SelfPlayConfig,
    ): File {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        val startedAtEpochMillis = System.currentTimeMillis()
        val report = File(
            directory,
            "drawless-selfplay-${config.rulesLabel}-$startedAtEpochMillis.jsonl",
        )
        val header = JSONObject()
            .put("event", "self_play_config")
            .put("started_at_epoch_ms", startedAtEpochMillis)
            .put("config", config.toJson())
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())))
            .put("engine_source", JSONObject()
                .put("upstream_revision", BuildConfig.FAIRY_UPSTREAM_REVISION)
                .put("patched_tree", BuildConfig.FAIRY_PATCHED_TREE)
                .put("drawless_patch", BuildConfig.DRAWLESS_PATCH_VERSION)
                .put("bridge_abi", BuildConfig.NATIVE_BRIDGE_ABI_VERSION))
            .put("engine_rule_awareness", JSONObject()
                .put("stalemate", true)
                .put("repetition", true)
                .put("dead_position_policy", false)
                .put("fifty_move_policy", false))
        report.writeText(header.toString() + "\n", Charsets.UTF_8)
        return report
    }

    private fun endReasonCounts(results: List<SelfPlayGameResult>): JSONObject {
        val counts = results.mapNotNull(SelfPlayGameResult::outcomeReason)
            .groupingBy(EndReason::name)
            .eachCount()
        return JSONObject().also { json -> counts.forEach(json::put) }
    }

    private fun percentile(values: List<Long>, percentile: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private data class TimedResponse(
        val response: EngineResponse,
        val elapsedMillis: Long,
    )

    private data class Opening(
        val name: String,
        val moves: List<String>,
    )

    private data class SelfPlayGameResult(
        val gameId: String,
        val opening: String,
        val openingIndex: Int,
        val whiteStrength: String,
        val blackStrength: String,
        val uciMoves: List<String>,
        val sanMoves: List<String>,
        val plies: Int,
        val capped: Boolean,
        val outcomeReason: EndReason?,
        val winner: Side?,
        val lastFen: String,
        val medianLatencyMillis: Long?,
        val p95LatencyMillis: Long?,
        val maximumDepth: Int,
        val maximumNodes: Long,
        val engineBuild: String?,
        val repetitionAvoidingMoves: Int?,
        val fiftyMoveAvoidingMoves: Int?,
        val materialWhite: Int?,
        val materialBlack: Int?,
    ) {
        fun summaryJson(): JSONObject = JSONObject()
            .put("event", "self_play_game")
            .put("game_id", gameId)
            .put("opening", opening)
            .put("opening_index", openingIndex)
            .put("white_strength", whiteStrength)
            .put("black_strength", blackStrength)
            .put("plies", plies)
            .put("capped_inconclusive", capped)
            .putNullable("outcome_reason", outcomeReason?.name)
            .putNullable("winner", winner?.name)
            .putNullable("median_latency_ms", medianLatencyMillis)
            .putNullable("p95_latency_ms", p95LatencyMillis)
            .put("maximum_depth", maximumDepth)
            .put("maximum_nodes", maximumNodes)

        fun fullJson(): JSONObject = summaryJson()
            .put("uci_moves", JSONArray(uciMoves))
            .put("san_moves", JSONArray(sanMoves))
            .put("last_fen", lastFen)
            .putNullable("engine_build", engineBuild)
            .putNullable("repetition_avoiding_moves", repetitionAvoidingMoves)
            .putNullable("fifty_move_avoiding_moves", fiftyMoveAvoidingMoves)
            .putNullable("material_white", materialWhite)
            .putNullable("material_black", materialBlack)
    }

    private data class SelfPlayConfig(
        val games: Int,
        val openingOffset: Int,
        val moveTimeMillis: Long,
        val maxPlies: Int,
        val lane: String,
        val whiteElo: Int,
        val blackElo: Int,
        val pairAsymmetricStrengths: Boolean,
        val rules: RulesContractV1,
        val rulesLabel: String,
    ) {
        // First analysis can consume handshake plus two synchronization windows before search.
        val callbackTimeoutMillis: Long =
            ENGINE_TIMEOUT_MILLIS * 3 + moveTimeMillis + SEARCH_GRACE_MILLIS + 5_000L

        fun strengthFor(side: Side, gameIndex: Int): EngineStrength = if (lane == "ceiling") {
            EngineStrength.SkillLevel(20)
        } else {
            val swap = pairAsymmetricStrengths && whiteElo != blackElo && gameIndex % 2 == 1
            val resolvedSide = if (swap) side.opposite() else side
            EngineStrength.ApproximateElo(if (resolvedSide == Side.WHITE) whiteElo else blackElo)
        }

        fun openingIndex(gameIndex: Int): Int {
            val pairedIndex = if (pairAsymmetricStrengths && whiteElo != blackElo) {
                gameIndex / 2
            } else {
                gameIndex
            }
            return (openingOffset + pairedIndex) % OPENINGS.size
        }

        fun toJson(): JSONObject = JSONObject()
            .put("games", games)
            .put("opening_offset", openingOffset)
            .put("move_time_ms", moveTimeMillis)
            .put("max_plies", maxPlies)
            .put("lane", lane)
            .put("white_strength_game_0", strengthLabel(Side.WHITE, 0))
            .put("black_strength_game_0", strengthLabel(Side.BLACK, 0))
            .put("pair_asymmetric_strengths", pairAsymmetricStrengths)
            .put("rules", rulesLabel)

        fun strengthLabel(side: Side, gameIndex: Int): String =
            when (val strength = strengthFor(side, gameIndex)) {
            is EngineStrength.ApproximateElo -> "elo-${strength.elo}"
            is EngineStrength.SkillLevel -> "skill-${strength.level}"
        }

        companion object {
            fun from(arguments: Bundle): SelfPlayConfig {
                val lane = arguments.string("selfPlayLane", "app").lowercase()
                require(lane in setOf("app", "ceiling")) { "selfPlayLane must be app or ceiling" }
                val rulesLabel = arguments.string("selfPlayRules", "drawless-material-off").lowercase()
                return SelfPlayConfig(
                    games = arguments.int("selfPlayGames", 2, 1..40),
                    openingOffset = arguments.int("selfPlayOpeningOffset", 0, OPENINGS.indices),
                    moveTimeMillis = arguments.long("selfPlayMoveTimeMillis", 350L, 50L..5_000L),
                    maxPlies = arguments.int("selfPlayMaxPlies", 300, 20..500),
                    lane = lane,
                    whiteElo = arguments.int("selfPlayWhiteElo", 2500, 500..2850),
                    blackElo = arguments.int("selfPlayBlackElo", 2500, 500..2850),
                    pairAsymmetricStrengths = arguments.boolean(
                        "selfPlayPairAsymmetricStrengths",
                        true,
                    ),
                    rules = parseRules(rulesLabel),
                    rulesLabel = rulesLabel,
                )
            }

            private fun parseRules(label: String): RulesContractV1 {
                val parts = label.split('-')
                require(parts.size == 3) {
                    "selfPlayRules must be preset-deadPolicy-fiftyPolicy"
                }
                val dead = when (parts[1]) {
                    "material" -> DeadPositionPolicy.MATERIAL_VICTORY
                    "final" -> DeadPositionPolicy.FINAL_CAPTURE_VICTORY
                    else -> error("Unknown self-play dead-position policy ${parts[1]}")
                }
                val fifty = when (parts[2]) {
                    "off" -> FiftyMovePolicy.DISABLED
                    "completing" -> FiftyMovePolicy.COMPLETING_PLAYER_LOSES
                    "forced" -> FiftyMovePolicy.FORCED_MOVE_EXCEPTION
                    "material" -> FiftyMovePolicy.MATERIAL_VICTORY
                    else -> error("Unknown self-play 50-move policy ${parts[2]}")
                }
                return when (parts[0]) {
                    "drawless" -> RulesContractV1.drawless(dead, fifty)
                    "escape" -> RulesContractV1.escape(dead, fifty)
                    else -> error("Unknown self-play preset ${parts[0]}")
                }
            }
        }
    }

    private companion object {
        const val LOG_TAG = "DrawlessSelfPlay"
        const val SEARCH_GRACE_MILLIS = 10_000L
        const val ENGINE_TIMEOUT_MILLIS = 30_000L

        val OPENINGS = listOf(
            Opening("start", emptyList()),
            Opening("open-e-file", listOf("e2e4", "e7e5", "g1f3", "b8c6")),
            Opening("sicilian", listOf("e2e4", "c7c5", "g1f3", "d7d6")),
            Opening("french", listOf("e2e4", "e7e6", "d2d4", "d7d5")),
            Opening("queens-gambit", listOf("d2d4", "d7d5", "c2c4", "e7e6")),
            Opening("indian", listOf("d2d4", "g8f6", "c2c4", "g7g6")),
            Opening("english", listOf("c2c4", "e7e5", "b1c3", "g8f6")),
            Opening("reti", listOf("g1f3", "d7d5", "g2g3", "g8f6")),
        )

        fun Bundle.string(name: String, default: String): String =
            getString(name)?.trim()?.takeIf(String::isNotEmpty) ?: default

        fun Bundle.int(name: String, default: Int, range: IntRange): Int =
            string(name, default.toString()).toInt().also { value ->
                require(value in range) { "$name must be in $range" }
            }

        fun Bundle.long(name: String, default: Long, range: LongRange): Long =
            string(name, default.toString()).toLong().also { value ->
                require(value in range) { "$name must be in $range" }
            }

        fun Bundle.boolean(name: String, default: Boolean): Boolean =
            string(name, default.toString()).toBooleanStrictOrNull()
                ?: error("$name must be true or false")

        fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
            put(name, value ?: JSONObject.NULL)
    }
}
