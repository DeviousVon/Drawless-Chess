package com.drawlesschess.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.engine.UciSessionPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real-library smoke: packaged asset -> JNI -> patched Fairy search -> deterministic restart. */
@RunWith(AndroidJUnit4::class)
class AndroidFairyEngineInstrumentedTest {
    @Test
    fun forcedRepetitionSearchClosesAndRestartsSequentially() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = AndroidFairyEngineFactory(
            context = context,
            uciPolicy = UciSessionPolicy(
                handshakeTimeoutMillis = ENGINE_TIMEOUT_MILLIS,
                synchronizationTimeoutMillis = ENGINE_TIMEOUT_MILLIS,
                searchGraceMillis = SEARCH_GRACE_MILLIS,
            ),
        )

        // Closing before the asynchronous JNI startup has necessarily completed must wait for
        // nativeCreate/nativeClose, or the next process-global Fairy session can be rejected.
        factory.create().close()

        val first = factory.create()
        val firstResponse = try {
            analyze(first, "first")
        } finally {
            first.close()
        }
        assertForcedRepetitionResult(firstResponse)

        // Fairy owns process-global state. This second real session proves close completed the
        // singleton teardown and that initialization can safely reset for a later game.
        val second = factory.create()
        val secondResponse = try {
            analyze(second, "second")
        } finally {
            second.close()
        }
        assertForcedRepetitionResult(secondResponse)
    }

    private fun analyze(
        session: AndroidFairyEngineSession,
        suffix: String,
    ): EngineResponse {
        val response = AtomicReference<EngineResponse?>()
        val failure = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        session.analyze(request(suffix)) { result ->
            result.fold(response::set, failure::set)
            completed.countDown()
        }

        assertTrue(
            "Engine callback timed out; protocol=${session.protocolState}, " +
                "transport=${session.transportState}",
            completed.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        failure.get()?.let { throw AssertionError("Native analysis failed", it) }
        assertNotNull("Native analysis completed without a response", response.get())
        return response.get()!!
    }

    private fun assertForcedRepetitionResult(response: EngineResponse) {
        assertEquals(UciMove(EXPECTED_BEST_MOVE), response.bestMove)
        assertEquals(
            "Forced repetition must evaluate as a one-ply win for the forced mover",
            1,
            response.variations.first().mateIn,
        )
        assertEquals(BuildConfig.DRAWLESS_PATCH_VERSION, response.engine.drawlessPatch)
    }

    private fun request(suffix: String) = EngineRequest(
        requestId = "android-native-smoke-$suffix",
        gameId = "android-native-smoke-game-$suffix",
        positionId = "forced-repetition-$suffix",
        initialFen = FORCED_REPETITION_FEN,
        moves = FORCED_REPETITION_HISTORY.map(::UciMove),
        rules = RulesContractV1.drawless(),
        strength = EngineStrength.SkillLevel(20),
        limits = EngineLimits(moveTimeMillis = SEARCH_MILLIS),
    )

    private companion object {
        const val FORCED_REPETITION_FEN = "6k1/7p/5Q2/8/8/8/8/6K1 w - - 0 1"
        val FORCED_REPETITION_HISTORY = listOf(
            "f6f7",
            "g8h8",
            "f7f6",
            "h8g8",
            "f6f7",
            "g8h8",
            "f7f6",
        )
        const val EXPECTED_BEST_MOVE = "h8g8"
        const val SEARCH_MILLIS = 750L
        const val SEARCH_GRACE_MILLIS = 5_000L
        const val ENGINE_TIMEOUT_MILLIS = 30_000L
        const val CALLBACK_TIMEOUT_SECONDS = 45L
    }
}
