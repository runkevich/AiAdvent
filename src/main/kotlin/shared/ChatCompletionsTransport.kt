package shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ChatCompletionResult(
    val responseText: String?,
    val finishReason: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val rawApiResponse: JsonElement,
    val error: String?,
    val apiStatus: Int? = null,
    val latencyMs: Long? = null,
    val cachedPromptTokens: Int? = null,
    val actualModel: String? = null,
)

/** Shared HTTP and response parsing layer for the Day 2 and Day 4 Chat Completions clients. */
internal class ChatCompletionsTransport {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun complete(requestBody: JsonObject): ChatCompletionResult {
        val apiKey = ApiKeyProvider.get()
        val httpClient = HttpClient(CIO)
        val startedAt = System.nanoTime()
        try {
            val response = httpClient.post("https://api.openai.com/v1/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }
            val safeBody = response.bodyAsText().replace(apiKey, "[REDACTED]").replace(API_KEY_PATTERN, "[REDACTED]")
            val raw = runCatching { json.parseToJsonElement(safeBody) }.getOrElse { JsonPrimitive(safeBody) }
            val latency = (System.nanoTime() - startedAt) / 1_000_000
            val actualModel = runCatching { raw.jsonObject["model"]?.jsonPrimitive?.contentOrNull }.getOrNull()

            if (response.status.value !in 200..299) {
                val message = runCatching {
                    raw.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                return ChatCompletionResult(
                    responseText = null, finishReason = null, promptTokens = null, completionTokens = null,
                    totalTokens = null, rawApiResponse = raw,
                    error = "OpenAI API вернул HTTP ${response.status.value}" + (message?.let { ": $it" } ?: "."),
                    apiStatus = response.status.value, latencyMs = latency, actualModel = actualModel,
                )
            }

            return runCatching {
                val root = raw.jsonObject
                val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: error("Ответ OpenAI не содержит choices.")
                val content = choice["message"]?.jsonObject?.get("content")
                val usage = root["usage"]?.jsonObject
                ChatCompletionResult(
                    responseText = (content as? JsonPrimitive)?.contentOrNull ?: "",
                    finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
                    promptTokens = usage?.intValue("prompt_tokens"),
                    completionTokens = usage?.intValue("completion_tokens"),
                    totalTokens = usage?.intValue("total_tokens"),
                    cachedPromptTokens = usage?.get("prompt_tokens_details")?.jsonObject?.intValue("cached_tokens"),
                    rawApiResponse = raw, error = null, apiStatus = response.status.value,
                    latencyMs = latency, actualModel = actualModel,
                )
            }.getOrElse { exception ->
                ChatCompletionResult(
                    responseText = null, finishReason = null, promptTokens = null, completionTokens = null,
                    totalTokens = null, rawApiResponse = raw,
                    error = "Не удалось разобрать ответ OpenAI: ${exception.message ?: "неверный формат ответа"}",
                    apiStatus = response.status.value, latencyMs = latency, actualModel = actualModel,
                )
            }
        } catch (exception: Exception) {
            val safeMessage = (exception.message ?: "Сетевая ошибка при запросе к OpenAI.").replace(apiKey, "[REDACTED]")
            throw IllegalStateException(safeMessage, null)
        } finally {
            httpClient.close()
        }
    }

    private fun JsonObject.intValue(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    companion object {
        private val API_KEY_PATTERN = Regex("(?i)\\bsk-[^\\s,;]+")
    }
}
