package day9

import day7.Day7ConversationStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Day9AgentTest {
    @Test
    fun `summary mode archives old turns and sends summary with recent turns`() = runBlocking {
        val root = Files.createTempDirectory("day9-agent-test")
        try {
            val id = "4de6172e-faf8-4e8b-a2ca-280ca110d9d3"
            val conversationStore = Day7ConversationStore(root.resolve("history"))
            val memoryStore = Day9MemoryStore(root.resolve("memory"))
            conversationStore.saveItems(id, Day9Scenario.seedItems())
            val requests = mutableListOf<JsonObject>()
            val agent = Day9Agent(conversationStore, memoryStore, request = { body ->
                requests += body
                if (requests.size == 1) response("Сводка: космический день рождения Алисы, 24 мая, бюджет 20 000 рублей.")
                else response("План продолжения сохранён.")
            })

            val result = agent.respond(id, "Составь краткий итог", Day9Strategy.SUMMARY)

            assertEquals("План продолжения сохранён.", result.answer)
            assertEquals(2, requests.size) // вызов суммаризации и обычный ответ
            assertEquals(5, result.summarizedTurns) // из 11 ходов последние 6 остались дословно
            assertEquals(1, result.summaryApiCalls)
            assertTrue(result.summary.contains("24 мая"))
            val sentInput = requests.last()["input"]!!.jsonArray
            assertTrue(sentInput.first().jsonObject.toString().contains("Сводка: космический день рождения Алисы"))
            assertEquals(11, sentInput.count { it.jsonObject["role"]?.jsonPrimitive?.content in setOf("user", "assistant") })

            // Память summary хранится отдельно и переживает создание нового хранилища.
            val restored = Day9MemoryStore(root.resolve("memory")).load(id)
            assertEquals(result.summary, restored.summary)
            assertEquals(5, restored.summarizedTurns)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun response(text: String) = buildJsonObject {
        put("usage", buildJsonObject { put("input_tokens", 100); put("output_tokens", 20) })
        put("output", buildJsonArray { add(buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
        }) })
    }
}
