package day5

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal class Day5RunManager(
    private val service: Day5ExperimentRunner,
    private val store: Day5ResultStore,
    private val operationMutex: Mutex,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }
    private val runs = mutableMapOf<String, Day5RunState>()
    private val files = mutableMapOf<String, Day5RunFiles>()
    private var activeRunId: String? = null

    init {
        store.loadFullRuns().forEach { (runFiles, saved) ->
            val restored = Day5RunState.fromJson(saved)
            val state = if (restored.status == Day5RunStatus.PENDING || restored.status == Day5RunStatus.RUNNING) {
                restored.copy(
                    status = Day5RunStatus.FAILED,
                    completedAt = Instant.now().toString(),
                    currentModelId = null,
                    currentTaskId = null,
                    error = "Приложение было остановлено; незавершённый эксперимент не возобновлялся.",
                )
            } else restored
            runs[runFiles.runId] = state
            files[runFiles.runId] = runFiles
            if (state.status != restored.status) persistState(runFiles, state)
        }
    }

    fun start(): Day5RunStartResult {
        synchronized(lock) {
            if (activeRunId?.let(runs::get)?.status in setOf(Day5RunStatus.PENDING, Day5RunStatus.RUNNING)) {
                return Day5RunStartResult(null, "Полный эксперимент Day 5 уже выполняется.")
            }
            if (!operationMutex.tryLock()) return Day5RunStartResult(null, "Сейчас выполняется другой запрос Day 5.")
            val id = UUID.randomUUID().toString()
            val now = Instant.now().toString()
            val combinations = combinations()
            val config = buildJsonObject {
                put("run_id", id)
                put("created_at", now)
                put("api", Day5Config.api)
                put("reasoning_effort", Day5Config.reasoningEffort)
                put("max_output_tokens", Day5Config.maxOutputTokens)
                put("models", JsonArray(Day5Config.models.map(::modelJson)))
                put("tasks", JsonArray(Day5TaskCatalog.comparisonTasks.map { task -> buildJsonObject {
                    put("id", task.id); put("category", task.category); put("text", task.text)
                } }))
                put("combinations", JsonArray(combinations.map { (model, task) -> buildJsonObject {
                    put("model_id", model.id); put("task_id", task.id)
                } }))
            }
            val runFiles = try {
                store.createFullRun(id, config)
            } catch (e: Exception) {
                operationMutex.unlock()
                return Day5RunStartResult(null, "Не удалось создать папку полного эксперимента.")
            }
            val state = Day5RunState(id, Day5RunStatus.PENDING, now, null, 0, TOTAL, null, null, null, emptyList(), emptyList(), 0)
            try {
                persistState(runFiles, state)
            } catch (e: Exception) {
                operationMutex.unlock()
                return Day5RunStartResult(null, "Не удалось сохранить состояние полного эксперимента.")
            }
            runs[id] = state
            files[id] = runFiles
            activeRunId = id
            scope.launch { execute(id, combinations) }
            return Day5RunStartResult(state, null)
        }
    }

    fun get(runId: String): Day5RunState? = synchronized(lock) { runs[runId] }
    fun all(): List<Day5RunState> = synchronized(lock) { runs.values.sortedByDescending { it.createdAt } }
    fun active(): Day5RunState? = synchronized(lock) { activeRunId?.let(runs::get) }

    private suspend fun execute(runId: String, combinations: List<Pair<Day5ModelConfig, Day5Task>>) {
        update(runId) { it.copy(status = Day5RunStatus.RUNNING) }
        try {
            combinations.forEachIndexed { index, (model, task) ->
                update(runId) { it.copy(currentModelId = model.id, currentTaskId = task.id) }
                val record = try {
                    val result = service.runTest(model.id, task.id).sanitized()
                    buildJsonObject {
                        put("index", index + 1)
                        put("model_id", model.id)
                        put("task_id", task.id)
                        put("result", json.encodeToJsonElement(Day5ExperimentResult.serializer(), result))
                        put("error", result.error?.let(::JsonPrimitive) ?: JsonNull)
                    }
                } catch (e: Exception) {
                    buildJsonObject {
                        put("index", index + 1)
                        put("model_id", model.id)
                        put("task_id", task.id)
                        put("result", JsonNull)
                        put("error", safeMessage(e.message))
                        put("timestamp", Instant.now().toString())
                    }
                }
                val resultFile = try {
                    store.saveFullRunRecord(requireNotNull(files[runId]), index + 1, record)
                } catch (e: Exception) {
                    null
                }
                update(runId) { current ->
                    val completed = current.completed + 1
                    current.copy(
                        completed = completed,
                        currentModelId = null,
                        currentTaskId = null,
                        records = current.records + record,
                        resultFiles = current.resultFiles + listOfNotNull(resultFile),
                        persistenceErrors = current.persistenceErrors + if (resultFile == null) 1 else 0,
                    )
                }
            }
            val current = get(runId) ?: return
            val hasErrors = current.records.any { record ->
                record["error"] !is JsonNull || (record["result"] as? JsonObject)?.get("failureType")?.let { it !is JsonNull } == true
            } || current.persistenceErrors > 0
            finish(runId, if (hasErrors) Day5RunStatus.COMPLETED_WITH_ERRORS else Day5RunStatus.COMPLETED, null)
        } catch (e: Exception) {
            finish(runId, Day5RunStatus.FAILED, safeMessage(e.message))
        } finally {
            synchronized(lock) {
                if (activeRunId == runId) activeRunId = null
                if (operationMutex.isLocked) operationMutex.unlock()
            }
        }
    }

    private fun finish(runId: String, status: Day5RunStatus, error: String?) {
        update(runId) { it.copy(status = status, completedAt = Instant.now().toString(), currentModelId = null, currentTaskId = null, error = error) }
    }

    private fun update(runId: String, transform: (Day5RunState) -> Day5RunState) = synchronized(lock) {
        val current = runs[runId] ?: return@synchronized
        val updated = transform(current)
        runs[runId] = updated
        files[runId]?.let { runCatching { persistState(it, updated) } }
    }

    private fun persistState(runFiles: Day5RunFiles, state: Day5RunState) {
        val summary = buildJsonObject {
            put("run_id", state.runId); put("status", state.status.name)
            put("completed", state.completed); put("total", state.total)
            put("error_count", state.records.count { it["error"] !is JsonNull } + state.persistenceErrors)
            put("updated_at", Instant.now().toString())
        }
        store.saveFullRunState(runFiles, state.toJson(), summary)
    }

    private fun combinations() = Day5TaskCatalog.comparisonTasks.flatMap { task ->
        Day5Config.models.map { model -> model to task }
    }

    private fun modelJson(model: Day5ModelConfig) = buildJsonObject {
        put("id", model.id); put("display_name", model.displayName); put("tier", model.tier.name)
        put("reasoning_effort", Day5Config.reasoningEffort); put("max_output_tokens", Day5Config.maxOutputTokens)
        put("pricing_usd_per_million_tokens", buildJsonObject {
            put("input", model.pricing.inputUsdPerMillion)
            put("cached_input", model.pricing.cachedInputUsdPerMillion)
            put("output", model.pricing.outputUsdPerMillion)
        })
    }

    private fun safeMessage(message: String?) = (message ?: "Не удалось выполнить комбинацию.")
        .replace(Regex("(?i)\\bsk-[^\\s,;\\\"']+"), "[REDACTED]")
        .replace(Regex("(?i)\\bBearer\\s+[^\\s,;\\\"']+"), "Bearer [REDACTED]")

    private fun Day5ExperimentResult.sanitized() = copy(
        error = error?.let(::safeMessage),
        validationDetails = safeMessage(validationDetails),
    )

    companion object { const val TOTAL = 15 }
}

internal enum class Day5RunStatus { PENDING, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, FAILED }

internal data class Day5RunStartResult(val state: Day5RunState?, val error: String?)

internal data class Day5RunState(
    val runId: String,
    val status: Day5RunStatus,
    val createdAt: String,
    val completedAt: String?,
    val completed: Int,
    val total: Int,
    val currentModelId: String?,
    val currentTaskId: String?,
    val error: String?,
    val records: List<JsonObject>,
    val resultFiles: List<String>,
    val persistenceErrors: Int,
) {
    val progressPercent: Int get() = if (total == 0) 0 else completed * 100 / total
    fun toHistoryJson() = buildJsonObject {
        put("runId", runId); put("createdAt", createdAt); put("completedAt", completedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("status", status.name); put("completed", completed); put("total", total)
        put("progressPercent", progressPercent); put("error", error?.let(::JsonPrimitive) ?: JsonNull)
    }
    fun toJson() = buildJsonObject {
        put("runId", runId); put("status", status.name); put("createdAt", createdAt)
        put("completedAt", completedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("completed", completed); put("total", total); put("progressPercent", progressPercent)
        put("currentModelId", currentModelId?.let(::JsonPrimitive) ?: JsonNull)
        put("currentTaskId", currentTaskId?.let(::JsonPrimitive) ?: JsonNull)
        put("error", error?.let(::JsonPrimitive) ?: JsonNull)
        put("records", JsonArray(records)); put("resultFiles", JsonArray(resultFiles.map(::JsonPrimitive)))
        put("persistenceErrors", persistenceErrors)
    }

    companion object {
        fun fromJson(value: JsonObject): Day5RunState {
            fun text(k: String) = value[k]?.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull
            return Day5RunState(
                runId = requireNotNull(text("runId")), status = Day5RunStatus.valueOf(requireNotNull(text("status"))),
                createdAt = requireNotNull(text("createdAt")), completedAt = text("completedAt"),
                completed = value["completed"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                total = value["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: Day5RunManager.TOTAL,
                currentModelId = text("currentModelId"), currentTaskId = text("currentTaskId"), error = text("error"),
                records = (value["records"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList(),
                resultFiles = (value["resultFiles"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                persistenceErrors = value["persistenceErrors"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }
    }
}
