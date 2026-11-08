import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ExperimentRunManager(
    private val experimentService: Day2ExperimentService,
    private val operationMutex: Mutex,
) {
    private val lock = Any()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs = mutableMapOf<String, ExperimentRunState>()
    private var activeRunId: String? = null

    fun start(prompt: String): ExperimentRunState? {
        val run = synchronized(lock) {
            val active = activeRunId?.let(runs::get)
            if (active?.status == STATUS_QUEUED || active?.status == STATUS_RUNNING) return null

            ExperimentRunState(
                runId = UUID.randomUUID().toString(),
                status = STATUS_QUEUED,
                createdAt = Instant.now().toString(),
                completedAt = null,
                totalRequests = TOTAL_REQUESTS,
                completedRequests = 0,
                currentRequest = null,
                results = emptyList(),
                summary = null,
                error = null,
            ).also {
                runs.clear()
                runs[it.runId] = it
                activeRunId = it.runId
            }
        }

        backgroundScope.launch { execute(run.runId, prompt) }
        return run
    }

    fun get(runId: String): ExperimentRunState? = synchronized(lock) { runs[runId] }

    fun active(): ExperimentRunState? = synchronized(lock) { activeRunId?.let(runs::get) }

    private suspend fun execute(runId: String, prompt: String) {
        update(runId) { it.copy(status = STATUS_RUNNING) }
        try {
            operationMutex.withLock {
                var ordinal = 0
                for (variant in Day2Variant.entries) {
                    for (runNumber in 1..RUNS_PER_VARIANT) {
                        ordinal += 1
                        update(runId) {
                            it.copy(currentRequest = buildJsonObject {
                                put("ordinal", ordinal)
                                put("variant", variant.id)
                                put("variant_name", variant.title)
                                put("run_number", runNumber)
                            })
                        }

                        val result = try {
                            experimentService.runTest(prompt, variant.id, "full_experiment", runNumber)
                        } catch (exception: Exception) {
                            experimentService.recordFailure(
                                prompt = prompt,
                                variantId = variant.id,
                                runType = "full_experiment",
                                runNumber = runNumber,
                                message = exception.message,
                            )
                        }
                        update(runId) {
                            it.copy(completedRequests = ordinal, results = it.results + result)
                        }
                    }
                }
            }
            update(runId) {
                it.copy(
                    status = STATUS_COMPLETED,
                    completedAt = Instant.now().toString(),
                    currentRequest = null,
                    summary = summarize(it.results),
                )
            }
        } catch (exception: Exception) {
            update(runId) {
                it.copy(
                    status = STATUS_FAILED,
                    completedAt = Instant.now().toString(),
                    error = safeMessage(exception.message),
                    summary = summarize(it.results),
                )
            }
        }
    }

    private fun update(runId: String, transform: (ExperimentRunState) -> ExperimentRunState) {
        synchronized(lock) {
            val current = runs[runId] ?: return
            runs[runId] = transform(current)
        }
    }

    private fun summarize(results: List<JsonObject>): JsonObject {
        val successful = results.filter { it["error"] == null || it["error"] == JsonNull }
        val validJsonCount = results.count { result ->
            result["experiment_variant"]?.jsonPrimitive?.contentOrNull == Day2Variant.JSON_SCHEMA.id &&
                result["validation_result"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull == "pass"
        }
        val perVariant = Day2Variant.entries.associate { variant ->
            val calls = results.filter { it["experiment_variant"]?.jsonPrimitive?.contentOrNull == variant.id }
            variant.id to buildJsonObject {
                put("name", variant.title)
                put("request_count", calls.size)
                put("successful_requests", calls.count { it["error"] == null || it["error"] == JsonNull })
                put("average_latency_ms", average(calls.mapNotNull { it["latency_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() }))
                put("average_input_tokens", average(calls.mapNotNull { it["token_usage"]?.jsonObject?.get("prompt_tokens")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() }))
                put("average_output_tokens", average(calls.mapNotNull { it["token_usage"]?.jsonObject?.get("completion_tokens")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() }))
            }
        }
        val problems = results.mapNotNull { result ->
            val error = result["error"]?.jsonPrimitive?.contentOrNull
            val validation = result["validation_result"]?.jsonObject
            val validationFailure = validation?.get("status")?.jsonPrimitive?.contentOrNull == "fail"
            when {
                !error.isNullOrBlank() -> error
                validationFailure -> validation["message"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }.distinct()

        return buildJsonObject {
            put("total_requests", results.size)
            put("successful_requests", successful.size)
            put("failed_requests", results.size - successful.size)
            put("valid_json_responses", validJsonCount)
            put("per_variant", JsonObject(perVariant))
            put("problems", JsonArray(problems.map(::JsonPrimitive)))
        }
    }

    private fun average(values: List<Double>) = if (values.isEmpty()) JsonNull else JsonPrimitive(
        (values.average() * 10.0).toLong() / 10.0,
    )

    private fun safeMessage(message: String?) = (message ?: "Не удалось завершить эксперимент.")
        .replace(Regex("(?i)\\bsk-[^\\s,;]+"), "[REDACTED]")

    companion object {
        private const val RUNS_PER_VARIANT = 3
        private const val TOTAL_REQUESTS = 4 * RUNS_PER_VARIANT
        private const val STATUS_QUEUED = "queued"
        private const val STATUS_RUNNING = "running"
        private const val STATUS_COMPLETED = "completed"
        private const val STATUS_FAILED = "failed"
    }
}

internal data class ExperimentRunState(
    val runId: String,
    val status: String,
    val createdAt: String,
    val completedAt: String?,
    val totalRequests: Int,
    val completedRequests: Int,
    val currentRequest: JsonObject?,
    val results: List<JsonObject>,
    val summary: JsonObject?,
    val error: String?,
) {
    fun toJson(includeResults: Boolean = status == "completed" || status == "failed"): JsonObject = buildJsonObject {
        put("run_id", runId)
        put("status", status)
        put("created_at", createdAt)
        put("completed_at", completedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("total_requests", totalRequests)
        put("completed_requests", completedRequests)
        put("current_request", currentRequest ?: JsonNull)
        put("summary", summary ?: JsonNull)
        put("error", error?.let(::JsonPrimitive) ?: JsonNull)
        if (includeResults) put("results", JsonArray(results))
    }
}
