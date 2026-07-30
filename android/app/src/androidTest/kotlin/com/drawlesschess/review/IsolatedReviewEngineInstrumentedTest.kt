package com.drawlesschess.review

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drawlesschess.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class IsolatedReviewEngineInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun serviceRunsOutsideGameplayProcessAndRejectsGameplayRequests() {
        val client = IsolatedReviewEngine(context)
        try {
            val manager = context.getSystemService(ActivityManager::class.java)
            val deadline = System.currentTimeMillis() + 5_000L
            var reviewPid: Int? = null
            while (System.currentTimeMillis() < deadline && reviewPid == null) {
                reviewPid = manager.runningAppProcesses
                    ?.firstOrNull { it.processName == "${context.packageName}:review_engine" }
                    ?.pid
                if (reviewPid == null) Thread.sleep(25L)
            }
            assertNotEquals(android.os.Process.myPid(), requireNotNull(reviewPid))

            val result = AtomicReference<Result<EngineResponse>>()
            val completed = CountDownLatch(1)
            client.analyze(reviewRequest()) {
                result.set(it)
                completed.countDown()
            }
            assertTrue("Isolated native analysis timed out", completed.await(15, TimeUnit.SECONDS))
            assertTrue(result.get().isSuccess)
            assertEquals("review-request", result.get().getOrThrow().requestId)

            val botRequest = reviewRequest().copy(purpose = EnginePurpose.BOT_MOVE)
            val failure = runCatching { client.analyze(botRequest) {} }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally {
            client.close()
        }
    }

    @Test
    fun ipcCodecPreservesReviewEvidence() {
        val request = reviewRequest()
        assertEquals(request, ReviewEngineJson.request(ReviewEngineJson.request(request)))

        val response = EngineResponse(
            request.requestId, request.gameId, request.positionId, UciMove("e2e4"), null,
            depth = 12, nodes = 3456,
            variations = listOf(
                PrincipalVariation(
                    scoreCentipawns = 23, mateIn = null, moves = listOf(UciMove("e2e4"), UciMove("e7e5")),
                    rank = 1, wdl = EngineWdl(410, 350, 240), depth = 12,
                ),
            ),
            engine = EngineIdentity("fairy", "test", 1),
        )
        assertEquals(response, ReviewEngineJson.response(ReviewEngineJson.response(response)))
    }

    private fun reviewRequest() = EngineRequest(
        requestId = "review-request", gameId = "game", positionId = "position",
        initialFen = "rn1qkbnr/pppbpppp/8/3p4/8/4P3/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
        moves = listOf(UciMove("g1f3")), rules = RulesContractV1.drawless(),
        strength = EngineStrength.SkillLevel(20), limits = EngineLimits(350, 3),
        purpose = EnginePurpose.REVIEW,
    )
}
