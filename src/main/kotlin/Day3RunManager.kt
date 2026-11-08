import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class Day3RunManager(
    private val service: Day3TestService,
    private val operationMutex: Mutex,
    private val resultDirectory: Path = Path.of("results", "day3"),
) {
    private val lock = Any()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }
    private val runs = mutableMapOf<String, Day3RunState>()
    private var activeRunId: String? = null

    fun start(estimate: JsonObject): Day3RunStartResult {
        val created = synchronized(lock) {
            val active = activeRunId?.let(runs::get)
            if (active?.status == STATUS_QUEUED || active?.status == STATUS_RUNNING) {
                return Day3RunStartResult(null, "Полный эксперимент уже выполняется.")
            }

            val runId = UUID.randomUUID().toString()
            val createdAt = Instant.now().toString()
            val config = buildJsonObject {
                put("experiment_id", runId)
                put("created_at", createdAt)
                put("model", Day3Config.model)
                put("tasks", JsonArray(Day3TaskCatalog.all.map { JsonPrimitive(it.id) }))
                put("methods", JsonArray(Day3Method.entries.map { JsonPrimitive(it.name) }))
                put("reasoning_efforts", JsonArray(Day3Config.reasoningEfforts.map { JsonPrimitive(it.apiValue) }))
                put("expected_method_results", EXPECTED_COMBINATIONS)
                put("expected_api_requests", EXPECTED_API_REQUESTS)
                put("cost_estimate", estimate)
            }
            val configFile = persist("run-$runId-config.json", config)
                ?: return Day3RunStartResult(null, "Не удалось сохранить конфигурацию полного эксперимента.")
            Day3RunState(
                runId = runId,
                status = STATUS_QUEUED,
                createdAt = createdAt,
                completedAt = null,
                totalCombinations = EXPECTED_COMBINATIONS,
                completedCombinations = 0,
                totalApiRequests = EXPECTED_API_REQUESTS,
                completedApiCalls = 0,
                skippedApiCalls = 0,
                currentCombination = null,
                currentApiCall = null,
                results = emptyList(),
                summary = null,
                configFile = configFile,
                summaryFile = null,
                error = null,
            ).also {
                runs.clear()
                runs[runId] = it
                activeRunId = runId
            }
        }
        backgroundScope.launch { execute(created.runId) }
        return Day3RunStartResult(created, null)
    }

    fun get(runId: String): Day3RunState? = synchronized(lock) { runs[runId] }
    fun active(): Day3RunState? = synchronized(lock) { activeRunId?.let(runs::get) }

    private suspend fun execute(runId: String) {
        update(runId) { it.copy(status = STATUS_RUNNING) }
        try {
            operationMutex.withLock {
                for (task in Day3TaskCatalog.all) {
                    for (method in Day3Method.entries) {
                        for (effort in Day3Config.reasoningEfforts) {
                            update(runId) {
                                it.copy(currentCombination = buildJsonObject {
                                    put("task_id", task.id)
                                    put("task_category", task.category)
                                    put("method", method.name)
                                    put("reasoning_effort", effort.apiValue)
                                    put("planned_api_calls", method.apiCalls)
                                })
                            }
                            val beforeCalls = get(runId)?.completedApiCalls ?: 0
                            val result = try {
                                service.runTest(
                                    taskId = task.id,
                                    effort = effort,
                                    method = method,
                                    experimentId = runId,
                                ) { call ->
                                    update(runId) { current ->
                                        current.copy(
                                            completedApiCalls = current.completedApiCalls + 1,
                                            currentApiCall = buildJsonObject {
                                                put("task_id", task.id)
                                                put("method", method.name)
                                                put("reasoning_effort", effort.apiValue)
                                                put("purpose", call.callPurpose ?: "solution")
                                                put("api_call_number", current.completedApiCalls + 1)
                                                put("total_api_requests", current.totalApiRequests)
                                            },
                                        )
                                    }
                                }
                            } catch (exception: Exception) {
                                buildJsonObject {
                                    put("taskId", task.id)
                                    put("taskCategory", task.category)
                                    put("method", method.name)
                                    put("reasoningEffort", effort.name)
                                    put("model", Day3Config.model)
                                    put("expectedAnswer", task.expectedAnswer)
                                    put("answer", JsonNull)
                                    put("explanation", JsonNull)
                                    put("validationStatus", Day3ValidationStatus.NEEDS_REVIEW.name)
                                    put("validationDetails", "Комбинацию не удалось завершить из-за внутренней ошибки сервера.")
                                    put("inputTokens", JsonNull)
                                    put("outputTokens", JsonNull)
                                    put("reasoningTokens", JsonNull)
                                    put("totalTokens", JsonNull)
                                    put("latencyMs", 0)
                                    put("estimatedCostUsd", JsonNull)
                                    put("apiStatus", JsonNull)
                                    put("responseStatus", JsonNull)
                                    put("error", safeMessage(exception.message))
                                    put("calls", JsonArray(emptyList()))
                                    put("resultFiles", JsonArray(emptyList()))
                                }
                            }
                            val attemptedCalls = (get(runId)?.completedApiCalls ?: beforeCalls) - beforeCalls
                            val skipped = (method.apiCalls - attemptedCalls).coerceAtLeast(0)
                            update(runId) { current ->
                                current.copy(
                                    completedCombinations = current.completedCombinations + 1,
                                    skippedApiCalls = current.skippedApiCalls + skipped,
                                    currentCombination = null,
                                    currentApiCall = null,
                                    results = current.results + result,
                                )
                            }
                        }
                    }
                }
            }
            finish(runId, STATUS_COMPLETED, null)
        } catch (exception: Exception) {
            finish(runId, STATUS_FAILED, safeMessage(exception.message))
        }
    }

    private fun finish(runId: String, status: String, error: String?) {
        val current = get(runId) ?: return
        val summary = Day3Analytics.summarize(current.results)
        val summaryDocument = buildJsonObject {
            put("experiment_id", runId)
            put("completed_at", Instant.now().toString())
            put("status", status)
            put("summary", summary)
        }
        val summaryFile = persist("run-$runId-summary.json", summaryDocument)
        update(runId) {
            it.copy(
                status = status,
                completedAt = Instant.now().toString(),
                currentCombination = null,
                currentApiCall = null,
                summary = summary,
                summaryFile = summaryFile,
                error = listOfNotNull(error, if (summaryFile == null) "Не удалось сохранить итоговую сводку." else null).joinToString(" ").ifBlank { null },
            )
        }
    }

    private fun persist(fileName: String, document: JsonObject): String? = runCatching {
        Files.createDirectories(resultDirectory)
        val file = resultDirectory.resolve(fileName)
        Files.writeString(file, json.encodeToString(JsonObject.serializer(), document), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        file.toString()
    }.getOrNull()

    private fun update(runId: String, transform: (Day3RunState) -> Day3RunState) {
        synchronized(lock) {
            val current = runs[runId] ?: return
            runs[runId] = transform(current)
        }
    }

    private fun safeMessage(message: String?) = (message ?: "Не удалось завершить эксперимент.")
        .replace(Regex("(?i)\\bsk-[^\\s,;]+"), "[REDACTED]")

    companion object {
        const val EXPECTED_COMBINATIONS = 64
        const val EXPECTED_API_REQUESTS = 80
        private const val STATUS_QUEUED = "queued"
        private const val STATUS_RUNNING = "running"
        private const val STATUS_COMPLETED = "completed"
        private const val STATUS_FAILED = "failed"
    }
}

internal data class Day3RunStartResult(val state: Day3RunState?, val error: String?)

internal data class Day3RunState(
    val runId: String,
    val status: String,
    val createdAt: String,
    val completedAt: String?,
    val totalCombinations: Int,
    val completedCombinations: Int,
    val totalApiRequests: Int,
    val completedApiCalls: Int,
    val skippedApiCalls: Int,
    val currentCombination: JsonObject?,
    val currentApiCall: JsonObject?,
    val results: List<JsonObject>,
    val summary: JsonObject?,
    val configFile: String?,
    val summaryFile: String?,
    val error: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("run_id", runId)
        put("status", status)
        put("created_at", createdAt)
        put("completed_at", completedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("total_combinations", totalCombinations)
        put("completed_combinations", completedCombinations)
        put("total_api_requests", totalApiRequests)
        put("completed_api_calls", completedApiCalls)
        put("skipped_api_calls", skippedApiCalls)
        put("processed_api_requests", completedApiCalls + skippedApiCalls)
        put("current_combination", currentCombination ?: JsonNull)
        put("current_api_call", currentApiCall ?: JsonNull)
        put("summary", summary ?: JsonNull)
        put("config_file", configFile?.let(::JsonPrimitive) ?: JsonNull)
        put("summary_file", summaryFile?.let(::JsonPrimitive) ?: JsonNull)
        put("error", error?.let(::JsonPrimitive) ?: JsonNull)
        put("results", JsonArray(results.map { compactResult(it) }))
    }

    private fun compactResult(result: JsonObject): JsonObject = buildJsonObject {
        listOf(
            "taskId", "taskCategory", "taskText", "method", "reasoningEffort", "model", "generatedPrompt",
            "answer", "explanation", "expectedAnswer", "validationStatus", "validationDetails", "inputTokens",
            "outputTokens", "reasoningTokens", "totalTokens", "latencyMs", "estimatedCostUsd", "apiStatus",
            "responseStatus", "error", "resultFiles", "persistenceError", "aggregateResultFile",
        ).forEach { key -> result[key]?.let { put(key, it) } }
        val callArray = result["calls"] as? JsonArray
        if (callArray != null) put("api_call_count", callArray.size)
    }

}
