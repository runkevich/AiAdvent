package day9

import day6.Day6Tools
import day6.requestOpenAi
import day7.Day7ConversationStore
import day7.Day7TranscriptEntry
import day8.Day8AgentFailure
import day8.Day8TokenCounter
import day8.Day8TurnStats
import java.time.Instant
import kotlinx.serialization.json.*

enum class Day9Strategy { FULL, SUMMARY }

data class Day9AgentResult(
    val answer: String,
    val transcript: List<Day7TranscriptEntry>,
    val toolCalls: Int,
    val summary: String,
    val summarizedTurns: Int,
    val summaryTokens: Int,
    val summaryApiCalls: Int,
    val tokenStats: Day8TurnStats,
)

data class Day9RecallResult(
    val strategy: String,
    val answers: List<Day9RecallAnswer>,
    val correctCount: Int,
    val summary: String,
    val summaryTokens: Int,
    val summaryApiCalls: Int,
    val stats: Day8TurnStats,
)

data class Day9RecallAnswer(val key: String, val question: String, val answer: String, val correct: Boolean)

class Day9Agent(
    private val conversationStore: Day7ConversationStore = Day7ConversationStore(),
    private val memoryStore: Day9MemoryStore = Day9MemoryStore(),
    private val model: String = "gpt-4.1",
    private val request: suspend (JsonObject) -> JsonObject = ::requestOpenAi,
    private val lastTurnsToKeep: Int = 6,
    private val contextLimitTokens: Int = 1_047_576,
) {
    suspend fun respond(conversationId: String, prompt: String, strategy: Day9Strategy): Day9AgentResult {
        require(prompt.isNotBlank()) { "Введите сообщение." }
        val items = conversationStore.loadItems(conversationId).toMutableList()
        val historyBefore = Day8TokenCounter.estimateJson(JsonArray(items.toList()))
        val createdAt = Instant.now().toString()
        val usage = UsageAccumulator()
        val promptTokens = Day8TokenCounter.countText(prompt)
        var maxRequestEstimate = 0
        var answerTokens = 0
        var summaryState = if (strategy == Day9Strategy.SUMMARY) memoryStore.load(conversationId) else Day9MemoryState()

        items += userMessage(prompt)
        conversationStore.saveItems(conversationId, JsonArray(items.toList()))
        if (strategy == Day9Strategy.SUMMARY) {
            summaryState = updateSummaryIfNeeded(conversationId, items, summaryState, usage) { estimate ->
                maxRequestEstimate = maxOf(maxRequestEstimate, estimate)
            }
        }

        repeat(9) { round ->
            val inputItems = makeContext(items, strategy, summaryState)
            val body = agentRequest(inputItems)
            val estimate = Day8TokenCounter.estimateJson(body)
            maxRequestEstimate = maxOf(maxRequestEstimate, estimate)
            if (estimate > contextLimitTokens) throw Day8AgentFailure(
                usage.stats(createdAt, "context_overflow", promptTokens, historyBefore, maxRequestEstimate, items, answerTokens, contextLimitTokens, "Оценка запроса превысила лимит контекста."),
                "Запрос остановлен до отправки: превышен лимит контекста.",
            )

            val response = call(body, usage, createdAt, promptTokens, historyBefore, maxRequestEstimate, items, answerTokens)
            val output = response["output"]?.jsonArray ?: JsonArray(emptyList())
            val functionCalls = output.mapNotNull { (it as? JsonObject)?.takeIf { item -> item["type"]?.jsonPrimitive?.contentOrNull == "function_call" } }
            items.addAll(output)
            conversationStore.saveItems(conversationId, JsonArray(items.toList()))

            if (functionCalls.isEmpty()) {
                val answer = extractText(output).ifBlank { "Модель не вернула текстовый ответ." }
                answerTokens = Day8TokenCounter.countText(answer)
                return Day9AgentResult(
                    answer = answer,
                    transcript = conversationStore.transcript(JsonArray(items.toList())),
                    toolCalls = usage.toolCalls,
                    summary = summaryState.summary,
                    summarizedTurns = summaryState.summarizedTurns,
                    summaryTokens = Day8TokenCounter.countText(summaryState.summary),
                    summaryApiCalls = usage.summaryCalls,
                    tokenStats = usage.stats(createdAt, "success", promptTokens, historyBefore, maxRequestEstimate, items, answerTokens, contextLimitTokens),
                )
            }
            if (round == 8) throw Day8AgentFailure(
                usage.stats(createdAt, "api_error", promptTokens, historyBefore, maxRequestEstimate, items, answerTokens, contextLimitTokens, "Агент достиг лимита циклов инструментов."),
                "Агент достиг лимита циклов инструментов.",
            )

            functionCalls.forEach { call ->
                val name = call["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val args = call["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val result = runCatching { Day6Tools.execute(name, args) }
                    .getOrElse { "Ошибка инструмента: ${it.message ?: "не удалось выполнить"}" }
                items += buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", call["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("output", result)
                }
                usage.toolCalls++
                conversationStore.saveItems(conversationId, JsonArray(items.toList()))
            }
        }
        error("Агент не смог завершить ответ.")
    }

    suspend fun evaluateRecall(conversationId: String, strategy: Day9Strategy): Day9RecallResult {
        val items = conversationStore.loadItems(conversationId)
        val createdAt = Instant.now().toString()
        val historyBefore = Day8TokenCounter.estimateJson(items)
        val usage = UsageAccumulator()
        var maxEstimate = 0
        val summaryState = if (strategy == Day9Strategy.SUMMARY) {
            updateSummaryIfNeeded(conversationId, items.toMutableList(), memoryStore.load(conversationId), usage) { estimate ->
                maxEstimate = maxOf(maxEstimate, estimate)
            }
        } else Day9MemoryState()
        val baseInput = makeContext(items, strategy, summaryState)
        val answers = Day9Scenario.facts.map { fact ->
            val prompt = "Отвечай только фактами из диалога. Если не помнишь, прямо скажи «не помню». Вопрос: ${fact.question}"
            val questionItem = userMessage(prompt)
            val body = buildJsonObject {
                put("model", model)
                put("instructions", "Ты проверяешь память о проекте. Не додумывай отсутствующие данные.")
                put("input", JsonArray((baseInput + questionItem).toList()))
                put("max_output_tokens", 300)
            }
            val estimate = Day8TokenCounter.estimateJson(body)
            maxEstimate = maxOf(maxEstimate, estimate)
            if (estimate > contextLimitTokens) throw Day8AgentFailure(
                usage.stats(createdAt, "context_overflow", Day9Scenario.facts.sumOf { Day8TokenCounter.countText(it.question) }, historyBefore, maxEstimate, items, 0, contextLimitTokens, "Запрос проверки памяти превышает лимит контекста."),
                "Проверка памяти остановлена: превышен лимит контекста.",
            )
            val response = call(body, usage, createdAt, Day9Scenario.facts.sumOf { Day8TokenCounter.countText(it.question) }, historyBefore, maxEstimate, items, 0)
            val answer = extractText(response["output"]?.jsonArray ?: JsonArray(emptyList()))
            val normalized = normalize(answer)
            val correct = fact.accepted.any { normalize(it) in normalized }
            Day9RecallAnswer(fact.key, fact.question, answer, correct)
        }
        val allAnswers = answers.joinToString("\n") { it.answer }
        val stats = usage.stats(
            createdAt, "success", Day9Scenario.facts.sumOf { Day8TokenCounter.countText(it.question) }, historyBefore,
            maxEstimate, items, Day8TokenCounter.countText(allAnswers), contextLimitTokens,
        )
        return Day9RecallResult(strategy.name.lowercase(), answers, answers.count { it.correct }, summaryState.summary, Day8TokenCounter.countText(summaryState.summary), usage.summaryCalls, stats)
    }

    private suspend fun updateSummaryIfNeeded(
        conversationId: String,
        items: List<JsonElement>,
        oldState: Day9MemoryState,
        usage: UsageAccumulator,
        onEstimate: (Int) -> Unit,
    ): Day9MemoryState {
        val groups = splitTurns(items)
        val target = (groups.size - lastTurnsToKeep).coerceAtLeast(0)
        if (target <= oldState.summarizedTurns) return oldState
        val start = oldState.summarizedTurns.coerceAtMost(target)
        val newlyArchived = groups.subList(start, target).flatten()
        val payload = buildString {
            if (oldState.summary.isNotBlank()) append("Существующее резюме:\n${oldState.summary}\n\n")
            append("Новые сообщения для включения в резюме:\n")
            append(JsonArray(newlyArchived.toList()).toString())
        }
        val body = buildJsonObject {
            put("model", model)
            put("instructions", "Сожми предыдущие сообщения в точное краткое резюме для продолжения диалога. Сохрани имена, даты, суммы, ограничения, предпочтения, принятые решения и нерешённые вопросы. Не выдумывай факты. Отвечай только резюме.")
            put("input", payload)
            put("max_output_tokens", 900)
        }
        val estimate = Day8TokenCounter.estimateJson(body)
        onEstimate(estimate)
        if (estimate > contextLimitTokens) throw Day8AgentFailure(
            usage.stats(Instant.now().toString(), "context_overflow", 0, Day8TokenCounter.estimateJson(JsonArray(items)), estimate, items, 0, contextLimitTokens, "Слишком большой материал для суммаризации."),
            "Не удалось сжать историю: запрос суммаризации превышает лимит контекста.",
        )
        val response = call(body, usage, Instant.now().toString(), 0, Day8TokenCounter.estimateJson(JsonArray(items)), estimate, items, 0)
        val summary = extractText(response["output"]?.jsonArray ?: JsonArray(emptyList()))
        if (summary.isBlank()) throw IllegalStateException("Модель не вернула summary истории.")
        usage.summaryCalls++
        return memoryStore.save(conversationId, Day9MemoryState(summary, target, oldState.summaryApiCalls + 1))
    }

    private suspend fun call(
        body: JsonObject,
        usage: UsageAccumulator,
        createdAt: String,
        promptTokens: Int,
        historyBefore: Int,
        requestEstimate: Int,
        items: List<JsonElement>,
        answerTokens: Int,
    ): JsonObject {
        usage.apiCalls++
        val response = try {
            request(body)
        } catch (error: Exception) {
            val message = error.message ?: "Запрос к модели завершился ошибкой."
            val overflow = message.contains("context", true) || message.contains("token limit", true) || message.contains("input is too long", true)
            throw Day8AgentFailure(
                usage.stats(createdAt, if (overflow) "context_overflow" else "api_error", promptTokens, historyBefore, requestEstimate, items, answerTokens, contextLimitTokens, message),
                message,
            )
        }
        val apiUsage = response["usage"]?.jsonObject
        usage.inputTokens += apiUsage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        usage.cachedTokens += apiUsage?.get("input_tokens_details")?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
        usage.outputTokens += apiUsage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
        return response
    }

    private fun makeContext(items: List<JsonElement>, strategy: Day9Strategy, state: Day9MemoryState): List<JsonElement> {
        if (strategy == Day9Strategy.FULL) return items.toList()
        val groups = splitTurns(items)
        val input = mutableListOf<JsonElement>()
        if (state.summary.isNotBlank()) input += buildJsonObject {
            put("type", "message")
            put("role", "developer")
            put("content", buildJsonArray { add(buildJsonObject {
                put("type", "input_text")
                put("text", "Резюме предыдущей части диалога. Считай факты в нём контекстом; при конфликте уточняй у пользователя.\n${state.summary}")
            }) })
        }
        val from = state.summarizedTurns.coerceIn(0, groups.size)
        groups.drop(from).forEach(input::addAll)
        return input
    }

    private fun agentRequest(input: List<JsonElement>) = buildJsonObject {
        put("model", model)
        put("instructions", "Ты полезный русскоязычный помощник. Используй инструменты, если это помогает. Не выдумывай факты.")
        put("input", JsonArray(input.toList()))
        put("tools", Day6Tools.schemas)
        put("tool_choice", "auto")
    }

    private fun userMessage(text: String) = buildJsonObject {
        put("type", "message")
        put("role", "user")
        put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", text) }) })
    }

    private fun splitTurns(items: List<JsonElement>): List<List<JsonElement>> {
        val groups = mutableListOf<MutableList<JsonElement>>()
        items.forEach { element ->
            val item = element as? JsonObject
            val isUserTurn = item?.get("type")?.jsonPrimitive?.contentOrNull == "message" && item["role"]?.jsonPrimitive?.contentOrNull == "user"
            if (isUserTurn || groups.isEmpty()) groups.add(mutableListOf())
            groups.last() += element
        }
        return groups
    }

    private fun extractText(output: JsonArray): String = output.flatMap { (it as? JsonObject)?.get("content")?.jsonArray.orEmpty() }
        .filter { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "output_text" }
        .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        .joinToString("\n")

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private class UsageAccumulator {
        var apiCalls = 0
        var summaryCalls = 0
        var inputTokens = 0
        var cachedTokens = 0
        var outputTokens = 0
        var toolCalls = 0

        fun stats(createdAt: String, status: String, promptTokens: Int, historyBefore: Int, requestEstimate: Int, items: List<JsonElement>, answerTokens: Int, contextLimit: Int, error: String? = null): Day8TurnStats {
            val uncached = (inputTokens - cachedTokens).coerceAtLeast(0)
            val cost = (uncached * 2.0 + cachedTokens * 0.5 + outputTokens * 8.0) / 1_000_000.0
            return Day8TurnStats(
                createdAt = createdAt, status = status, currentPromptTokens = promptTokens,
                historyTokensBefore = historyBefore, estimatedRequestTokens = requestEstimate,
                historyTokensAfter = Day8TokenCounter.estimateJson(JsonArray(items.toList())),
                apiInputTokens = inputTokens, cachedInputTokens = cachedTokens, apiOutputTokens = outputTokens,
                finalAnswerTokensEstimate = answerTokens, apiCalls = apiCalls, estimatedCostUsd = cost,
                contextLimitTokens = contextLimit, error = error,
            )
        }
    }
}
