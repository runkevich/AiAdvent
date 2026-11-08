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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal data class ChatCompletionResult(
    val responseText: String?,
    val finishReason: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val rawApiResponse: JsonElement,
    val error: String?,
)

internal class ChatCompletionsClient(
    private val model: String = System.getenv("OPENAI_MODEL")?.takeIf(String::isNotBlank) ?: "gpt-4.1",
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun modelName(): String = model

    fun requestParameters(prompt: String, variant: Day2Variant): JsonObject = createRequest(prompt, variant)

    suspend fun complete(prompt: String, variant: Day2Variant): ChatCompletionResult {
        val apiKey = ApiKeyProvider.get()
        val requestBody = requestParameters(prompt, variant)
        val httpClient = HttpClient(CIO)
        try {
            val response = httpClient.post("https://api.openai.com/v1/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }
            val safeBody = response.bodyAsText()
                .replace(apiKey, "[REDACTED]")
                .replace(API_KEY_PATTERN, "[REDACTED]")
            val raw = runCatching { json.parseToJsonElement(safeBody) }
                .getOrElse { JsonPrimitive(safeBody) }

            if (response.status.value !in 200..299) {
                val message = runCatching {
                    raw.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                return ChatCompletionResult(
                    responseText = null,
                    finishReason = null,
                    promptTokens = null,
                    completionTokens = null,
                    totalTokens = null,
                    rawApiResponse = raw,
                    error = "OpenAI API вернул HTTP ${response.status.value}" +
                        (message?.let { ": $it" } ?: "."),
                )
            }

            return runCatching {
                val root = raw.jsonObject
                val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: error("Ответ OpenAI не содержит choices.")
                val content = choice["message"]?.jsonObject?.get("content")
                val responseText = (content as? JsonPrimitive)?.contentOrNull
                    ?: ""
                val usage = root["usage"]?.jsonObject
                ChatCompletionResult(
                    responseText = responseText,
                    finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
                    promptTokens = usage?.intValue("prompt_tokens"),
                    completionTokens = usage?.intValue("completion_tokens"),
                    totalTokens = usage?.intValue("total_tokens"),
                    rawApiResponse = raw,
                    error = null,
                )
            }.getOrElse { exception ->
                ChatCompletionResult(
                    responseText = null,
                    finishReason = null,
                    promptTokens = null,
                    completionTokens = null,
                    totalTokens = null,
                    rawApiResponse = raw,
                    error = "Не удалось разобрать ответ OpenAI: ${exception.message ?: "неверный формат ответа"}",
                )
            }
        } catch (exception: Exception) {
            val safeMessage = (exception.message ?: "Сетевая ошибка при запросе к OpenAI.")
                .replace(apiKey, "[REDACTED]")
            throw IllegalStateException(safeMessage, null)
        } finally {
            httpClient.close()
        }
    }

    private fun createRequest(prompt: String, variant: Day2Variant): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            if (variant != Day2Variant.BASELINE) {
                add(JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(variant.instructions))))
            }
            val userPrompt = if (variant == Day2Variant.STOP_SEQUENCE) {
                "$prompt\n\nПосле ответа выведи маркер <<<END_OF_RESPONSE>>>."
            } else {
                prompt
            }
            add(JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(userPrompt))))
        }
        if (variant.usesTokenLimit) put("max_completion_tokens", OUTPUT_TOKEN_LIMIT)
        if (variant == Day2Variant.STOP_SEQUENCE) put("stop", STOP_SEQUENCE)
        if (variant == Day2Variant.JSON_SCHEMA) {
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "dinner_recipes")
                    put("strict", true)
                    put("schema", Day2Variant.recipeSchema)
                }
            }
        }
    }

    private fun JsonObject.intValue(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    companion object {
        const val OUTPUT_TOKEN_LIMIT = 1200
        const val STOP_SEQUENCE = "<<<END_OF_RESPONSE>>>"
        private val API_KEY_PATTERN = Regex("(?i)\\bsk-[^\\s,;]+")
    }
}
