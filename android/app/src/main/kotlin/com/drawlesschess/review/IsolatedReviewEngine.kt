package com.drawlesschess.review

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.drawlesschess.core.*
import com.drawlesschess.engine.AndroidFairyEngineFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** ChessEngine client whose native delegate lives in the app's dedicated :review_engine process. */
class IsolatedReviewEngine(context: Context) : ChessEngine, AutoCloseable {
    private data class Pending(
        val request: EngineRequest,
        val callback: (Result<EngineResponse>) -> Unit,
    )

    private val applicationContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val submitted = ConcurrentHashMap.newKeySet<String>()
    private val replyMessenger = Messenger(Handler(Looper.getMainLooper(), ::handleReply))
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            serviceMessenger = Messenger(binder)
            pending.values.forEach { submit(it.request) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceMessenger = null
            failAll(IllegalStateException("Review engine process disconnected"))
        }

        override fun onBindingDied(name: ComponentName) {
            serviceMessenger = null
            failAll(IllegalStateException("Review engine binding died"))
        }

        override fun onNullBinding(name: ComponentName) {
            failAll(IllegalStateException("Review engine service returned no binder"))
        }
    }
    @Volatile private var serviceMessenger: Messenger? = null

    init {
        check(
            applicationContext.bindService(
                Intent(applicationContext, ReviewEngineService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        ) { "Could not bind isolated review engine" }
    }

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation {
        require(request.purpose == EnginePurpose.REVIEW) {
            "The isolated review engine accepts review requests only"
        }
        check(!closed.get()) { "Review engine client is closed" }
        check(pending.putIfAbsent(request.requestId, Pending(request, onResult)) == null) {
            "Duplicate review request ${request.requestId}"
        }
        if (serviceMessenger != null) submit(request)
        return EngineCancellation {
            if (pending.remove(request.requestId) != null) {
                submitted.remove(request.requestId)
                send(MSG_CANCEL, Bundle().apply { putString(KEY_REQUEST_ID, request.requestId) })
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.clear()
        submitted.clear()
        serviceMessenger = null
        runCatching { applicationContext.unbindService(connection) }
    }

    private fun submit(request: EngineRequest) {
        if (!pending.containsKey(request.requestId) || !submitted.add(request.requestId)) return
        send(MSG_ANALYZE, Bundle().apply { putString(KEY_PAYLOAD, ReviewEngineJson.request(request)) })
    }

    private fun send(what: Int, data: Bundle) {
        val target = serviceMessenger ?: return
        try {
            target.send(Message.obtain(null, what).apply {
                this.data = data
                replyTo = replyMessenger
            })
        } catch (error: RemoteException) {
            failAll(error)
        }
    }

    private fun handleReply(message: Message): Boolean {
        if (message.what != MSG_RESULT) return false
        val requestId = message.data.getString(KEY_REQUEST_ID) ?: return true
        val accepted = pending.remove(requestId) ?: return true
        submitted.remove(requestId)
        val failure = message.data.getString(KEY_ERROR)
        val result = if (failure != null) {
            Result.failure(IllegalStateException(failure))
        } else {
            runCatching { ReviewEngineJson.response(requireNotNull(message.data.getString(KEY_PAYLOAD))) }
        }
        accepted.callback(result)
        return true
    }

    private fun failAll(error: Throwable) {
        val accepted = pending.values.toList()
        pending.clear()
        submitted.clear()
        accepted.forEach { it.callback(Result.failure(error)) }
    }
}

/** Runs in :review_engine, giving Fairy-Stockfish a distinct set of process globals. */
class ReviewEngineService : Service() {
    private val cancellations = ConcurrentHashMap<String, EngineCancellation>()
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::handleMessage))
    private var engine: AutoCloseableChessEngine? = null

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        cancellations.values.forEach { runCatching { it.cancel() } }
        cancellations.clear()
        runCatching { engine?.close() }
        engine = null
        super.onDestroy()
    }

    private fun handleMessage(message: Message): Boolean {
        when (message.what) {
            MSG_ANALYZE -> analyze(message)
            MSG_CANCEL -> message.data.getString(KEY_REQUEST_ID)?.let { requestId ->
                cancellations.remove(requestId)?.let { runCatching { it.cancel() } }
            }
            else -> return false
        }
        return true
    }

    private fun analyze(message: Message) {
        val reply = message.replyTo ?: return
        val parsed = runCatching {
            ReviewEngineJson.request(requireNotNull(message.data.getString(KEY_PAYLOAD))).also {
                require(it.purpose == EnginePurpose.REVIEW) { "Isolated service accepts review work only" }
            }
        }
        if (parsed.isFailure) {
            sendFailure(reply, "unknown", parsed.exceptionOrNull())
            return
        }
        val request = parsed.getOrThrow()
        if (cancellations.containsKey(request.requestId)) {
            sendFailure(reply, request.requestId, IllegalStateException("Duplicate review request"))
            return
        }
        val delegate = try {
            engine ?: AutoCloseableChessEngine(AndroidFairyEngineFactory(this).create()).also { engine = it }
        } catch (error: Throwable) {
            sendFailure(reply, request.requestId, error)
            return
        }
        val completed = AtomicBoolean(false)
        val cancellation = try {
            delegate.analyze(request) { result ->
                completed.set(true)
                cancellations.remove(request.requestId)
                val response = Message.obtain(null, MSG_RESULT).apply {
                    data = Bundle().apply {
                        putString(KEY_REQUEST_ID, request.requestId)
                        result.fold(
                            onSuccess = { putString(KEY_PAYLOAD, ReviewEngineJson.response(it)) },
                            onFailure = { putString(KEY_ERROR, it.message ?: it::class.java.simpleName) },
                        )
                    }
                }
                runCatching { reply.send(response) }
            }
        } catch (error: Throwable) {
            sendFailure(reply, request.requestId, error)
            return
        }
        if (!completed.get()) cancellations[request.requestId] = cancellation
    }

    private fun sendFailure(reply: Messenger, requestId: String, error: Throwable?) {
        runCatching {
            reply.send(Message.obtain(null, MSG_RESULT).apply {
                data = Bundle().apply {
                    putString(KEY_REQUEST_ID, requestId)
                    putString(KEY_ERROR, error?.message ?: "Review engine request failed")
                }
            })
        }
    }

    private class AutoCloseableChessEngine(
        private val delegate: com.drawlesschess.engine.AndroidFairyEngineSession,
    ) : ChessEngine, AutoCloseable {
        override fun analyze(request: EngineRequest, onResult: (Result<EngineResponse>) -> Unit) =
            delegate.analyze(request, onResult)
        override fun close() = delegate.close()
    }
}

private const val MSG_ANALYZE = 1
private const val MSG_CANCEL = 2
private const val MSG_RESULT = 3
private const val KEY_REQUEST_ID = "requestId"
private const val KEY_PAYLOAD = "payload"
private const val KEY_ERROR = "error"

internal object ReviewEngineJson {
    fun request(value: EngineRequest): String = JSONObject().apply {
        put("requestId", value.requestId); put("gameId", value.gameId); put("positionId", value.positionId)
        put("initialFen", value.initialFen); put("moves", JSONArray(value.moves.map(UciMove::value)))
        put("purpose", value.purpose.name); put("moveTimeMillis", value.limits.moveTimeMillis)
        put("multiPv", value.limits.multiPv); put("rules", rules(value.rules))
        when (val strength = value.strength) {
            is EngineStrength.ApproximateElo -> { put("strengthKind", "elo"); put("strength", strength.elo) }
            is EngineStrength.SkillLevel -> { put("strengthKind", "skill"); put("strength", strength.level) }
        }
    }.toString()

    fun request(json: String): EngineRequest = JSONObject(json).let { value ->
        EngineRequest(
            value.getString("requestId"), value.getString("gameId"), value.getString("positionId"),
            value.getString("initialFen"), value.getJSONArray("moves").strings().map(::UciMove),
            rules(value.getJSONObject("rules")),
            if (value.getString("strengthKind") == "elo") EngineStrength.ApproximateElo(value.getInt("strength"))
            else EngineStrength.SkillLevel(value.getInt("strength")),
            EngineLimits(value.getLong("moveTimeMillis"), value.getInt("multiPv")),
            EnginePurpose.valueOf(value.getString("purpose")),
        )
    }

    fun response(value: EngineResponse): String = JSONObject().apply {
        put("requestId", value.requestId); put("gameId", value.gameId); put("positionId", value.positionId)
        put("bestMove", value.bestMove.value); put("ponderMove", value.ponderMove?.value)
        put("depth", value.depth); put("nodes", value.nodes)
        put("engine", JSONObject().apply { put("id", value.engine.id); put("build", value.engine.build); put("patch", value.engine.drawlessPatch) })
        put("variations", JSONArray(value.variations.map(::variation)))
    }.toString()

    fun response(json: String): EngineResponse = JSONObject(json).let { value ->
        val identity = value.getJSONObject("engine")
        EngineResponse(
            value.getString("requestId"), value.getString("gameId"), value.getString("positionId"),
            UciMove(value.getString("bestMove")),
            value.takeUnless { it.isNull("ponderMove") }?.getString("ponderMove")?.let(::UciMove),
            value.getInt("depth"), value.getLong("nodes"),
            value.getJSONArray("variations").objects().map(::variation),
            EngineIdentity(identity.getString("id"), identity.getString("build"), identity.getInt("patch")),
        )
    }

    private fun rules(value: RulesContractV1) = JSONObject().apply {
        put("preset", value.preset.name); put("stalemate", value.stalemate.name)
        put("deadPosition", value.deadPosition.name); put("fiftyMove", value.fiftyMove.name)
        put("bareKing", value.bareKing.name)
    }

    private fun rules(value: JSONObject) = RulesContractV1(
        RulesContractV1.Preset.valueOf(value.getString("preset")),
        StalematePolicy.valueOf(value.getString("stalemate")),
        DeadPositionPolicy.valueOf(value.getString("deadPosition")),
        FiftyMovePolicy.valueOf(value.getString("fiftyMove")),
        bareKing = BareKingPolicy.valueOf(value.getString("bareKing")),
    )

    private fun variation(value: PrincipalVariation) = JSONObject().apply {
        put("cp", value.scoreCentipawns); put("mate", value.mateIn); put("moves", JSONArray(value.moves.map(UciMove::value)))
        put("rank", value.rank); put("bound", value.bound.name); put("depth", value.depth)
        put("evidence", value.evidenceAvailable)
        value.wdl?.let { put("wdl", JSONObject().apply { put("wins", it.wins); put("draws", it.draws); put("losses", it.losses) }) }
    }

    private fun variation(value: JSONObject): PrincipalVariation {
        val wdl = value.optJSONObject("wdl")?.let { EngineWdl(it.getInt("wins"), it.getInt("draws"), it.getInt("losses")) }
        return PrincipalVariation(
            value.optIntOrNull("cp"), value.optIntOrNull("mate"), value.getJSONArray("moves").strings().map(::UciMove),
            value.getInt("rank"), EngineScoreBound.valueOf(value.getString("bound")), wdl,
            value.optIntOrNull("depth"), value.getBoolean("evidence"),
        )
    }

    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else getInt(name)
}
