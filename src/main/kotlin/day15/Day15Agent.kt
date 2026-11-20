package day15

import day6.requestOpenAi
import java.time.Instant
import kotlinx.serialization.json.*

data class Day15Answer(val answer: String, val expected: String?, val stale: Boolean, val promptContainsOldValues: Boolean)

class Day15Agent(
    private val store: Day15MemoryStore = Day15MemoryStore(),
    private val request: suspend (JsonObject) -> JsonObject = ::requestOpenAi,
) {
    fun create(task: String) = Day15Scenario.initial(task).also(store::save)
    fun state(id: String) = store.load(id)
    fun update(id: String, layer: String, key: String, value: String?): Day15MemoryState {
        val current = store.load(id); check(!current.completed) { "Задача завершена: рабочая память уже очищена." }; val now = Instant.now().toString()
        val target = if (layer == "working") current.working else current.longTerm
        val old = target[key]
        val nextTarget = target.toMutableMap()
        val action: String
        if (value.isNullOrBlank()) { require(old != null) { "Факт для удаления не найден." }; nextTarget.remove(key); action = "removed" }
        else { nextTarget[key] = Day15Fact(key, value.trim(), now, old?.policy ?: if (layer == "working") Day15FactPolicy.REMOVE_WHEN_DONE else Day15FactPolicy.REPLACE); action = if (old == null) "added" else "replaced" }
        val change = Day15Change(now, layer, key, old?.value, value?.trim(), action)
        val decision = if (layer == "long-term" && key == "подход" && old != null) current.decisions + change else current.decisions
        val next = if (layer == "working") current.copy(working = nextTarget, decisions = decision, changes = current.changes + change) else current.copy(longTerm = nextTarget, decisions = decision, changes = current.changes + change)
        store.save(next); return next
    }
    fun complete(id: String): Day15MemoryState {
        val current = store.load(id); val now = Instant.now().toString(); val cleared = current.working.values.map { fact -> Day15Change(now, "working", fact.key, fact.value, null, "cleared_on_completion") }
        return current.copy(working = emptyMap(), completed = true, changes = current.changes + cleared).also(store::save)
    }
    suspend fun askCurrent(id: String, key: String): Day15Answer {
        val state = store.load(id); val current = state.longTerm[key] ?: state.working[key]
        val question = "Назови только актуальное значение факта «$key». Если факта нет, ответь только «не активно»."
        val context = promptFacts(state)
        val body = buildJsonObject { put("model", "gpt-4.1"); put("instructions", "Ты помощник по актуальной памяти. Не используй историю изменений. Отвечай коротко только актуальным значением."); put("input", JsonArray(listOf(developerMessage(context), userMessage(question)))); put("max_output_tokens", 80) }
        val response = request(body); val answer = extractText(response["output"]?.jsonArray ?: JsonArray(emptyList())).ifBlank { "Модель не вернула ответ." }
        val oldValues = state.changes.mapNotNull { it.from }.filter { it != current?.value }
        return Day15Answer(answer, current?.value, oldValues.any { answer.contains(it, ignoreCase = true) }, oldValues.any { context.contains(it, ignoreCase = true) })
    }
    /** The prompt intentionally contains current facts only; changes and replaced facts never enter it. */
    fun promptFacts(state: Day15MemoryState): String = buildString {
        append("Актуальная долговременная память:\n"); state.longTerm.values.forEach { append("- ${it.key}: ${it.value} (записано ${it.recordedAt})\n") }
        append("Актуальная рабочая память:\n"); if (state.working.isEmpty()) append("- очищена\n") else state.working.values.forEach { append("- ${it.key}: ${it.value} (записано ${it.recordedAt})\n") }
    }
    private fun userMessage(text: String) = message("user", "input_text", text)
    private fun developerMessage(text: String) = message("developer", "input_text", text)
    private fun message(role: String, type: String, text: String) = buildJsonObject { put("type", "message"); put("role", role); put("content", buildJsonArray { add(buildJsonObject { put("type", type); put("text", text) }) }) }
    private fun extractText(output: JsonArray) = output.flatMap { (it as? JsonObject)?.get("content")?.jsonArray.orEmpty() }.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
}
