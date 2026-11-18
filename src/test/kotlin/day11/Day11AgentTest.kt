package day11

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Day11AgentTest {
    @Test fun `layers are isolated, counted and cleared independently`() = runBlocking {
        val root = Files.createTempDirectory("day11-memory-test")
        try {
            val seen = mutableListOf<JsonObject>()
            val agent = Day11Agent(Day11MemoryStore(root), request = { body ->
                seen += body
                response(answerFor(body))
            })
            val id = agent.createSession(Day11Scenario.day10Facts)
            assertTrue(Files.isRegularFile(root.resolve("$id.short.json")))
            assertTrue(Files.isRegularFile(root.resolve("$id.working.json")))
            assertTrue(Files.isRegularFile(root.resolve("$id.long.json")))
            val (_, tokens) = agent.snapshot(id)
            assertTrue(tokens.shortTerm > 0); assertTrue(tokens.working > 0); assertTrue(tokens.longTerm > 0)

            val checks = agent.checkRecall(id)
            assertEquals(listOf("Марина", "Согласовать список гостей", "15:00"), checks.map { it.answer })
            agent.clear(id, MemoryLayer.WORKING)
            val afterWorkingClear = agent.checkRecall(id)
            assertEquals("не помню", afterWorkingClear[1].answer)
            assertEquals("Марина", afterWorkingClear[0].answer)
            assertEquals("15:00", afterWorkingClear[2].answer)

            agent.clear(id, MemoryLayer.SHORT_TERM); agent.clear(id, MemoryLayer.LONG_TERM)
            val final = agent.checkRecall(id)
            assertEquals(listOf("не помню", "не помню", "не помню"), final.map { it.answer })
            val prompt = seen.first()["input"]!!.jsonArray.toString()
            assertTrue(prompt.contains("Рабочая память")); assertTrue(prompt.contains("Долговременная память"))
            assertFalse(agent.snapshot(id).second.total > 0)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun answerFor(body: JsonObject): String {
        val text = body["input"]!!.jsonArray.toString()
        return when {
            text.contains("Как меня называть?") -> if (text.contains("Марина")) "Марина" else "не помню"
            text.contains("Во сколько начинается праздник?") -> if (text.contains("15:00")) "15:00" else "не помню"
            text.contains("следующее действие") -> if (text.contains("Согласовать список гостей")) "Согласовать список гостей" else "не помню"
            else -> "не помню"
        }
    }
    private fun response(text: String) = buildJsonObject {
        put("usage", buildJsonObject { put("input_tokens", 20); put("output_tokens", 4) })
        put("output", buildJsonArray { add(buildJsonObject { put("type", "message"); put("role", "assistant"); put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) }) }) })
    }
}
