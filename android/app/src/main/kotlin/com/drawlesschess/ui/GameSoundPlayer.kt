package com.drawlesschess.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import kotlin.random.Random

/**
 * Preloaded, close-range board foley backed by the audited resources in [SampledSoundCatalog].
 * Playback failures are intentionally non-fatal: losing an audio device must never lose a game.
 */
internal class GameSoundPlayer(context: Context) : AutoCloseable {
    private enum class CompletionKind { VICTORY, DEFEAT }

    private data class CompletionSession(
        val kind: CompletionKind,
        val token: Any = Any(),
        val glassVariant: Int? = null,
    )

    private data class PendingSample(
        val volume: Float,
        val expiresAtMillis: Long,
        val completionToken: Any?,
        val bag: SoundShuffleBag? = null,
        val resource: Int? = null,
    )

    private val lock = Any()
    private val pool = SoundPool.Builder()
        .setMaxStreams(MAX_SIMULTANEOUS_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val resourceToSample = HashMap<Int, Int>(SampledSoundCatalog.all.size)
    private val sampleToResource = HashMap<Int, Int>(SampledSoundCatalog.all.size)
    private val loadedSamples = HashSet<Int>(SampledSoundCatalog.all.size)
    private val failedSamples = HashSet<Int>()
    private val pendingSamples = HashMap<Int, PendingSample>()
    private val activeStreams = ArrayDeque<Int>()

    private val moves = SoundShuffleBag(SampledSoundCatalog.moves)
    private val captures = SoundShuffleBag(SampledSoundCatalog.captures)
    private val castles = SoundShuffleBag(SampledSoundCatalog.castles)
    private val fireworkLow = SoundShuffleBag(SampledSoundCatalog.fireworkLow)
    private val fireworkMid = SoundShuffleBag(SampledSoundCatalog.fireworkMid)
    private val fireworkHigh = SoundShuffleBag(SampledSoundCatalog.fireworkHigh)
    private val glassVariants = SoundShuffleBag(
        IntArray(SampledSoundCatalog.glassImpact.size) { index -> index },
    )
    private val checks = SoundShuffleBag(SampledSoundCatalog.checkAccents)
    private val promotions = SoundShuffleBag(SampledSoundCatalog.promotions)
    private val hints = SoundShuffleBag(SampledSoundCatalog.hints)
    private val lowTime = SoundShuffleBag(SampledSoundCatalog.lowTime)
    private val gameStart = SoundShuffleBag(SampledSoundCatalog.gameStart)
    private val undo = SoundShuffleBag(SampledSoundCatalog.undo)

    private var enabled = true
    private var closed = false
    private var completionSession: CompletionSession? = null

    init {
        pool.setOnLoadCompleteListener loadListener@{ _, sampleId, status ->
            synchronized(lock) {
                if (closed) return@loadListener
                if (status != 0) {
                    pendingSamples.remove(sampleId)
                    failedSamples += sampleId
                    sampleToResource[sampleId]?.let(::discardResourceLocked)
                    Log.w(
                        AUDIO_LOG_TAG,
                        "Sample load failed: resource=${sampleToResource[sampleId]} status=$status",
                    )
                    return@loadListener
                }

                loadedSamples += sampleId
                val pending = pendingSamples.remove(sampleId) ?: return@loadListener
                val stillCurrent = pending.completionToken == null ||
                    pending.completionToken === completionSession?.token
                if (enabled && stillCurrent && SystemClock.uptimeMillis() <= pending.expiresAtMillis) {
                    if (playSampleLocked(sampleId, pending.volume)) {
                        pending.resource?.let { resource -> pending.bag?.markPlayed(resource) }
                    }
                }
            }
        }

        SampledSoundCatalog.all.forEach { resource ->
            val sampleId = try {
                pool.load(context.applicationContext, resource, 1)
            } catch (exception: RuntimeException) {
                Log.w(AUDIO_LOG_TAG, "Could not queue sample resource=$resource", exception)
                0
            }
            if (sampleId == 0) {
                Log.w(AUDIO_LOG_TAG, "SoundPool rejected sample resource=$resource")
                synchronized(lock) { discardResourceLocked(resource) }
            } else {
                synchronized(lock) {
                    resourceToSample[resource] = sampleId
                    sampleToResource[sampleId] = resource
                    if (sampleId in failedSamples) discardResourceLocked(resource)
                }
            }
        }
    }

    /** Compatibility overload retained for callers that only know whether a move captured. */
    fun playMove(capture: Boolean) {
        synchronized(lock) {
            if (closed || !enabled) return
            playBagLocked(if (capture) captures else moves, if (capture) 0.54f else 0.46f)
        }
    }

    /** SAN distinguishes the two-piece castling cue without coupling audio to engine internals. */
    fun playMove(san: String) {
        synchronized(lock) {
            if (closed || !enabled) return
            when {
                san.startsWith("O-O") -> playBagLocked(castles, 0.55f)
                'x' in san -> playBagLocked(captures, 0.54f)
                else -> playBagLocked(moves, 0.46f)
            }
        }
    }

    /** Applies the persisted sound preference without rebuilding the preloaded sample library. */
    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if (closed || enabled == value) return
            enabled = value
            if (!value) stopAllLocked()
        }
    }

    /** Plays the sample at the exact marker crossed by the shared visual animation clock. */
    fun playCompletionCue(cue: CompletionEffectCue) {
        synchronized(lock) {
            if (closed || !enabled) return
            when (cue) {
                CompletionEffectCue.FIREWORK_LOW -> startVictoryLocked()
                CompletionEffectCue.FIREWORK_MID -> continueVictoryLocked(fireworkMid, 0.52f)
                CompletionEffectCue.FIREWORK_HIGH -> continueVictoryLocked(fireworkHigh, 0.48f)
                CompletionEffectCue.GLASS_IMPACT -> startDefeatLocked()
                CompletionEffectCue.GLASS_FRACTURE -> continueDefeatLocked(cue, 0.52f)
                CompletionEffectCue.GLASS_SHARDS -> continueDefeatLocked(cue, 0.42f)
            }
        }
    }

    // Reserved for the matching UI events; intentionally not wired until those event contracts
    // can guarantee one-shot playback across recreation and saved-game restore.
    fun playCheck() = playOptional(checks, 0.16f)
    fun playPromotion() = playOptional(promotions, 0.24f)
    fun playHint() = playOptional(hints, 0.24f)
    fun playLowTime() = playOptional(lowTime, 0.24f)
    fun playGameStart() = playOptional(gameStart, 0.28f)
    fun playUndo() = playOptional(undo, 0.20f)

    fun stopAll() {
        synchronized(lock) {
            if (closed) return
            stopAllLocked()
        }
    }

    override fun close() {
        val release = synchronized(lock) {
            if (closed) return
            closed = true
            stopAllLocked()
            resourceToSample.clear()
            sampleToResource.clear()
            loadedSamples.clear()
            failedSamples.clear()
            true
        }
        if (release) {
            try {
                pool.release()
            } catch (_: RuntimeException) {
                // A vendor implementation may already have torn down its audio service.
            }
        }
    }

    private fun playOptional(bag: SoundShuffleBag, volume: Float) {
        synchronized(lock) {
            if (closed || !enabled) return
            playBagLocked(bag, volume)
        }
    }

    private fun startVictoryLocked() {
        cancelCompletionLocked()
        val session = CompletionSession(CompletionKind.VICTORY)
        completionSession = session
        playBagLocked(fireworkLow, 0.55f, session.token)
    }

    private fun continueVictoryLocked(bag: SoundShuffleBag, volume: Float) {
        val session = completionSession?.takeIf { it.kind == CompletionKind.VICTORY } ?: return
        playBagLocked(bag, volume, session.token)
    }

    private fun startDefeatLocked() {
        cancelCompletionLocked()
        discardUnavailableGlassVariantsLocked()
        val variant = glassVariants.nextOrNull() ?: return
        val session = CompletionSession(CompletionKind.DEFEAT, glassVariant = variant)
        completionSession = session
        playResourceLocked(
            SampledSoundCatalog.glassImpact[variant],
            0.64f,
            session.token,
        )
    }

    private fun continueDefeatLocked(cue: CompletionEffectCue, volume: Float) {
        val session = completionSession?.takeIf { it.kind == CompletionKind.DEFEAT } ?: return
        val variant = session.glassVariant ?: return
        val resource = when (cue) {
            CompletionEffectCue.GLASS_FRACTURE -> SampledSoundCatalog.glassFracture[variant]
            CompletionEffectCue.GLASS_SHARDS -> SampledSoundCatalog.glassShards[variant]
            else -> error("$cue is not a continuation of the defeat effect")
        }
        playResourceLocked(resource, volume, session.token)
    }

    private fun playBagLocked(
        bag: SoundShuffleBag,
        volume: Float,
        completionToken: Any? = null,
    ) {
        discardUnavailableResourcesLocked(bag)
        val loadedResource = bag.peekMatchingOrNull { resource ->
            resourceToSample[resource]?.let(loadedSamples::contains) == true
        }
        if (loadedResource != null) {
            if (completionToken == null) clearOrdinaryPendingLocked()
            val sampleId = checkNotNull(resourceToSample[loadedResource])
            if (playSampleLocked(sampleId, volume)) bag.markPlayed(loadedResource)
            return
        }

        // Do not consume unloaded variants while searching for something playable. The selected
        // item leaves the bag only after SoundPool actually starts it, preserving each full cycle
        // even when an older device is still decoding the startup library.
        val pendingResource = bag.peekMatchingOrNull { resource ->
            resourceToSample[resource]?.let { sampleId -> sampleId !in failedSamples } == true
        } ?: return
        playResourceLocked(pendingResource, volume, completionToken, bag)
    }

    private fun discardUnavailableResourcesLocked(bag: SoundShuffleBag) {
        bag.discardWhere { resource ->
            val sampleId = resourceToSample[resource]
            sampleId == null || sampleId in failedSamples
        }
    }

    private fun discardUnavailableGlassVariantsLocked() {
        SampledSoundCatalog.glassImpact.indices.forEach { index ->
            val resources = intArrayOf(
                SampledSoundCatalog.glassImpact[index],
                SampledSoundCatalog.glassFracture[index],
                SampledSoundCatalog.glassShards[index],
            )
            if (resources.any { resource ->
                    val sampleId = resourceToSample[resource]
                    sampleId == null || sampleId in failedSamples
                }
            ) {
                glassVariants.discard(index)
            }
        }
    }

    private fun discardResourceLocked(resource: Int) {
        listOf(
            moves,
            captures,
            castles,
            fireworkLow,
            fireworkMid,
            fireworkHigh,
            checks,
            promotions,
            hints,
            lowTime,
            gameStart,
            undo,
        ).forEach { bag -> bag.discard(resource) }
        val glassIndex = SampledSoundCatalog.glassImpact.indices.firstOrNull { index ->
            resource == SampledSoundCatalog.glassImpact[index] ||
                resource == SampledSoundCatalog.glassFracture[index] ||
                resource == SampledSoundCatalog.glassShards[index]
        }
        if (glassIndex != null) {
            glassVariants.discard(glassIndex)
            val session = completionSession
            if (session?.kind == CompletionKind.DEFEAT && session.glassVariant == glassIndex) {
                // Never continue a linked three-layer effect with a different or broken variant.
                cancelCompletionLocked()
            }
        }
    }

    private fun playResourceLocked(
        resource: Int,
        volume: Float,
        token: Any?,
        bag: SoundShuffleBag? = null,
    ) {
        val sampleId = resourceToSample[resource] ?: run {
            Log.w(AUDIO_LOG_TAG, "No SoundPool id for sample resource=$resource")
            return
        }
        if (sampleId in loadedSamples) {
            if (token == null) clearOrdinaryPendingLocked()
            if (playSampleLocked(sampleId, volume)) bag?.markPlayed(resource)
            return
        }

        if (token == null) {
            // Keep only the newest ordinary action. A burst of startup actions must not replay
            // as a delayed train of ghost moves when asynchronous preloading completes.
            clearOrdinaryPendingLocked()
        }
        pendingSamples[sampleId] = PendingSample(
            volume = volume,
            expiresAtMillis = SystemClock.uptimeMillis() + PENDING_SAMPLE_MAX_AGE_MS,
            completionToken = token,
            bag = bag,
            resource = resource,
        )
    }

    private fun playSampleLocked(sampleId: Int, volume: Float): Boolean =
        try {
            val streamId = pool.play(sampleId, volume, volume, 1, 0, 1f)
            if (streamId == 0) {
                Log.w(AUDIO_LOG_TAG, "SoundPool could not start sample=$sampleId")
                false
            } else {
                activeStreams.addLast(streamId)
                while (activeStreams.size > MAX_TRACKED_STREAMS) {
                    pool.stop(activeStreams.removeFirst())
                }
                true
            }
        } catch (exception: RuntimeException) {
            Log.w(AUDIO_LOG_TAG, "Sample playback failed", exception)
            false
        }

    private fun clearOrdinaryPendingLocked() {
        pendingSamples.entries.removeAll { entry -> entry.value.completionToken == null }
    }

    private fun stopAllLocked() {
        cancelCompletionLocked()
        pendingSamples.clear()
        while (activeStreams.isNotEmpty()) {
            try {
                pool.stop(activeStreams.removeFirst())
            } catch (_: RuntimeException) {
                // Already stopped or released by the platform.
            }
        }
    }

    private fun cancelCompletionLocked() {
        completionSession = null
        pendingSamples.entries.removeAll { entry -> entry.value.completionToken != null }
    }
}

/** Shuffle bag with cycle-boundary repeat prevention; callers provide their own synchronization. */
internal class SoundShuffleBag(
    source: IntArray,
    private val random: Random = Random.Default,
) {
    private val available = source.toMutableList()
    private val remaining = ArrayList<Int>(source.size)
    private var last = Int.MIN_VALUE

    val size: Int
        get() = available.size

    fun nextOrNull(): Int? {
        val value = peekMatchingOrNull { true } ?: return null
        check(markPlayed(value))
        return value
    }

    fun peekMatchingOrNull(predicate: (Int) -> Boolean): Int? {
        if (available.isEmpty()) return null
        if (remaining.isEmpty()) refill()
        return remaining.firstOrNull(predicate)
    }

    fun markPlayed(value: Int): Boolean {
        val index = remaining.indexOf(value)
        if (index < 0) return false
        remaining.removeAt(index)
        last = value
        return true
    }

    fun discard(value: Int): Boolean {
        val removed = available.remove(value)
        remaining.remove(value)
        return removed
    }

    fun discardWhere(predicate: (Int) -> Boolean) {
        available.filter(predicate).toList().forEach(::discard)
    }

    private fun refill() {
        val shuffled = available.toIntArray()
        for (index in shuffled.lastIndex downTo 1) {
            val swap = random.nextInt(index + 1)
            val value = shuffled[index]
            shuffled[index] = shuffled[swap]
            shuffled[swap] = value
        }
        if (shuffled.size > 1 && shuffled[0] == last) {
            val swap = random.nextInt(1, shuffled.size)
            val value = shuffled[0]
            shuffled[0] = shuffled[swap]
            shuffled[swap] = value
        }
        remaining.clear()
        shuffled.forEach(remaining::add)
    }
}

/**
 * Builds one reusable static track. MODE_STATIC tracks intentionally report
 * STATE_NO_STATIC_DATA until their first successful write; only then may they be
 * required to report STATE_INITIALIZED.
 *
 * Kept internal so the Android regression suite can exercise the real platform state
 * transition instead of validating synthesized PCM alone.
 */
internal fun createStaticAudioTrack(pcm: ShortArray): AudioTrack? {
    var track: AudioTrack? = null
    return try {
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SOUND_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
            .build()
        if (track.state == AudioTrack.STATE_UNINITIALIZED) {
            Log.w(AUDIO_LOG_TAG, "Static AudioTrack was uninitialized after construction")
            releaseAudioTrack(track)
            null
        } else {
            val written = track.write(pcm, 0, pcm.size)
            if (written != pcm.size || track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(
                    AUDIO_LOG_TAG,
                    "Static AudioTrack rejected PCM: written=$written expected=${pcm.size} " +
                        "state=${track.state}",
                )
                releaseAudioTrack(track)
                null
            } else {
                track
            }
        }
    } catch (exception: RuntimeException) {
        Log.w(AUDIO_LOG_TAG, "Static AudioTrack initialization failed", exception)
        track?.let(::releaseAudioTrack)
        null
    }
}

private fun releaseAudioTrack(track: AudioTrack) {
    try {
        track.release()
    } catch (_: RuntimeException) {
        // Some vendor audio drivers throw when a half-initialized track is released.
    }
}

private const val AUDIO_LOG_TAG = "DrawlessAudio"
private const val MAX_SIMULTANEOUS_STREAMS = 8
private const val MAX_TRACKED_STREAMS = 64
private const val PENDING_SAMPLE_MAX_AGE_MS = 250L
