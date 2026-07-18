package com.drawlesschess.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.SoundPool
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampledAudioInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun persistedVolumeRangeIsATrueLinearMaster() {
        assertEquals(0f, soundVolumeMultiplier(-10), 0f)
        assertEquals(0.5f, soundVolumeMultiplier(DEFAULT_SOUND_VOLUME_PERCENT), 0f)
        assertEquals(1f, soundVolumeMultiplier(100), 0f)
        assertEquals(1f, soundVolumeMultiplier(150), 0f)
    }

    @Test
    fun volumePreviewUsesTheMeasuredMedianMoveSample() {
        assertEquals(SampledSoundCatalog.moves[16], SampledSoundCatalog.volumePreview)
    }

    @Test
    fun sanPlansLayerCheckTicksOverTheCorrectPrimaryCue() {
        assertEquals(MoveSoundPlan(PrimaryMoveSound.MOVE, false), moveSoundPlan("e4"))
        assertEquals(MoveSoundPlan(PrimaryMoveSound.MOVE, true), moveSoundPlan("Qh7+"))
        assertEquals(MoveSoundPlan(PrimaryMoveSound.CAPTURE, false), moveSoundPlan("Qxh7"))
        assertEquals(MoveSoundPlan(PrimaryMoveSound.CAPTURE, true), moveSoundPlan("Qxh7#"))
        assertEquals(MoveSoundPlan(PrimaryMoveSound.CASTLE, true), moveSoundPlan("O-O+"))
    }

    @Test
    fun catalogIsCompleteUniqueAndAndroidDecodesEveryResource() {
        val groups = listOf(
            SampledSoundCatalog.moves to 50,
            SampledSoundCatalog.captures to 12,
            SampledSoundCatalog.castles to 6,
            SampledSoundCatalog.fireworkLow to 2,
            SampledSoundCatalog.fireworkMid to 2,
            SampledSoundCatalog.fireworkHigh to 2,
            SampledSoundCatalog.glassImpact to 3,
            SampledSoundCatalog.glassFracture to 3,
            SampledSoundCatalog.glassShards to 3,
            SampledSoundCatalog.checkAccents to 4,
            SampledSoundCatalog.promotions to 4,
            SampledSoundCatalog.hints to 3,
            SampledSoundCatalog.lowTime to 4,
            SampledSoundCatalog.gameStart to 3,
            SampledSoundCatalog.undo to 3,
        )
        groups.forEach { (resources, expected) -> assertEquals(expected, resources.size) }
        assertEquals(104, SampledSoundCatalog.all.size)
        assertEquals(104, SampledSoundCatalog.all.toSet().size)

        SampledSoundCatalog.all.forEach { resource ->
            val name = context.resources.getResourceEntryName(resource)
            assertTrue(name.matches(Regex("[a-z][a-z0-9_]*")))
            context.resources.openRawResource(resource).use { input ->
                val header = ByteArray(4)
                assertEquals(header.size, input.read(header))
                assertEquals("OggS", header.toString(Charsets.US_ASCII))
            }
            val descriptor = requireNotNull(context.resources.openRawResourceFd(resource))
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length,
                )
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertEquals("audio/vorbis", format.getString(MediaFormat.KEY_MIME))
                assertEquals(48_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                val durationMicros = format.getLong(MediaFormat.KEY_DURATION)
                assertTrue("$name is implausibly short", durationMicros >= 30_000L)
                assertTrue("$name is implausibly long", durationMicros <= 2_500_000L)
            } finally {
                extractor.release()
                descriptor.close()
            }
        }
    }

    @Test
    fun soundPoolPreloadsEveryResourceOnThePlatform() {
        val pool = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        val latch = CountDownLatch(SampledSoundCatalog.all.size)
        val statuses = ConcurrentHashMap<Int, Int>()
        try {
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                statuses[sampleId] = status
                latch.countDown()
            }
            val sampleIds = SampledSoundCatalog.all.map { resource ->
                pool.load(context, resource, 1).also { sampleId ->
                    assertTrue("SoundPool rejected resource=$resource", sampleId != 0)
                }
            }
            assertEquals(sampleIds.size, sampleIds.toSet().size)
            assertTrue("Timed out loading ${latch.count} audio samples", latch.await(20, TimeUnit.SECONDS))
            assertEquals(SampledSoundCatalog.all.size, statuses.size)
            assertTrue(statuses.values.all { status -> status == 0 })
        } finally {
            pool.release()
        }
    }

    @Test
    fun shuffleBagUsesEveryVariantBeforeRepeating() {
        val source = intArrayOf(11, 22, 33, 44)
        val bag = SoundShuffleBag(source, Random(20260713))
        val firstCycle = List(source.size) { requireNotNull(bag.nextOrNull()) }
        val secondCycle = List(source.size) { requireNotNull(bag.nextOrNull()) }

        assertEquals(source.toSet(), firstCycle.toSet())
        assertEquals(source.toSet(), secondCycle.toSet())
        assertNotEquals(firstCycle.last(), secondCycle.first())
        assertEquals(null, SoundShuffleBag(intArrayOf()).nextOrNull())
    }

    @Test
    fun shuffleBagDoesNotConsumeVariantsThatAreNotYetAvailable() {
        val bag = SoundShuffleBag(intArrayOf(11, 22, 33), Random(7))

        assertEquals(null, bag.peekMatchingOrNull { false })
        val delayed = requireNotNull(bag.peekMatchingOrNull { it == 22 })
        assertEquals(22, delayed)
        assertTrue(bag.markPlayed(delayed))

        val restOfCycle = List(2) { requireNotNull(bag.nextOrNull()) }
        assertEquals(setOf(11, 33), restOfCycle.toSet())
        assertFalse(bag.markPlayed(22))
    }

    @Test
    fun shuffleBagKeepsCyclingAfterAResourcePermanentlyFails() {
        val bag = SoundShuffleBag(intArrayOf(11, 22, 33), Random(19))
        requireNotNull(bag.peekMatchingOrNull { true }) // Populate the current cycle first.

        assertTrue(bag.discard(22))
        assertEquals(2, bag.size)
        val firstCycle = List(2) { requireNotNull(bag.nextOrNull()) }
        val secondCycle = List(2) { requireNotNull(bag.nextOrNull()) }

        assertEquals(setOf(11, 33), firstCycle.toSet())
        assertEquals(setOf(11, 33), secondCycle.toSet())
        assertNotEquals(firstCycle.last(), secondCycle.first())
        assertFalse(bag.discard(22))
    }

    @Test
    fun playerMuteStopAndCloseOperationsAreIdempotent() {
        val player = GameSoundPlayer(context)
        player.setVolumePercent(100)
        player.playVolumePreview()
        player.setEnabled(false)
        player.playVolumePreview()
        player.playMove("e4")
        player.playCompletionCue(CompletionEffectCue.FIREWORK_LOW)
        player.stopAll()

        player.setEnabled(true)
        player.setVolumePercent(0)
        player.playVolumePreview()
        player.playMove("O-O")
        player.playCompletionCue(CompletionEffectCue.GLASS_IMPACT)
        player.playCompletionCue(CompletionEffectCue.GLASS_FRACTURE)
        player.setEnabled(false)
        player.playCompletionCue(CompletionEffectCue.GLASS_SHARDS)

        // Re-enabling cannot resurrect the canceled linked variant. A new impact creates the
        // only session that may accept its fracture and shard continuations.
        player.setEnabled(true)
        player.setVolumePercent(50)
        player.playMove("Qxh7+")
        player.playMove("Qh7#")
        player.playCheck()
        player.playCompletionCue(CompletionEffectCue.GLASS_SHARDS)
        player.playCompletionCue(CompletionEffectCue.GLASS_IMPACT)
        player.playCompletionCue(CompletionEffectCue.GLASS_FRACTURE)
        player.playCompletionCue(CompletionEffectCue.GLASS_SHARDS)
        player.stopAll()

        player.close()
        player.close()
        player.playCompletionCue(CompletionEffectCue.GLASS_IMPACT)
        player.stopAll()
    }
}
