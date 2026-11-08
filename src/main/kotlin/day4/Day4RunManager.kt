package day4

import java.nio.file.Path
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
import kotlinx.serialization.json.put

internal class Day4RunManager(
    private val service: Day4ExperimentService,
    private val operationMutex: Mutex,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs = mutableMapOf<String, Day4RunState>()
    private var activeRunId: String? = null

    fun startTest(taskId: String, temperature: Double, repetitions: Int): Day4RunStartResult = start(
        mode = "test",
        taskIds = listOf(taskId),
        temperatures = listOf(temperature),
        repetitions = repetitions,
    )

    fun startFull(): Day4RunStartResult = start(
        mode = "full_experiment",
        taskIds = Day4TaskCatalog.all.map { it.id },
        temperatures = Day4Config.temperatures,
        repetitions = 5,
    )

    fun startPilot(): Day4RunStartResult = start(
        mode = "pilot",
        taskIds = Day4TaskCatalog.all.map { it.id },
        temperatures = Day4Config.temperatures,
        repetitions = 1,
        runIdPrefix = "pilot-",
    )

    fun get(runId: String): Day4RunState? = synchronized(lock) { runs[runId] }
    fun active(): Day4RunState? = synchronized(lock) { activeRunId?.let(runs::get) }

    private fun start(
        mode: String,
        taskIds: List<String>,
        temperatures: List<Double>,
        repetitions: Int,
        runIdPrefix: String = "",
    ): Day4RunStartResult {
        synchronized(lock) {
            val active = activeRunId?.let(runs::get)
            if (active?.status in setOf(STATUS_QUEUED, STATUS_RUNNING)) {
                return Day4RunStartResult(null, "Эксперимент Дня 4 уже выполняется.")
            }
            val runId = runIdPrefix + UUID.randomUUID().toString()
            val directory = try {
                service.createRunDirectory(runId, mode, taskIds, temperatures, repetitions)
            } catch (exception: Exception) {
                return Day4RunStartResult(null, "Не удалось создать каталог эксперимента: ${safeMessage(exception.message)}")
            }
            val created = Day4RunState(
                runId = runId,
                mode = mode,
                status = STATUS_QUEUED,
                createdAt = Instant.now().toString(),
                completedAt = null,
                totalRequests = taskIds.size * temperatures.size * repetitions,
                completedRequests = 0,
                currentRequest = null,
                results = emptyList(),
                summary = null,
                directory = directory.toString(),
                error = null,
            )
            runs[runId] = created
            activeRunId = runId
            scope.launch { execute(runId, taskIds, temperatures, repetitions) }
            return Day4RunStartResult(created, null)
        }
    }

    private suspend fun execute(runId: String, taskIds: List<String>, temperatures: List<Double>, repetitions: Int) {
        update(runId) { it.copy(status = STATUS_RUNNING) }
        try {
            operationMutex.withLock {
                for (taskId in taskIds) {
                    val task = Day4TaskCatalog.find(taskId) ?: continue
                    for (temperature in temperatures) {
                        for (repetition in 1..repetitions) {
                            val ordinal = (get(runId)?.completedRequests ?: 0) + 1
                            update(runId) { current -> current.copy(currentRequest = buildJsonObject {
                                put("ordinal", ordinal)
                                put("task_id", task.id)
                                put("task_category", task.category)
                                put("temperature", temperature)
                                put("repetition_number", repetition)
                                put("total_requests", current.totalRequests)
                            }) }
                            val result = try {
                                service.runOne(Path.of(get(runId)!!.directory), task, temperature, repetition)
                            } catch (exception: Exception) {
                                buildJsonObject {
                                    put("timestamp", Instant.now().toString())
                                    put("task_id", task.id)
                                    put("task_category", task.category)
                                    put("temperature", temperature)
                                    put("repetition_number", repetition)
                                    put("model", JsonNull)
                                    put("prompt", task.prompt)
                                    put("response_text", JsonNull)
                                    put("http_status", JsonNull)
                                    put("finish_reason", JsonNull)
                                    put("latency_ms", JsonNull)
                                    put("input_tokens", JsonNull)
                                    put("cached_input_tokens", JsonNull)
                                    put("output_tokens", JsonNull)
                                    put("total_tokens", JsonNull)
                                    put("estimated_cost", JsonNull)
                                    put("validation_status", "NEEDS_REVIEW")
                                    put("validation_details", "Проверка невозможна: запрос или сохранение результата завершились ошибкой.")
                                    put("error", safeMessage(exception.message))
                                }
                            }
                            update(runId) { current -> current.copy(
                                completedRequests = current.completedRequests + 1,
                                currentRequest = null,
                                results = current.results + result,
                            ) }
                        }
                    }
                }
            }
            val current = get(runId) ?: return
            val summary = Day4Analytics.summarize(current.results)
            service.writeSummary(Path.of(current.directory), buildJsonObject {
                put("run_id", runId)
                put("mode", current.mode)
                put("completed_at", Instant.now().toString())
                put("status", STATUS_COMPLETED)
                put("summary", summary)
            })
            update(runId) { it.copy(status = STATUS_COMPLETED, completedAt = Instant.now().toString(), summary = summary, currentRequest = null) }
        } catch (exception: Exception) {
            update(runId) { current ->
                val summary = Day4Analytics.summarize(current.results)
                current.copy(status = STATUS_FAILED, completedAt = Instant.now().toString(), summary = summary, error = safeMessage(exception.message))
            }
        }
    }

    private fun update(runId: String, transform: (Day4RunState) -> Day4RunState) = synchronized(lock) {
        val current = runs[runId] ?: return@synchronized
        runs[runId] = transform(current)
    }

    private fun safeMessage(message: String?) = (message ?: "Не удалось завершить эксперимент.")
        .replace(Regex("(?i)\\bsk-[^\\s,;]+"), "[REDACTED]")

    companion object {
        private const val STATUS_QUEUED = "queued"
        private const val STATUS_RUNNING = "running"
        private const val STATUS_COMPLETED = "completed"
        private const val STATUS_FAILED = "failed"
    }
}

internal data class Day4RunStartResult(val state: Day4RunState?, val error: String?)

internal data class Day4RunState(
    val runId: String,
    val mode: String,
    val status: String,
    val createdAt: String,
    val completedAt: String?,
    val totalRequests: Int,
    val completedRequests: Int,
    val currentRequest: JsonObject?,
    val results: List<JsonObject>,
    val summary: JsonObject?,
    val directory: String,
    val error: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("run_id", runId)
        put("mode", mode)
        put("status", status)
        put("created_at", createdAt)
        put("completed_at", completedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("total_requests", totalRequests)
        put("completed_requests", completedRequests)
        put("current_request", currentRequest ?: JsonNull)
        put("results", JsonArray(results))
        put("summary", summary ?: JsonNull)
        put("result_directory", directory)
        put("error", error?.let(::JsonPrimitive) ?: JsonNull)
    }
}
