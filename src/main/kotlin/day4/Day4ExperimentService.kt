package day4

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class Day4ExperimentService(
    private val client: Day4ChatCompletionsClient = Day4ChatCompletionsClient(),
    private val resultRoot: Path = Path.of("results", "day4"),
) {
    private val json = Json { prettyPrint = true; explicitNulls = true }

    fun modelName(): String = client.modelName()

    fun createRunDirectory(runId: String, mode: String, taskIds: List<String>, temperatures: List<Double>, repetitions: Int): Path {
        val directory = resultRoot.resolve(runId)
        Files.createDirectories(directory.resolve("runs"))
        val config = buildJsonObject {
            put("run_id", runId)
            put("created_at", Instant.now().toString())
            put("mode", mode)
            put("model", client.modelName())
            put("temperatures", kotlinx.serialization.json.JsonArray(temperatures.map(::JsonPrimitive)))
            put("top_p", Day4Config.topP)
            put("max_completion_tokens", Day4Config.maxCompletionTokens)
            put("repetitions", repetitions)
            put("tasks", kotlinx.serialization.json.JsonArray(taskIds.map(::JsonPrimitive)))
            put("pricing", buildJsonObject {
                put("input_usd_per_million", Day4Config.pricing.inputUsdPerMillion)
                put("cached_input_usd_per_million", Day4Config.pricing.cachedInputUsdPerMillion)
                put("output_usd_per_million", Day4Config.pricing.outputUsdPerMillion)
            })
        }
        writeNew(directory.resolve("config.json"), config)
        return directory
    }

    suspend fun runOne(
        directory: Path,
        task: Day4Task,
        temperature: Double,
        repetition: Int,
    ): JsonObject {
        val timestamp = Instant.now()
        val startedAt = System.nanoTime()
        val request = client.requestParameters(task.prompt, temperature)
        val requestOutcome = runCatching { client.complete(task.prompt, temperature) }
        val api = requestOutcome.getOrNull()
        val latency = api?.latencyMs ?: (System.nanoTime() - startedAt) / 1_000_000
        val error = api?.error ?: requestOutcome.exceptionOrNull()?.message
        val validation = when {
            error != null || api?.responseText == null -> Day4ValidationResult("NEEDS_REVIEW", "Проверка невозможна: ответ API отсутствует.")
            task.id == Day4TaskCatalog.factual.id -> Day4AnswerValidator.validateFactual(api.responseText)
            else -> Day4AnswerValidator.validateCreative(api.responseText)
        }
        val input = api?.promptTokens
        val output = api?.completionTokens
        val cost = if (input != null && output != null) {
            Day4Config.pricing.costUsd(input, output, api.cachedPromptTokens)
        } else null
        val result = buildJsonObject {
            put("timestamp", timestamp.toString())
            put("task_id", task.id)
            put("task_category", task.category)
            put("temperature", temperature)
            put("repetition_number", repetition)
            put("model", api?.actualModel?.let(::JsonPrimitive) ?: JsonNull)
            put("prompt", task.prompt)
            put("request_parameters", request)
            put("response_text", api?.responseText?.let(::JsonPrimitive) ?: JsonNull)
            put("http_status", api?.apiStatus?.let(::JsonPrimitive) ?: JsonNull)
            put("finish_reason", api?.finishReason?.let(::JsonPrimitive) ?: JsonNull)
            put("latency_ms", latency)
            put("input_tokens", input?.let(::JsonPrimitive) ?: JsonNull)
            put("cached_input_tokens", api?.cachedPromptTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("output_tokens", output?.let(::JsonPrimitive) ?: JsonNull)
            put("total_tokens", api?.totalTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("estimated_cost", cost?.let(::JsonPrimitive) ?: JsonNull)
            put("validation_status", validation.status)
            put("validation_details", validation.details)
            put("validation_item_count", validation.itemCount?.let(::JsonPrimitive) ?: JsonNull)
            put("validation_duplicate_names", kotlinx.serialization.json.JsonArray(validation.duplicateNames.map(::JsonPrimitive)))
            put("raw_api_response", api?.rawApiResponse ?: JsonNull)
            put("error", error?.let(::JsonPrimitive) ?: JsonNull)
        }
        return persistResult(directory, result)
    }

    internal fun persistResult(directory: Path, result: JsonObject): JsonObject {
        val timestamp = result.stringValue("timestamp") ?: Instant.now().toString()
        val taskId = result.stringValue("task_id") ?: "unknown"
        val temperature = result.stringValue("temperature") ?: "unknown"
        val repetition = result.stringValue("repetition_number") ?: "unknown"
        val stamp = timestamp.replace(":", "-")
        val file = directory.resolve("runs").resolve("$taskId-temp-$temperature-run-$repetition-$stamp.json")
        writeNew(file, result)
        return JsonObject(result + ("result_file" to JsonPrimitive(file.toString())))
    }

    fun writeSummary(directory: Path, summary: JsonObject) {
        writeReplace(directory.resolve("summary.json"), summary)
    }

    private fun writeNew(path: Path, value: JsonObject) {
        Files.writeString(path, json.encodeToString(JsonObject.serializer(), value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private fun writeReplace(path: Path, value: JsonObject) {
        Files.writeString(path, json.encodeToString(JsonObject.serializer(), value), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
