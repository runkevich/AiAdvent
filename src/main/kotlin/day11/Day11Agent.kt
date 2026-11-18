package day11

import day6.requestOpenAi
import day8.Day8TokenCounter
import java.util.UUID
import kotlinx.serialization.json.*

data class Day11TokenBreakdown(val shortTerm: Int, val working: Int, val longTerm: Int) {
    val total: Int get() = shortTerm + working + longTerm
}
data class Day11ChatResult(val answer: String, val stats: Day11TokenBreakdown, val apiInputTokens: Int, val apiOutputTokens: Int)
data class Day11Check(val layer: MemoryLayer, val question: String, val answer: String, val contextTokens: Int)

class Day11Agent(
    private val store: Day11MemoryStore = Day11MemoryStore(),
    private val request: suspend (JsonObject) -> JsonObject = ::requestOpenAi,
    private val maxShortMessages: Int = 6,
) {
    fun createSession(day10Facts: JsonObject): String {
        val id = UUID.randomUUID().toString()
        // The fallback is the Day 10 birthday scenario; a real Day 10 Facts object always takes precedence.
        val durableFacts = if (day10Facts.isNotEmpty()) day10Facts else Day11Scenario.day10Facts
        store.save(id, Day11MemoryState(
            shortTerm = JsonArray(listOf(userMessage("В этом диалоге называй меня Марина."), assistantMessage("Хорошо, Марина."))),
            working = Day11Scenario.workingMemory,
            longTerm = durableFacts,
        ))
        return id
    }

    fun snapshot(id: String): Pair<Day11MemoryState, Day11TokenBreakdown> {
        val state = store.load(id)
        return state to breakdown(state)
    }

    fun remember(id: String, layer: MemoryLayer, text: String): Day11MemoryState {
        require(text.isNotBlank()) { "Введите запись для памяти." }
        val current = store.load(id)
        val next = when (layer) {
            MemoryLayer.SHORT_TERM -> current.copy(shortTerm = JsonArray(current.shortTerm + developerMessage("Краткосрочная заметка пользователя: $text")))
            MemoryLayer.WORKING -> current.copy(working = updateObject(current.working, text))
            MemoryLayer.LONG_TERM -> current.copy(longTerm = updateObject(current.longTerm, text))
        }
        store.save(id, next)
        return next
    }

    fun clear(id: String, layer: MemoryLayer): Day11MemoryState {
        val current = store.load(id)
        val next = when (layer) {
            MemoryLayer.SHORT_TERM -> current.copy(shortTerm = JsonArray(emptyList()))
            MemoryLayer.WORKING -> current.copy(working = JsonObject(emptyMap()))
            MemoryLayer.LONG_TERM -> current.copy(longTerm = JsonObject(emptyMap()))
        }
        store.save(id, next)
        return next
    }

    suspend fun respond(id: String, prompt: String): Day11ChatResult {
        require(prompt.isNotBlank()) { "Введите сообщение." }
        var state = store.load(id)
        state = state.copy(shortTerm = JsonArray(state.shortTerm + userMessage(prompt)))
        val tokens = breakdown(state)
        val response = request(agentRequest(context(state)))
        val answer = extractText(response["output"]?.jsonArray ?: JsonArray(emptyList())).ifBlank { "Модель не вернула текстовый ответ." }
        state = state.copy(shortTerm = JsonArray(state.shortTerm + assistantMessage(answer)))
        store.save(id, state)
        val usage = response["usage"]?.jsonObject
        return Day11ChatResult(answer, tokens, usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0, usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0)
    }

    /** These questions isolate each layer. Run them before and after clearing a layer. */
    suspend fun checkRecall(id: String): List<Day11Check> {
        val state = store.load(id)
        return Day11Scenario.checks.map { check ->
            val input = context(state) + userMessage("Отвечай только данными из контекста. Если данных нет, скажи «не помню». ${check.question}")
            val body = agentRequest(input, 160)
            val response = request(body)
            Day11Check(check.layer, check.question, extractText(response["output"]?.jsonArray ?: JsonArray(emptyList())), Day8TokenCounter.estimateJson(JsonArray(input)))
        }
    }

    private fun context(state: Day11MemoryState): List<JsonElement> = buildList {
        // Rule 1: only the latest dialog messages go in; task fields and durable facts do not.
        addAll(state.shortTerm.takeLast(maxShortMessages))
        // Rule 2: the complete active task snapshot goes in on every task request.
        if (state.working.isNotEmpty()) add(developerMessage("Рабочая память текущей задачи (используй только для этой задачи): ${state.working}"))
        // Rule 3: verified long-lived facts go in as reference; never infer new facts from them.
        if (state.longTerm.isNotEmpty()) add(developerMessage("Долговременная память — проверенные facts из Дня 10: ${state.longTerm}"))
    }

    private fun breakdown(state: Day11MemoryState): Day11TokenBreakdown = Day11TokenBreakdown(
        shortTerm = Day8TokenCounter.estimateJson(JsonArray(state.shortTerm.takeLast(maxShortMessages))),
        working = state.working.takeIf { it.isNotEmpty() }?.let { Day8TokenCounter.estimateJson(JsonArray(listOf(developerMessage("Рабочая память текущей задачи: $it")))) } ?: 0,
        longTerm = state.longTerm.takeIf { it.isNotEmpty() }?.let { Day8TokenCounter.estimateJson(JsonArray(listOf(developerMessage("Долговременная память: $it")))) } ?: 0,
    )

    private fun updateObject(existing: JsonObject, text: String): JsonObject {
        val pair = text.split(":", limit = 2)
        val key = pair.first().trim().ifBlank { "заметка" }
        val value = pair.getOrElse(1) { text }.trim()
        return JsonObject(existing.toMutableMap().also { it[key] = JsonPrimitive(value) })
    }
    private fun agentRequest(input: List<JsonElement>, maxOutput: Int = 700) = buildJsonObject {
        put("model", "gpt-4.1")
        put("instructions", "Ты русскоязычный помощник. Опирайся только на предоставленные слои памяти; если сведений нет, честно скажи «не помню». Не сохраняй ничего сам: приложение явно выбирает слой памяти.")
        put("input", JsonArray(input)); put("max_output_tokens", maxOutput)
    }
    private fun userMessage(text: String) = message("user", "input_text", text)
    private fun assistantMessage(text: String) = message("assistant", "output_text", text)
    private fun developerMessage(text: String) = message("developer", "input_text", text)
    private fun message(role: String, type: String, text: String) = buildJsonObject { put("type", "message"); put("role", role); put("content", buildJsonArray { add(buildJsonObject { put("type", type); put("text", text) }) }) }
    private fun extractText(output: JsonArray) = output.flatMap { (it as? JsonObject)?.get("content")?.jsonArray.orEmpty() }.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
}

object Day11Scenario {
    val day10Facts = buildJsonObject {
        put("время начала", "15:00"); put("бюджет", "20 000 рублей"); put("гости", "12 детей"); put("аллергия", "арахис"); put("надпись на торте", "К звёздам, Алиса!")
    }
    val workingMemory = buildJsonObject { put("задача", "Подготовить день рождения Алисы"); put("этап", "планирование"); put("следующее действие", "Согласовать список гостей") }
    data class RecallCheck(val layer: MemoryLayer, val question: String)
    val checks = listOf(
        RecallCheck(MemoryLayer.SHORT_TERM, "Как меня называть?"),
        RecallCheck(MemoryLayer.WORKING, "Какое следующее действие по задаче?"),
        RecallCheck(MemoryLayer.LONG_TERM, "Во сколько начинается праздник?"),
    )
}
