package day7

import day6.Day6Tools
import day6.requestOpenAi
import day8.Day8AgentFailure
import day8.Day8TokenCounter
import day8.Day8TurnStats
import kotlinx.serialization.json.*
import java.time.Instant

data class Day7AgentResult(
    val answer: String,
    val transcript: List<Day7TranscriptEntry>,
    val toolCalls: Int,
    val tokenStats: Day8TurnStats? = null,
)

/** Replays and extends a locally persisted Responses API history on every turn. */
class Day7Agent(
    private val store: Day7ConversationStore = Day7ConversationStore(),
    private val model: String = "gpt-4.1",
    private val request: suspend (JsonObject) -> JsonObject = ::requestOpenAi,
    private val maxToolRounds: Int = 8,
) {
    suspend fun respond(conversationId: String, prompt: String, contextLimitTokens: Int = 1_047_576): Day7AgentResult {
        require(prompt.isNotBlank()) { "Введите сообщение." }
        val items = store.loadItems(conversationId).toMutableList()
        val startedAt = Instant.now().toString()
        val promptTokens = Day8TokenCounter.countText(prompt)
        val historyTokensBefore = Day8TokenCounter.estimateJson(JsonArray(items.toList()))
        var maxRequestEstimate = 0
        var apiInputTokens = 0
        var cachedInputTokens = 0
        var apiOutputTokens = 0
        var apiCalls = 0
        var answerTokensEstimate = 0

        fun stats(status: String, error: String? = null): Day8TurnStats {
            val nonCachedInput = (apiInputTokens - cachedInputTokens).coerceAtLeast(0)
            val cost = (nonCachedInput * 2.0 + cachedInputTokens * 0.5 + apiOutputTokens * 8.0) / 1_000_000.0
            return Day8TurnStats(
                createdAt = startedAt,
                status = status,
                currentPromptTokens = promptTokens,
                historyTokensBefore = historyTokensBefore,
                estimatedRequestTokens = maxRequestEstimate,
                historyTokensAfter = Day8TokenCounter.estimateJson(JsonArray(items.toList())),
                apiInputTokens = apiInputTokens,
                cachedInputTokens = cachedInputTokens,
                apiOutputTokens = apiOutputTokens,
                finalAnswerTokensEstimate = answerTokensEstimate,
                apiCalls = apiCalls,
                estimatedCostUsd = cost,
                contextLimitTokens = contextLimitTokens,
                error = error,
            )
        }

        items += buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", prompt) }) })
        }
        persist(conversationId, items)
        var toolCallCount = 0

        repeat(maxToolRounds + 1) { round ->
            val requestBody = buildRequest(items)
            val requestEstimate = Day8TokenCounter.estimateJson(requestBody)
            maxRequestEstimate = maxOf(maxRequestEstimate, requestEstimate)
            if (requestEstimate > contextLimitTokens) {
                throw Day8AgentFailure(stats("context_overflow", "Оценка запроса ($requestEstimate токенов) превысила установленный лимит $contextLimitTokens."), "Запрос остановлен до отправки: превышен лимит контекста.")
            }
            apiCalls++
            val response = try {
                request(requestBody)
            } catch (error: Exception) {
                val message = error.message ?: "Не удалось выполнить запрос к модели."
                val overflow = message.contains("context", ignoreCase = true) || message.contains("token limit", ignoreCase = true) || message.contains("input is too long", ignoreCase = true)
                throw Day8AgentFailure(stats(if (overflow) "context_overflow" else "api_error", message), message)
            }
            val usage = response["usage"]?.jsonObject
            apiInputTokens += usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
            cachedInputTokens += usage?.get("input_tokens_details")?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
            apiOutputTokens += usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val output = response["output"]?.jsonArray ?: JsonArray(emptyList())
            val functionCalls = output.mapNotNull { element ->
                (element as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "function_call" }
            }
            items.addAll(output)
            persist(conversationId, items)

            if (functionCalls.isEmpty()) {
                val answer = output.flatMap { element ->
                    (element as? JsonObject)?.get("content")?.jsonArray.orEmpty()
                }.filter { part ->
                    (part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "output_text"
                }.mapNotNull { part -> (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
                    .joinToString("\n").ifBlank { "Модель не вернула текстовый ответ." }
                answerTokensEstimate = Day8TokenCounter.countText(answer)
                val fullHistory = JsonArray(items.toList())
                return Day7AgentResult(answer, store.transcript(fullHistory), toolCallCount, stats("success"))
            }
            if (round == maxToolRounds) error("Агент достиг лимита вызовов инструментов ($maxToolRounds), но ответ не завершён.")

            functionCalls.forEach { call ->
                val name = call["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val arguments = call["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val result = runCatching { Day6Tools.execute(name, arguments) }
                    .getOrElse { "Ошибка инструмента: ${it.message ?: "не удалось выполнить"}" }
                val callId = call["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                items += buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", result)
                }
                toolCallCount++
                persist(conversationId, items)
            }
        }
        error("Агент не смог завершить ответ.")
    }

    private fun buildRequest(items: List<JsonElement>) = buildJsonObject {
        put("model", model)
        put("instructions", "Ты полезный русскоязычный помощник. Используй инструменты, когда они помогают ответить. Дождись результата инструмента и дай пользователю краткий итог.")
        put("input", JsonArray(items.toList()))
        put("tools", Day6Tools.schemas)
        put("tool_choice", "auto")
    }

    private fun persist(conversationId: String, items: List<JsonElement>) {
        store.saveItems(conversationId, JsonArray(items.toList()))
    }
}
