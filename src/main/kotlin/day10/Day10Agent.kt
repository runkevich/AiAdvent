package day10

import day6.Day6Tools
import day6.requestOpenAi
import day7.Day7ConversationStore
import day8.Day8TokenCounter
import day8.Day8TurnStats
import day9.Day9Agent
import day9.Day9RecallAnswer
import day9.Day9RecallResult
import day9.Day9Scenario
import day9.Day9Strategy
import java.time.Instant
import kotlinx.serialization.json.*

enum class Day10Strategy { SLIDING_WINDOW, STICKY_FACTS, SUMMARY }
data class Day10Metrics(val inputTokens: Int, val outputTokens: Int, val apiCalls: Int, val estimatedCostUsd: Double)
data class Day10Result(val answer: String, val transcript: List<day7.Day7TranscriptEntry>, val facts: JsonObject, val stats: Day10Metrics)
data class Day10Evaluation(val strategy: String, val answers: List<Day9RecallAnswer>, val correctCount: Int, val inputTokens: Int, val outputTokens: Int, val apiCalls: Int, val estimatedCostUsd: Double, val facts: JsonObject)

class Day10Agent(
    private val history: Day7ConversationStore = Day7ConversationStore(PathHelper.history),
    private val state: Day10StateStore = Day10StateStore(),
    private val day9: Day9Agent = Day9Agent(conversationStore = history),
    private val request: suspend (JsonObject) -> JsonObject = ::requestOpenAi,
    private val windowTurns: Int = 6,
) {
    suspend fun respond(id: String, prompt: String, strategy: Day10Strategy): Day10Result {
        if (strategy == Day10Strategy.SUMMARY) {
            val value = day9.respond(id, prompt, Day9Strategy.SUMMARY)
            return Day10Result(value.answer, value.transcript, JsonObject(emptyMap()), value.tokenStats.let { Day10Metrics(it.apiInputTokens, it.apiOutputTokens, it.apiCalls, it.estimatedCostUsd) })
        }
        val items = history.loadItems(id).toMutableList()
        items += userMessage(prompt)
        history.saveItems(id, JsonArray(items))
        val usage = Usage()
        val facts = if (strategy == Day10Strategy.STICKY_FACTS) updateFacts(id, items, prompt, usage) else JsonObject(emptyMap())
        val input = context(items, strategy, facts)
        var response = request(agentRequest(input)).also(usage::record)
        var output = response["output"]?.jsonArray ?: JsonArray(emptyList())
        val calls = output.mapNotNull { (it as? JsonObject)?.takeIf { item -> item["type"]?.jsonPrimitive?.contentOrNull == "function_call" } }
        items.addAll(output)
        if (calls.isNotEmpty()) {
            calls.forEach { call ->
                val result = runCatching { Day6Tools.execute(call["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), call["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()) }.getOrElse { "Ошибка инструмента: ${it.message}" }
                items += buildJsonObject { put("type", "function_call_output"); put("call_id", call["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()); put("output", result) }
            }
            var round = 0
            while (round++ < 8) {
                response = request(agentRequest(context(items, strategy, facts)))
                usage.record(response)
                output = response["output"]?.jsonArray ?: JsonArray(emptyList())
                items.addAll(output)
                val moreCalls = output.mapNotNull { (it as? JsonObject)?.takeIf { item -> item["type"]?.jsonPrimitive?.contentOrNull == "function_call" } }
                if (moreCalls.isEmpty()) break
                moreCalls.forEach { call ->
                    val result = runCatching { Day6Tools.execute(call["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), call["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()) }.getOrElse { "Ошибка инструмента: ${it.message}" }
                    items += buildJsonObject { put("type", "function_call_output"); put("call_id", call["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()); put("output", result) }
                }
            }
        }
        history.saveItems(id, JsonArray(items))
        return Day10Result(extractText(output), history.transcript(JsonArray(items)), facts, usage.metrics())
    }

    suspend fun evaluate(id: String, strategy: Day10Strategy): Day10Evaluation {
        if (strategy == Day10Strategy.SUMMARY) {
            val value: Day9RecallResult = day9.evaluateRecall(id, Day9Strategy.SUMMARY)
            return Day10Evaluation(strategy.name.lowercase(), value.answers, value.correctCount, value.stats.apiInputTokens, value.stats.apiOutputTokens, value.stats.apiCalls, value.stats.estimatedCostUsd, JsonObject(emptyMap()))
        }
        val items = history.loadItems(id)
        val usage = Usage()
        val facts = if (strategy == Day10Strategy.STICKY_FACTS) updateFacts(id, items, null, usage) else JsonObject(emptyMap())
        val base = context(items, strategy, facts)
        val answers = Day9Scenario.facts.map { fact ->
            val prompt = "Отвечай только фактами из контекста, не додумывай. Если не помнишь, скажи «не помню». Вопрос: ${fact.question}"
            val response = request(recallRequest(base + userMessage(prompt)))
            usage.record(response)
            val answer = extractText(response["output"]?.jsonArray ?: JsonArray(emptyList()))
            val normalized = answer.lowercase().filter(Char::isLetterOrDigit)
            Day9RecallAnswer(fact.key, fact.question, answer, fact.accepted.any { normalized.contains(it.lowercase().filter(Char::isLetterOrDigit)) })
        }
        val metrics = usage.metrics()
        return Day10Evaluation(strategy.name.lowercase(), answers, answers.count { it.correct }, metrics.inputTokens, metrics.outputTokens, metrics.apiCalls, metrics.estimatedCostUsd, facts)
    }

    private suspend fun updateFacts(id: String, items: List<JsonElement>, newPrompt: String?, usage: Usage): JsonObject {
        val oldFacts = state.loadFacts(id)
        val source = if (newPrompt == null || oldFacts.isEmpty()) JsonArray(items).toString() else newPrompt
        val body = buildJsonObject {
            put("model", "gpt-4.1")
            put("instructions", "Обнови память фактов проекта в JSON. Сохраняй только важные проверяемые детали: цели, даты, суммы, ограничения, решения, предпочтения и договорённости. Объединяй новые данные со старыми, исправляй только явно обновлённые факты, не выдумывай. Верни только JSON object из коротких ключей и значений.")
            put("input", "Текущие facts: $oldFacts\nНовые сообщения: $source")
            put("max_output_tokens", 700)
        }
        val response = request(body)
        usage.record(response)
        val text = extractText(response["output"]?.jsonArray ?: JsonArray(emptyList()))
        val parsed = runCatching { Json.parseToJsonElement(text.substringAfter("{").let { "{$it" }).jsonObject }.getOrElse { oldFacts }
        state.saveFacts(id, parsed)
        return parsed
    }

    private fun context(items: List<JsonElement>, strategy: Day10Strategy, facts: JsonObject): List<JsonElement> {
        val groups = splitTurns(items)
        val tail = groups.takeLast(windowTurns).flatten()
        if (strategy == Day10Strategy.SLIDING_WINDOW) return tail
        val factsItem = buildJsonObject { put("type", "message"); put("role", "developer"); put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", "Сохраняемые факты диалога в формате ключ-значение: $facts") }) }) }
        return listOf(factsItem) + tail
    }

    private fun splitTurns(items: List<JsonElement>): List<List<JsonElement>> {
        val result = mutableListOf<MutableList<JsonElement>>()
        items.forEach { element ->
            val item = element as? JsonObject
            if ((item?.get("type")?.jsonPrimitive?.contentOrNull == "message") && item["role"]?.jsonPrimitive?.contentOrNull == "user" || result.isEmpty()) result.add(mutableListOf())
            result.last().add(element)
        }
        return result
    }

    private fun agentRequest(input: List<JsonElement>) = buildJsonObject {
        put("model", "gpt-4.1"); put("instructions", "Ты русскоязычный помощник. Используй инструменты по необходимости, не выдумывай факты.")
        put("input", JsonArray(input)); put("tools", Day6Tools.schemas); put("tool_choice", "auto"); put("max_output_tokens", 700)
    }
    private fun recallRequest(input: List<JsonElement>) = buildJsonObject {
        put("model", "gpt-4.1")
        put("instructions", "Ты проверяешь память по диалогу. Отвечай кратко только тем, что явно известно из контекста. Не выдумывай.")
        put("input", JsonArray(input))
        put("max_output_tokens", 300)
    }
    private fun userMessage(text: String) = buildJsonObject { put("type", "message"); put("role", "user"); put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", text) }) }) }
    private fun extractText(output: JsonArray): String = output.flatMap { (it as? JsonObject)?.get("content")?.jsonArray.orEmpty() }.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
    private fun extractText(items: List<JsonElement>): String = extractText(JsonArray(items.flatMap { (it as? JsonObject)?.get("content")?.jsonArray.orEmpty() }))

    private class Usage {
        var input = 0; var output = 0; var calls = 0
        fun record(response: JsonObject) { val u = response["usage"]?.jsonObject; input += u?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0; output += u?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0; calls++ }
        fun metrics(): Day10Metrics = Day10Metrics(input, output, calls, (input * 2.0 + output * 8.0) / 1_000_000.0)
    }
}

private object PathHelper { val history: java.nio.file.Path = java.nio.file.Path.of("results", "day10", "conversations") }
