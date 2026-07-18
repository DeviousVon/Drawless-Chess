package com.drawlesschess.selfplay

import java.nio.file.Path
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private sealed interface JobExecution {
    val job: SelfPlayJob

    data class Success(
        override val job: SelfPlayJob,
        val result: SelfPlayGameResult,
    ) : JobExecution

    data class Failure(
        override val job: SelfPlayJob,
        val error: Throwable,
    ) : JobExecution
}

private class SelfPlayBatchFailure(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

fun main(arguments: Array<String>) {
    if (runPuzzleCli(arguments)) return
    if (arguments.size == 2 && arguments[0] == "--validate-config") {
        validateConfig(Path.of(arguments[1]))
        return
    }
    val configPath = parseArguments(arguments)
    val config = SelfPlayConfig.load(configPath)
    val identity = RunIdentityFactory.create(config)
    val jobs = SelfPlayJobs.create(config)
    validateJobs(config, jobs)

    JsonlReport.open(config, identity).use { report ->
        val pending = jobs.filterNot { it.jobId in report.resumedJobIds }
        val resumed = jobs.size - pending.size
        report.writeInvocationStarted(jobs.size, pending.size, resumed)
        println(
            Json.encode(
                linkedMapOf(
                    "event" to "selfplay_invocation_started",
                    "run_fingerprint" to identity.fingerprint,
                    "total_jobs" to jobs.size,
                    "pending_jobs" to pending.size,
                    "resumed_jobs" to resumed,
                    "parallel_games" to config.parallelGames,
                    "engine_processes_at_capacity" to config.parallelGames * 2,
                    "output" to config.outputPath.toString(),
                ),
            ),
        )
        if (pending.isEmpty()) {
            report.writeSummary(0, resumed, 0, 0, 0, aborted = false)
            return
        }
        runBatch(config, pending, resumed, report)
    }
}

private fun validateConfig(configPath: Path) {
    val config = SelfPlayConfig.load(configPath)
    val jobs = SelfPlayJobs.create(config)
    val distinctOpenings = validateJobs(config, jobs)

    println(
        Json.encode(
            linkedMapOf(
                "event" to "selfplay_config_validated",
                "job_source" to config.jobSource.name.lowercase().replace('_', '-'),
                "jobs" to jobs.size,
                "distinct_openings" to jobs.map(SelfPlayJob::openingId).toSet().size,
                "validated_opening_positions" to distinctOpenings,
                "distinct_matchups" to jobs.mapNotNull(SelfPlayJob::matchupId).toSet().size,
                "parallel_games" to config.parallelGames,
                "engine_processes_at_capacity" to config.parallelGames * 2,
            ),
        ),
    )
}

private fun validateJobs(config: SelfPlayConfig, jobs: List<SelfPlayJob>): Int {
    require(jobs.size == config.games) {
        "Configured ${config.games} games but ${jobs.size} jobs were derived"
    }
    require(jobs.map(SelfPlayJob::jobId).toSet().size == jobs.size) {
        "Derived campaign contains duplicate job IDs"
    }
    val distinctOpenings = jobs.distinctBy { job -> job.initialFen to job.openingMoves }
    distinctOpenings.forEach { job -> replayOpening(config, job) }
    return distinctOpenings.size
}

private fun runBatch(
    config: SelfPlayConfig,
    jobs: List<SelfPlayJob>,
    resumed: Int,
    report: JsonlReport,
) {
    val executorTerminationWaitMillis =
        2L * (config.quitTimeoutMillis + 2_750L) + 1_000L
    val workerIds = AtomicInteger()
    val executor = Executors.newFixedThreadPool(config.parallelGames) { action ->
        Thread(action, "drawless-selfplay-${workerIds.incrementAndGet()}").apply {
            isDaemon = false
        }
    }
    val shutdownHook = Thread(
        {
            executor.shutdownNow()
            try {
                executor.awaitTermination(executorTerminationWaitMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        },
        "drawless-selfplay-shutdown",
    )
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    var completed = 0
    var censored = 0
    var failures = 0
    var aborted = false
    var firstFailure: Throwable? = null
    var terminalError: Throwable? = null
    var interruptedDuringCleanup = false

    fun retainError(error: Throwable) {
        val existing = terminalError
        if (existing == null) terminalError = error else if (existing !== error) existing.addSuppressed(error)
    }

    fun awaitTerminationUninterruptibly(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return executor.isTerminated
            try {
                return executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interruptedDuringCleanup = true
            }
        }
    }

    try {
        val completion = ExecutorCompletionService<JobExecution>(executor)
        val iterator = jobs.iterator()
        val active = linkedSetOf<Future<JobExecution>>()

        fun submitOne(): Boolean {
            if (!iterator.hasNext()) return false
            val job = iterator.next()
            val future = completion.submit {
                try {
                    report.writeGameStarted(job)
                    JobExecution.Success(job, SelfPlayGameRunner(config).run(job))
                } catch (error: Exception) {
                    JobExecution.Failure(job, error)
                }
            }
            active += future
            return true
        }

        repeat(minOf(config.parallelGames, jobs.size)) { submitOne() }
        while (active.isNotEmpty()) {
            val future = completion.take()
            active.remove(future)
            val execution = try {
                future.get()
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                throw SelfPlayBatchFailure("Self-play worker failed outside its job boundary", error)
            }
            when (execution) {
                is JobExecution.Success -> {
                    report.writeGame(execution.result)
                    completed++
                    if (execution.result.censored) censored++
                    println(
                        Json.encode(
                            linkedMapOf(
                                "event" to "selfplay_game_completed",
                                "job_id" to execution.job.jobId,
                                "completed" to completed,
                                "scheduled" to jobs.size,
                                "censored" to execution.result.censored,
                                "end_reason" to execution.result.session.outcome?.reason?.name,
                            ),
                        ),
                    )
                }
                is JobExecution.Failure -> {
                    report.writeFailure(execution.job, execution.error)
                    failures++
                    if (firstFailure == null) firstFailure = execution.error
                    System.err.println(
                        "Self-play job ${execution.job.jobId} failed: " +
                            (execution.error.message ?: execution.error::class.java.name),
                    )
                    if (config.failFast) {
                        aborted = true
                        active.forEach { it.cancel(true) }
                        active.clear()
                    }
                }
            }
            if (!aborted) {
                while (active.size < config.parallelGames) {
                    if (!submitOne()) break
                }
            }
        }
    } catch (error: Throwable) {
        aborted = true
        retainError(error)
        if (error is InterruptedException) interruptedDuringCleanup = true
    } finally {
        interruptedDuringCleanup = Thread.interrupted() || interruptedDuringCleanup
        if (aborted || terminalError != null) executor.shutdownNow() else executor.shutdown()
        if (!awaitTerminationUninterruptibly(executorTerminationWaitMillis)) {
            aborted = true
            executor.shutdownNow()
            if (!awaitTerminationUninterruptibly(executorTerminationWaitMillis)) {
                retainError(
                    SelfPlayBatchFailure(
                        "Self-play workers did not terminate within the bounded shutdown window",
                    ),
                )
            }
        }
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        try {
            report.writeSummary(
                scheduled = jobs.size,
                resumed = resumed,
                completed = completed,
                censored = censored,
                failures = failures,
                aborted = aborted,
            )
        } catch (error: Throwable) {
            aborted = true
            retainError(error)
        } finally {
            if (interruptedDuringCleanup) Thread.currentThread().interrupt()
        }
    }
    terminalError?.let { throw it }
    if (failures > 0) {
        throw SelfPlayBatchFailure(
            "$failures self-play job(s) failed; see ${config.outputPath}",
            firstFailure,
        )
    }
}

private fun parseArguments(arguments: Array<String>): Path {
    require(arguments.size == 2 && arguments[0] == "--config") {
        "Usage: java -jar drawless-selfplay.jar (--config|--validate-config) PATH; " +
            "use --puzzle-help for puzzle tooling"
    }
    return Path.of(arguments[1])
}
