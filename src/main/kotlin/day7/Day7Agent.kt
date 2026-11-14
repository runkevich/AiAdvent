package day7

import day6.Day6Tools
import day6.requestOpenAi
import kotlinx.serialization.json.*

data class Day7AgentResult(
    val answer: String,
    val transcript: List<Day7TranscriptEntry>,
    val toolCalls: Int,
)

/** Replays and extends a locally persisted Responses API history on every turn. */
class Day7Agent(
    private val store: Day7ConversationStore = Day7ConversationStore(),
    private val model: String = "gpt-4.1",
    private val request: suspend (JsonObject) -> JsonObject = ::requestOpenAi,
    private val maxToolRounds: Int = 8,
) {
    suspend fun respond(conversationId: String, prompt: String): Day7AgentResult {
        require(prompt.isNotBlank()) { "Введите сообщение." }
        val items = store.loadItems(conversationId).toMutableList()
        items += buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", prompt) }) })
        }
        persist(conversationId, items)
        var toolCallCount = 0

        repeat(maxToolRounds + 1) { round ->
            val response = request(buildRequest(items))
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
                val fullHistory = JsonArray(items.toList())
                return Day7AgentResult(answer, store.transcript(fullHistory), toolCallCount)
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
