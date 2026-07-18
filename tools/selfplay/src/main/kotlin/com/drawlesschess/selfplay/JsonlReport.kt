package com.drawlesschess.selfplay

import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal const val REPORT_SCHEMA_VERSION = 2

internal object Json {
    fun encode(value: Any?): String = buildString { appendValue(value) }

    fun decode(text: String): Any? = Parser(text).parse()

    fun decodeObject(text: String): Map<String, Any?> {
        val value = decode(text)
        require(value is Map<*, *>) { "JSON record must be an object" }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendString(value)
            is Boolean, is Byte, is Short, is Int, is Long -> append(value.toString())
            is Float -> {
                require(value.isFinite()) { "JSON cannot encode a non-finite float" }
                append(value.toString())
            }
            is Double -> {
                require(value.isFinite()) { "JSON cannot encode a non-finite double" }
                append(value.toString())
            }
            is Map<*, *> -> {
                append('{')
                var first = true
                value.forEach { (key, item) ->
                    require(key is String) { "JSON object keys must be strings" }
                    if (!first) append(',')
                    first = false
                    appendString(key)
                    append(':')
                    appendValue(item)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                var first = true
                value.forEach { item ->
                    if (!first) append(',')
                    first = false
                    appendValue(item)
                }
                append(']')
            }
            is Array<*> -> appendValue(value.asIterable())
            else -> error("Unsupported JSON value ${value::class.java.name}")
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20 || character.isSurrogate()) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            require(index == text.length) { "Unexpected JSON content at character ${index + 1}" }
            return value
        }

        private fun parseValue(): Any? {
            require(index < text.length) { "Unexpected end of JSON" }
            return when (text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected JSON character '${text[index]}' at character ${index + 1}")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            index++
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (take('}')) return result
            while (true) {
                require(index < text.length && text[index] == '"') {
                    "Expected JSON object key at character ${index + 1}"
                }
                val key = parseString()
                require(!result.containsKey(key)) { "Duplicate JSON object key '$key'" }
                skipWhitespace()
                require(take(':')) { "Expected ':' after JSON object key '$key'" }
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                if (take('}')) return result
                require(take(',')) { "Expected ',' or '}' at character ${index + 1}" }
                skipWhitespace()
            }
        }

        private fun parseArray(): List<Any?> {
            index++
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (take(']')) return result
            while (true) {
                result += parseValue()
                skipWhitespace()
                if (take(']')) return result
                require(take(',')) { "Expected ',' or ']' at character ${index + 1}" }
                skipWhitespace()
            }
        }

        private fun parseString(): String {
            require(take('"')) { "Expected JSON string at character ${index + 1}" }
            return buildString {
                while (true) {
                    require(index < text.length) { "Unterminated JSON string" }
                    val character = text[index++]
                    when {
                        character == '"' -> return@buildString
                        character == '\\' -> {
                            require(index < text.length) { "Unterminated JSON escape" }
                            when (val escaped = text[index++]) {
                                '"', '\\', '/' -> append(escaped)
                                'b' -> append('\b')
                                'f' -> append('\u000C')
                                'n' -> append('\n')
                                'r' -> append('\r')
                                't' -> append('\t')
                                'u' -> {
                                    require(index + 4 <= text.length) { "Incomplete JSON Unicode escape" }
                                    val encoded = text.substring(index, index + 4)
                                    require(encoded.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                                        "Invalid JSON Unicode escape '$encoded'"
                                    }
                                    append(encoded.toInt(16).toChar())
                                    index += 4
                                }
                                else -> error("Invalid JSON escape '\\$escaped'")
                            }
                        }
                        character.code < 0x20 -> error("Unescaped control character in JSON string")
                        else -> append(character)
                    }
                }
            }
        }

        private fun parseNumber(): Number {
            val start = index
            take('-')
            require(index < text.length) { "Incomplete JSON number" }
            if (take('0')) {
                require(index >= text.length || text[index] !in '0'..'9') {
                    "JSON numbers cannot contain leading zeroes"
                }
            } else {
                require(index < text.length && text[index] in '1'..'9') {
                    "Invalid JSON number at character ${index + 1}"
                }
                while (index < text.length && text[index] in '0'..'9') index++
            }
            var integral = true
            if (take('.')) {
                integral = false
                require(index < text.length && text[index] in '0'..'9') {
                    "JSON fraction requires a digit"
                }
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (index < text.length && text[index].lowercaseChar() == 'e') {
                integral = false
                index++
                if (index < text.length && text[index] in setOf('+', '-')) index++
                require(index < text.length && text[index] in '0'..'9') {
                    "JSON exponent requires a digit"
                }
                while (index < text.length && text[index] in '0'..'9') index++
            }
            val encoded = text.substring(start, index)
            return if (integral) {
                encoded.toLongOrNull() ?: error("JSON integer is outside the 64-bit range")
            } else {
                encoded.toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?: error("Invalid or non-finite JSON number")
            }
        }

        private fun <T> parseLiteral(encoded: String, value: T): T {
            require(text.regionMatches(index, encoded, 0, encoded.length)) {
                "Invalid JSON literal at character ${index + 1}"
            }
            index += encoded.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in setOf(' ', '\t', '\r', '\n')) index++
        }

        private fun take(expected: Char): Boolean =
            (index < text.length && text[index] == expected).also { found ->
                if (found) index++
            }
    }
}

data class RunIdentity(
    val fingerprint: String,
    val engineSha256: String,
    val variantsSha256: String,
    val runtimeSha256: String,
    val fixtureSha256: Map<String, String>,
)

object RunIdentityFactory {
    fun create(config: SelfPlayConfig): RunIdentity {
        val engineHash = sha256(config.enginePath)
        val variantsHash = sha256(config.variantsPath)
        val runtimeHash = runtimeSha256()
        val fixtureHashes = linkedMapOf<String, String>()
        config.openingsPath?.let { fixtureHashes["openings"] = sha256(it) }
        config.ladderLevelsPath?.let { fixtureHashes["ladder_levels"] = sha256(it) }
        config.adjacentMatchupsPath?.let { fixtureHashes["adjacent_matchups"] = sha256(it) }
        val digest = MessageDigest.getInstance("SHA-256")
        config.fingerprintFields().toSortedMap().forEach { (key, value) ->
            digest.update(key.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(value.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
        }
        listOf(engineHash, variantsHash, runtimeHash).forEach { value ->
            digest.update(value.toByteArray(StandardCharsets.US_ASCII))
            digest.update(0)
        }
        fixtureHashes.forEach { (name, value) ->
            digest.update(name.toByteArray(StandardCharsets.US_ASCII))
            digest.update(0)
            digest.update(value.toByteArray(StandardCharsets.US_ASCII))
            digest.update(0)
        }
        return RunIdentity(
            fingerprint = digest.hex(),
            engineSha256 = engineHash,
            variantsSha256 = variantsHash,
            runtimeSha256 = runtimeHash,
            fixtureSha256 = fixtureHashes,
        )
    }

    private fun runtimeSha256(): String {
        val location = SelfPlayConfig::class.java.protectionDomain.codeSource.location.toURI()
        val path = Path.of(location)
        if (Files.isRegularFile(path)) return sha256(path)
        require(Files.isDirectory(path)) { "Cannot hash runtime code location $path" }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(path).use { files ->
            files.filter(Files::isRegularFile)
                .sorted()
                .forEach { file ->
                    val relative = path.relativize(file).toString().replace('\\', '/')
                    digest.update(relative.toByteArray(StandardCharsets.UTF_8))
                    digest.update(0)
                    Files.newInputStream(file).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) digest.update(buffer, 0, count)
                        }
                    }
                    digest.update(0)
                }
        }
        return digest.hex()
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.hex()
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }
}

class JsonlReport private constructor(
    private val config: SelfPlayConfig,
    val identity: RunIdentity,
    val resumedJobIds: Set<String>,
    private val invocationId: String,
    private val writer: BufferedWriter,
    private val lockChannel: FileChannel,
    private val reportLock: FileLock,
) : AutoCloseable {
    @Synchronized
    fun writeInvocationStarted(totalJobs: Int, pendingJobs: Int, resumedJobs: Int) {
        require(totalJobs >= 0 && pendingJobs >= 0 && resumedJobs >= 0)
        require(pendingJobs + resumedJobs == totalJobs)
        val runtime = Runtime.getRuntime()
        append(
            linkedMapOf(
                "event" to "invocation_started",
                "run_fingerprint" to identity.fingerprint,
                "invocation_id" to invocationId,
                "started_at" to Instant.now().toString(),
                "started_at_epoch_ms" to System.currentTimeMillis(),
                "engine_sha256" to identity.engineSha256,
                "variants_sha256" to identity.variantsSha256,
                "runtime_sha256" to identity.runtimeSha256,
                "fixture_sha256" to identity.fixtureSha256,
                "java_version" to System.getProperty("java.version"),
                "java_vendor" to System.getProperty("java.vendor"),
                "java_vm_name" to System.getProperty("java.vm.name"),
                "os_name" to System.getProperty("os.name"),
                "os_version" to System.getProperty("os.version"),
                "os_arch" to System.getProperty("os.arch"),
                "available_processors" to runtime.availableProcessors(),
                "max_memory_bytes" to runtime.maxMemory(),
                "process_id" to ProcessHandle.current().pid(),
                "working_directory" to Path.of("").toAbsolutePath().normalize().toString(),
                "java_command" to (System.getProperty("sun.java.command") ?: "unavailable"),
                "total_jobs" to totalJobs,
                "pending_jobs" to pendingJobs,
                "resumed_jobs" to resumedJobs,
                "parallel_games" to config.parallelGames,
                "engine_processes_at_capacity" to config.parallelGames * 2,
                "output" to config.outputPath.toString(),
            ),
        )
    }

    @Synchronized
    fun writeGameStarted(job: SelfPlayJob) {
        append(
            linkedMapOf(
                "event" to "game_started",
                "run_fingerprint" to identity.fingerprint,
                "record_complete" to false,
                "job_id" to job.jobId,
                "pair_id" to job.pairId,
                "pair_leg" to job.pairLeg,
                "opening_id" to job.openingId,
                "matchup_id" to job.matchupId,
                "started_at_epoch_ms" to System.currentTimeMillis(),
            ),
        )
    }

    @Synchronized
    fun writeGame(result: SelfPlayGameResult) {
        val outcome = result.session.outcome
        val record = linkedMapOf<String, Any?>(
            "event" to "game",
            "run_fingerprint" to identity.fingerprint,
            "record_complete" to true,
            "job_id" to result.job.jobId,
            "pair_id" to result.job.pairId,
            "pair_leg" to result.job.pairLeg,
            "opening_id" to result.job.openingId,
            "opening_name" to result.job.openingName,
            "matchup_id" to result.job.matchupId,
            "white_level_id" to result.job.whiteLevelId,
            "black_level_id" to result.job.blackLevelId,
            "white_competitor" to result.job.whiteCompetitor,
            "black_competitor" to result.job.blackCompetitor,
            "white_strength" to result.job.whiteStrength.label,
            "black_strength" to result.job.blackStrength.label,
            "started_at_epoch_ms" to result.startedAtEpochMillis,
            "elapsed_ms" to result.elapsedMillis,
            "engine_white_name" to result.engineWhiteName,
            "engine_black_name" to result.engineBlackName,
            "initial_fen" to result.job.initialFen,
            "opening_moves" to result.job.openingMoves.map { it.value },
            "opening_plies" to result.openingPlies,
            "plies" to result.session.moves.size,
            "max_plies" to config.maxPlies,
            "censored" to result.censored,
            "continuation_recommended" to result.continuationRecommended,
            "winner" to outcome?.winner?.name,
            "loser" to outcome?.loser?.name,
            "end_reason" to outcome?.reason?.name,
            "adjudication_facts" to result.session.adjudicationFacts?.toReportMap(),
            "uci_moves" to result.uciMoves,
            "san_moves" to result.sanMoves,
            "fen_timeline" to result.fenTimeline,
            "final_fen" to result.fenTimeline.last(),
            "searches" to result.searches.map { search ->
                linkedMapOf(
                    "ply" to search.ply,
                    "side" to search.side.name,
                    "competitor" to search.competitor,
                    "strength" to search.strength,
                    "elapsed_ms" to search.elapsedMillis,
                    "depth" to search.depth,
                    "nodes" to search.nodes,
                    "score_type" to search.scoreType,
                    "score_value" to search.scoreValue,
                    "ponder" to search.ponder,
                )
            },
        )
        append(record)
    }

    @Synchronized
    fun writeFailure(job: SelfPlayJob, error: Throwable) {
        append(
            linkedMapOf(
                "event" to "game_failure",
                "run_fingerprint" to identity.fingerprint,
                "record_complete" to false,
                "job_id" to job.jobId,
                "pair_id" to job.pairId,
                "pair_leg" to job.pairLeg,
                "opening_id" to job.openingId,
                "opening_name" to job.openingName,
                "matchup_id" to job.matchupId,
                "white_level_id" to job.whiteLevelId,
                "black_level_id" to job.blackLevelId,
                "initial_fen" to job.initialFen,
                "opening_moves" to job.openingMoves.map { it.value },
                "error_type" to error::class.java.name,
                "error_message" to (
                    error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName
                ),
                "occurred_at_epoch_ms" to System.currentTimeMillis(),
            ),
        )
    }

    @Synchronized
    fun writeSummary(
        scheduled: Int,
        resumed: Int,
        completed: Int,
        censored: Int,
        failures: Int,
        aborted: Boolean,
    ) {
        append(
            linkedMapOf(
                "event" to "invocation_summary",
                "run_fingerprint" to identity.fingerprint,
                "invocation_id" to invocationId,
                "scheduled_this_invocation" to scheduled,
                "resumed_records_skipped" to resumed,
                "completed_this_invocation" to completed,
                "censored_this_invocation" to censored,
                "failures_this_invocation" to failures,
                "aborted" to aborted,
                "finished_at_epoch_ms" to System.currentTimeMillis(),
            ),
        )
    }

    override fun close() {
        try {
            writer.close()
        } finally {
            try {
                reportLock.release()
            } finally {
                lockChannel.close()
            }
        }
    }

    private fun append(record: Map<String, Any?>) {
        writer.append(Json.encode(record))
        writer.newLine()
        writer.flush()
    }

    companion object {
        private val sha256Pattern = Regex("[0-9a-f]{64}")
        private val jobIdPattern = Regex("[A-Za-z0-9._-]+")

        fun open(config: SelfPlayConfig, identity: RunIdentity): JsonlReport {
            config.outputPath.parent?.let(Files::createDirectories)
            require(!Files.exists(config.outputPath) || Files.isRegularFile(config.outputPath)) {
                "Report output is not a regular file: ${config.outputPath}"
            }
            val lockPath = config.outputPath.resolveSibling("${config.outputPath.fileName}.lock")
            val lockChannel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val reportLock = try {
                try {
                    lockChannel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } ?: error("Another self-play process is already using ${config.outputPath}")
            } catch (error: Throwable) {
                lockChannel.close()
                throw error
            }
            try {
                val existing = Files.isRegularFile(config.outputPath) &&
                    Files.size(config.outputPath) > 0L
                val completed = if (existing) {
                    // Establish ownership before any repair can modify this path.
                    validateFirstHeader(config.outputPath, config, identity)
                    repairPartialTail(config.outputPath)
                    scanReport(config.outputPath, config, identity)
                } else {
                    emptySet()
                }
                val writer = Files.newBufferedWriter(
                    config.outputPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                val report = JsonlReport(
                    config,
                    identity,
                    completed,
                    UUID.randomUUID().toString(),
                    writer,
                    lockChannel,
                    reportLock,
                )
                if (!existing) report.append(report.headerRecord())
                return report
            } catch (error: Throwable) {
                try {
                    reportLock.release()
                } finally {
                    lockChannel.close()
                }
                throw error
            }
        }

        private fun JsonlReport.headerRecord(): Map<String, Any?> = linkedMapOf(
            "event" to "run_header",
            "run_fingerprint" to identity.fingerprint,
            "report_schema_version" to REPORT_SCHEMA_VERSION,
            "config_schema_version" to SelfPlayConfig.SCHEMA_VERSION,
            "created_at" to Instant.now().toString(),
            "created_at_epoch_ms" to System.currentTimeMillis(),
            "engine_sha256" to identity.engineSha256,
            "variants_sha256" to identity.variantsSha256,
            "runtime_sha256" to identity.runtimeSha256,
            "fixture_sha256" to identity.fixtureSha256,
            "java_version" to System.getProperty("java.version"),
            "os_name" to System.getProperty("os.name"),
            "os_version" to System.getProperty("os.version"),
            "os_arch" to System.getProperty("os.arch"),
            "available_processors" to Runtime.getRuntime().availableProcessors(),
            "config" to config.fingerprintFields(),
        )

        private fun validateFirstHeader(
            path: Path,
            config: SelfPlayConfig,
            identity: RunIdentity,
        ) {
            val headerBytes = ByteArrayOutputStream().use { header ->
                Files.newInputStream(path).use { input ->
                    while (true) {
                        val next = input.read()
                        if (next < 0 || next == '\n'.code) break
                        require(header.size() < MAX_HEADER_BYTES) {
                            "Existing report header exceeds $MAX_HEADER_BYTES bytes"
                        }
                        header.write(next)
                    }
                }
                header.toByteArray()
            }
            require(headerBytes.isNotEmpty()) { "Existing report begins with a blank record" }
            val line = strictUtf8(headerBytes)
            require(line.isNotBlank()) { "Existing report begins with a blank record" }
            val record = parseRecord(line, 1)
            require(record.string("event") == "run_header") {
                "Existing report does not begin with a run_header"
            }
            validateHeader(record, config, identity, 1)
        }

        private fun scanReport(
            path: Path,
            config: SelfPlayConfig,
            identity: RunIdentity,
        ): Set<String> {
            val completed = linkedSetOf<String>()
            var lineNumber = 0
            strictReader(path).useLines { lines ->
                lines.forEach { line ->
                    lineNumber++
                    require(line.isNotBlank()) { "Blank JSONL record at report line $lineNumber" }
                    val record = parseRecord(line, lineNumber)
                    val event = record.string("event")
                    require(record.string("run_fingerprint").also { fingerprint ->
                        require(sha256Pattern.matches(fingerprint)) {
                            "Invalid run_fingerprint at report line $lineNumber"
                        }
                    } == identity.fingerprint) {
                        "Wrong run_fingerprint at report line $lineNumber"
                    }
                    when (event) {
                        "run_header" -> {
                            require(lineNumber == 1) {
                                "Extra run_header at report line $lineNumber"
                            }
                            validateHeader(record, config, identity, lineNumber)
                        }
                        "invocation_started" -> validateInvocationStarted(
                            record,
                            identity,
                            lineNumber,
                        )
                        "game_started" -> validateGameStarted(record, lineNumber)
                        "game" -> {
                            validateGame(record, lineNumber)
                            val jobId = record.jobId(lineNumber)
                            require(completed.add(jobId)) {
                                "Duplicate completed game ID '$jobId' at report line $lineNumber"
                            }
                        }
                        "game_failure" -> validateGameFailure(record, lineNumber)
                        "invocation_summary" -> validateInvocationSummary(record, lineNumber)
                        else -> error("Unknown report event '$event' at line $lineNumber")
                    }
                }
            }
            require(lineNumber > 0) { "Existing report contains no records" }
            return completed
        }

        private fun validateHeader(
            record: Map<String, Any?>,
            config: SelfPlayConfig,
            identity: RunIdentity,
            lineNumber: Int,
        ) {
            require(record.string("run_fingerprint") == identity.fingerprint) {
                "Existing report fingerprint does not match ${identity.fingerprint}"
            }
            require(record.long("report_schema_version") == REPORT_SCHEMA_VERSION.toLong()) {
                "Unsupported report schema at line $lineNumber; expected $REPORT_SCHEMA_VERSION"
            }
            require(record.long("config_schema_version") == SelfPlayConfig.SCHEMA_VERSION.toLong()) {
                "Wrong config schema at report line $lineNumber"
            }
            Instant.parse(record.string("created_at"))
            record.nonNegativeLong("created_at_epoch_ms")
            require(record.sha256("engine_sha256") == identity.engineSha256)
            require(record.sha256("variants_sha256") == identity.variantsSha256)
            require(record.sha256("runtime_sha256") == identity.runtimeSha256)
            require(record.sha256Map("fixture_sha256") == identity.fixtureSha256) {
                "Fixture hashes do not match at report line $lineNumber"
            }
            record.nonBlankString("java_version")
            record.nonBlankString("os_name")
            record.nonBlankString("os_version")
            record.nonBlankString("os_arch")
            require(record.long("available_processors") > 0L)
            require(record.stringMap("config") == config.fingerprintFields()) {
                "Header config does not match this invocation at report line $lineNumber"
            }
        }

        private fun validateInvocationStarted(
            record: Map<String, Any?>,
            identity: RunIdentity,
            lineNumber: Int,
        ) {
            record.uuid("invocation_id")
            Instant.parse(record.string("started_at"))
            record.nonNegativeLong("started_at_epoch_ms")
            require(record.sha256("engine_sha256") == identity.engineSha256)
            require(record.sha256("variants_sha256") == identity.variantsSha256)
            require(record.sha256("runtime_sha256") == identity.runtimeSha256)
            require(record.sha256Map("fixture_sha256") == identity.fixtureSha256)
            listOf(
                "java_version",
                "java_vendor",
                "java_vm_name",
                "os_name",
                "os_version",
                "os_arch",
                "working_directory",
                "java_command",
                "output",
            ).forEach { record.nonBlankString(it) }
            require(record.long("available_processors") > 0L)
            require(record.long("max_memory_bytes") > 0L)
            require(record.long("process_id") > 0L)
            val total = record.nonNegativeLong("total_jobs")
            val pending = record.nonNegativeLong("pending_jobs")
            val resumed = record.nonNegativeLong("resumed_jobs")
            require(pending + resumed == total) {
                "Invocation job counts disagree at report line $lineNumber"
            }
            require(record.long("parallel_games") > 0L)
            require(record.long("engine_processes_at_capacity") > 0L)
        }

        private fun validateGameStarted(record: Map<String, Any?>, lineNumber: Int) {
            require(!record.boolean("record_complete"))
            record.jobId(lineNumber)
            record.validatePair(lineNumber)
            record.nullableString("opening_id")
            record.nullableString("matchup_id")
            record.nonNegativeLong("started_at_epoch_ms")
        }

        private fun validateGame(record: Map<String, Any?>, lineNumber: Int) {
            require(record.boolean("record_complete"))
            record.jobId(lineNumber)
            record.validatePair(lineNumber)
            listOf("opening_id", "opening_name", "matchup_id", "white_level_id", "black_level_id")
                .forEach { record.nullableString(it) }
            listOf(
                "white_competitor",
                "black_competitor",
                "white_strength",
                "black_strength",
                "initial_fen",
                "final_fen",
            ).forEach { record.nonBlankString(it) }
            record.nullableString("engine_white_name")
            record.nullableString("engine_black_name")
            record.nonNegativeLong("started_at_epoch_ms")
            record.nonNegativeLong("elapsed_ms")
            record.stringArray("opening_moves")
            record.nonNegativeLong("opening_plies")
            record.nonNegativeLong("plies")
            require(record.long("max_plies") > 0L)
            record.boolean("censored")
            record.boolean("continuation_recommended")
            listOf("winner", "loser", "end_reason").forEach { record.nullableString(it) }
            record.nullableObject("adjudication_facts")
            record.stringArray("uci_moves")
            record.stringArray("san_moves")
            require(record.stringArray("fen_timeline").isNotEmpty())
            record.array("searches").forEachIndexed { index, value ->
                require(value is Map<*, *>) {
                    "searches[$index] must be an object at report line $lineNumber"
                }
                @Suppress("UNCHECKED_CAST")
                val search = value as Map<String, Any?>
                search.nonNegativeLong("ply")
                search.nonBlankString("side")
                search.nonBlankString("competitor")
                search.nonBlankString("strength")
                search.nonNegativeLong("elapsed_ms")
                listOf("depth", "nodes", "score_value").forEach { search.nullableLong(it) }
                search.nullableString("score_type")
                search.nullableString("ponder")
            }
        }

        private fun validateGameFailure(record: Map<String, Any?>, lineNumber: Int) {
            require(!record.boolean("record_complete"))
            record.jobId(lineNumber)
            record.validatePair(lineNumber)
            listOf(
                "opening_id",
                "opening_name",
                "matchup_id",
                "white_level_id",
                "black_level_id",
            ).forEach { record.nullableString(it) }
            record.nonBlankString("initial_fen")
            record.stringArray("opening_moves")
            record.nonBlankString("error_type")
            record.nonBlankString("error_message")
            record.nonNegativeLong("occurred_at_epoch_ms")
        }

        private fun validateInvocationSummary(record: Map<String, Any?>, lineNumber: Int) {
            record.uuid("invocation_id")
            listOf(
                "scheduled_this_invocation",
                "resumed_records_skipped",
                "completed_this_invocation",
                "censored_this_invocation",
                "failures_this_invocation",
                "finished_at_epoch_ms",
            ).forEach { record.nonNegativeLong(it) }
            record.boolean("aborted")
            require(lineNumber > 1)
        }

        private fun parseRecord(line: String, lineNumber: Int): Map<String, Any?> = try {
            Json.decodeObject(line)
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "Malformed JSONL record at report line $lineNumber: ${error.message}",
                error,
            )
        }

        private fun strictReader(path: Path) = InputStreamReader(
            Files.newInputStream(path),
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT),
        ).buffered()

        private fun strictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

        private fun repairPartialTail(path: Path) {
            if (!Files.isRegularFile(path) || Files.size(path) == 0L) return
            RandomAccessFile(path.toFile(), "rw").use { file ->
                val length = file.length()
                file.seek(length - 1)
                if (file.read() == '\n'.code) return
                var cursor = length - 1
                while (cursor >= 0) {
                    file.seek(cursor)
                    if (file.read() == '\n'.code) {
                        val start = cursor + 1
                        val tailLength = length - start
                        require(tailLength <= Int.MAX_VALUE) { "Partial report record is too large" }
                        val tailBytes = ByteArray(tailLength.toInt())
                        file.seek(start)
                        file.readFully(tailBytes)
                        val tail = runCatching {
                            StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(tailBytes))
                                .toString()
                        }.getOrNull()
                        require(tail == null || tail.isNotBlank()) {
                            "Blank partial record at end of report"
                        }
                        if (tail != null && runCatching { Json.decode(tail) }.isSuccess) {
                            file.seek(length)
                            file.write('\n'.code)
                        } else {
                            file.setLength(start)
                        }
                        return
                    }
                    cursor--
                }
                require(length <= Int.MAX_VALUE) { "Report header record is too large" }
                val bytes = ByteArray(length.toInt())
                file.seek(0L)
                file.readFully(bytes)
                val onlyRecord = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                // validateFirstHeader already proved this complete record is our header.
                Json.decodeObject(onlyRecord)
                file.seek(length)
                file.write('\n'.code)
            }
        }

        private fun Map<String, Any?>.required(name: String): Any? {
            require(containsKey(name)) { "Missing required field '$name'" }
            return get(name)
        }

        private fun Map<String, Any?>.string(name: String): String =
            required(name) as? String ?: error("Field '$name' must be a string")

        private fun Map<String, Any?>.nonBlankString(name: String): String =
            string(name).also { require(it.isNotBlank()) { "Field '$name' must not be blank" } }

        private fun Map<String, Any?>.nullableString(name: String): String? =
            required(name)?.let { it as? String ?: error("Field '$name' must be a string or null") }

        private fun Map<String, Any?>.long(name: String): Long =
            required(name) as? Long ?: error("Field '$name' must be an integer")

        private fun Map<String, Any?>.nullableLong(name: String): Long? =
            required(name)?.let { it as? Long ?: error("Field '$name' must be an integer or null") }

        private fun Map<String, Any?>.nonNegativeLong(name: String): Long =
            long(name).also { require(it >= 0L) { "Field '$name' must be non-negative" } }

        private fun Map<String, Any?>.boolean(name: String): Boolean =
            required(name) as? Boolean ?: error("Field '$name' must be a boolean")

        private fun Map<String, Any?>.array(name: String): List<Any?> =
            required(name) as? List<*> ?: error("Field '$name' must be an array")

        private fun Map<String, Any?>.stringArray(name: String): List<String> =
            array(name).mapIndexed { index, value ->
                value as? String ?: error("Field '$name' item $index must be a string")
            }

        private fun Map<String, Any?>.nullableObject(name: String): Map<*, *>? =
            required(name)?.let { it as? Map<*, *> ?: error("Field '$name' must be an object or null") }

        private fun Map<String, Any?>.stringMap(name: String): Map<String, String> {
            val value = required(name) as? Map<*, *> ?: error("Field '$name' must be an object")
            return value.entries.associate { (key, item) ->
                require(key is String && item is String) { "Field '$name' must map strings to strings" }
                key to item
            }
        }

        private fun Map<String, Any?>.sha256(name: String): String =
            string(name).also { require(sha256Pattern.matches(it)) { "Field '$name' is not SHA-256" } }

        private fun Map<String, Any?>.sha256Map(name: String): Map<String, String> =
            stringMap(name).also { hashes ->
                require(hashes.values.all(sha256Pattern::matches)) {
                    "Field '$name' contains an invalid SHA-256"
                }
            }

        private fun Map<String, Any?>.uuid(name: String): String = string(name).also { encoded ->
            require(runCatching { UUID.fromString(encoded) }.isSuccess) {
                "Field '$name' must be a UUID"
            }
        }

        private fun Map<String, Any?>.jobId(lineNumber: Int): String =
            nonBlankString("job_id").also { id ->
                require(jobIdPattern.matches(id)) {
                    "Invalid job_id '$id' at report line $lineNumber"
                }
            }

        private fun Map<String, Any?>.validatePair(lineNumber: Int) {
            val pairId = nullableString("pair_id")
            val pairLeg = nullableString("pair_leg")
            require((pairId == null) == (pairLeg == null)) {
                "pair_id and pair_leg must both be null or both be present at report line $lineNumber"
            }
            if (pairId != null) {
                require(pairId.isNotBlank()) { "pair_id must not be blank at report line $lineNumber" }
                require(pairLeg in setOf("a", "b", "lower-white", "higher-white")) {
                    "pair_leg has an unknown paired-game value at report line $lineNumber"
                }
            }
        }

        private const val MAX_HEADER_BYTES = 4 * 1024 * 1024
    }
}
