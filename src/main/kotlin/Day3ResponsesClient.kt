import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class Day3ResponsesResult(
    val responseText: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val reasoningTokens: Int?,
    val latencyMs: Long,
    val estimatedCostUsd: Double?,
    val apiStatus: Int?,
    val responseStatus: String?,
    val finishReason: String?,
    val incompleteReason: String?,
    val incompleteDetails: JsonElement?,
    val rawApiResponse: JsonElement,
    val error: String?,
)

/** Day 3-only Responses API client. It does not share request behavior with the Day 1 or Day 2 clients. */
internal class Day3ResponsesClient(
    private val model: String = Day3Config.model,
    private val maxOutputTokens: Int = Day3Config.maxOutputTokens,
    private val pricing: Day3TokenPricing = Day3Config.pricing,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun modelName(): String = model

    fun requestParameters(
        prompt: String,
        effort: Day3ReasoningEffort,
        structuredOutput: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("input", prompt)
        put("max_output_tokens", maxOutputTokens)
        put("reasoning", buildJsonObject { put("effort", effort.apiValue) })
        if (structuredOutput) {
            put("text", buildJsonObject {
                put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("name", "day3_answer")
                    put("strict", true)
                    put("schema", answerSchema)
                })
            })
        }
    }

    suspend fun complete(
        prompt: String,
        effort: Day3ReasoningEffort,
        structuredOutput: Boolean = true,
    ): Day3ResponsesResult {
        val operationStartedAt = System.nanoTime()
        val apiKey = try {
            ApiKeyProvider.get()
        } catch (exception: Exception) {
            return failure(
                startedAt = operationStartedAt,
                apiStatus = null,
                raw = JsonNull,
                message = safeError(exception.message, null),
            )
        }

        val startedAt = System.nanoTime()
        val httpClient = HttpClient(CIO)
        try {
            val response = httpClient.post("https://api.openai.com/v1/responses") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestParameters(prompt, effort, structuredOutput).toString())
            }
            val responseBody = response.bodyAsText()
            val safeBody = redact(responseBody, apiKey)
            val parsedBody = runCatching { json.parseToJsonElement(safeBody) }
                .getOrElse { JsonPrimitive(safeBody) }
            val raw = removeReasoningItems(parsedBody)

            if (response.status.value !in 200..299) {
                val apiMessage = runCatching {
                    raw.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                return failure(
                    startedAt = startedAt,
                    apiStatus = response.status.value,
                    raw = raw,
                    message = "OpenAI API вернул HTTP ${response.status.value}" +
                        (apiMessage?.let { ": $it" } ?: "."),
                )
            }

            return runCatching { parseSuccess(startedAt, response.status.value, raw) }
                .getOrElse { exception ->
                    failure(
                        startedAt = startedAt,
                        apiStatus = response.status.value,
                        raw = raw,
                        message = "Не удалось разобрать успешный ответ OpenAI: ${safeError(exception.message, apiKey)}",
                    )
                }
        } catch (exception: Exception) {
            return failure(
                startedAt = startedAt,
                apiStatus = null,
                raw = JsonNull,
                message = safeError(exception.message ?: "Сетевая ошибка при запросе к OpenAI.", apiKey),
            )
        } finally {
            httpClient.close()
        }
    }

    private fun parseSuccess(startedAt: Long, httpStatus: Int, raw: JsonElement): Day3ResponsesResult {
        val root = raw as? JsonObject ?: return failure(
            startedAt, httpStatus, raw, "Не удалось разобрать JSON-ответ OpenAI.",
        )
        val usage = root["usage"] as? JsonObject
        val inputTokens = usage.intValue("input_tokens")
        val outputTokens = usage.intValue("output_tokens")
        val totalTokens = usage.intValue("total_tokens")
        val reasoningTokens = (usage?.get("output_tokens_details") as? JsonObject).intValue("reasoning_tokens")
        val inputDetails = (usage?.get("input_tokens_details") as? JsonObject)
        val cost = if (inputTokens != null && outputTokens != null) {
            pricing.costUsd(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedInputTokens = inputDetails.intValue("cached_tokens") ?: 0,
                cacheWriteTokens = inputDetails.intValue("cache_write_tokens") ?: 0,
            )
        } else {
            null
        }

        val responseStatus = root.stringValue("status")
        val incompleteReason = (root["incomplete_details"] as? JsonObject).stringValue("reason")
        val responseText = extractOutputText(root)
        return Day3ResponsesResult(
            responseText = responseText,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            reasoningTokens = reasoningTokens,
            latencyMs = elapsedMs(startedAt),
            estimatedCostUsd = cost,
            apiStatus = httpStatus,
            responseStatus = responseStatus,
            // Responses API normally reports status/incomplete_details rather than finish_reason.
            finishReason = root.stringValue("finish_reason"),
            incompleteReason = incompleteReason,
            incompleteDetails = root["incomplete_details"],
            rawApiResponse = raw,
            error = if (responseText == null) missingTextMessage(responseStatus, incompleteReason) else null,
        )
    }

    private fun failure(
        startedAt: Long,
        apiStatus: Int?,
        raw: JsonElement,
        message: String,
    ) = Day3ResponsesResult(
        responseText = null,
        inputTokens = null,
        outputTokens = null,
        totalTokens = null,
        reasoningTokens = null,
        latencyMs = elapsedMs(startedAt),
        estimatedCostUsd = null,
        apiStatus = apiStatus,
        responseStatus = (raw as? JsonObject).stringValue("status"),
        finishReason = null,
        incompleteReason = (raw as? JsonObject)?.get("incomplete_details")
            .let { it as? JsonObject }.stringValue("reason"),
        incompleteDetails = (raw as? JsonObject)?.get("incomplete_details"),
        rawApiResponse = raw,
        error = message,
    )

    private fun extractOutputText(root: JsonObject): String? {
        val output = (root["output"] as? JsonArray).orEmpty()
        return output.flatMap { outputItem ->
            val message = outputItem as? JsonObject ?: return@flatMap emptyList()
            if (message.stringValue("type") != "message" || message.stringValue("role") != "assistant") {
                return@flatMap emptyList()
            }
            (message["content"] as? JsonArray).orEmpty().mapNotNull { contentItem ->
                val content = contentItem as? JsonObject ?: return@mapNotNull null
                when (content.stringValue("type")) {
                    "output_text" -> content.stringValue("text")
                    "refusal" -> content.stringValue("refusal") ?: content.stringValue("text")
                    else -> null
                }
            }
        }.joinToString("\n").trim().takeIf(String::isNotEmpty)
    }

    private fun missingTextMessage(status: String?, incompleteReason: String?): String = when (status) {
        "incomplete" -> "Ответ неполный${incompleteReason?.let { " (причина: $it)" } ?: ""}: в сообщении модели нет текстового блока output_text."
        "completed" -> "Ответ со status=completed не содержит текстового блока output_text в сообщении модели."
        null -> "Ответ OpenAI не содержит читаемого сообщения с текстовым блоком output_text."
        else -> "В ответе OpenAI со status=$status нет читаемого текстового блока output_text."
    }

    private fun JsonObject?.intValue(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun JsonObject?.stringValue(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun redact(body: String, apiKey: String): String = body
        .replace(apiKey, "[REDACTED]")
        .replace(API_KEY_PATTERN, "[REDACTED]")

    /** Reasoning items are not needed for diagnostics and must not reach storage or the browser. */
    private fun removeReasoningItems(raw: JsonElement): JsonElement {
        val root = raw as? JsonObject ?: return raw
        val output = root["output"] as? JsonArray
        if (output == null && "reasoning" !in root) return root
        val visibleItems = JsonArray(
            output.orEmpty().filterNot { item -> (item as? JsonObject).stringValue("type") == "reasoning" },
        )
        return JsonObject((root - "reasoning") + ("output" to visibleItems))
    }

    private fun safeError(message: String?, apiKey: String?): String =
        (message ?: "Не удалось получить API-ключ.")
            .let { text -> if (apiKey == null) text else text.replace(apiKey, "[REDACTED]") }
            .replace(API_KEY_PATTERN, "[REDACTED]")

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    companion object {
        private val API_KEY_PATTERN = Regex("(?i)\\bsk-[^\\s,;\\\"']+")
        private val answerSchema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("required", JsonArray(listOf(JsonPrimitive("answer"), JsonPrimitive("explanation"))))
            put("properties", buildJsonObject {
                put("answer", buildJsonObject {
                    put("type", "string")
                    put("description", "Краткий итоговый ответ без рассуждений.")
                })
                put("explanation", buildJsonObject {
                    put("type", "string")
                    put("description", "Краткое объяснение результата без скрытых внутренних рассуждений.")
                })
            })
        }
    }
}
