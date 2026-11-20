package day15

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Day15AgentTest {
    @Test fun `newer facts replace old prompt values and history keeps audit trail`() = runBlocking {
        val root = Files.createTempDirectory("day15-memory-test")
        try {
            var lastBody: JsonObject? = null
            val store = Day15MemoryStore(root)
            val agent = Day15Agent(store, request = { body -> lastBody = body; response("25 декабря") })
            val state = agent.create("Форма записи")
            assertTrue(Files.isRegularFile(root.resolve("${state.id}.long-term.json")))
            assertTrue(Files.isRegularFile(root.resolve("${state.id}.working.json")))
            assertTrue(Files.isRegularFile(root.resolve("${state.id}.history.json")))

            agent.update(state.id, "long-term", "срок", "25 декабря")
            agent.update(state.id, "working", "требование", null)
            agent.update(state.id, "long-term", "подход", "Нативный JavaScript")
            val updated = agent.state(state.id)
            assertEquals("25 декабря", updated.longTerm["срок"]?.value)
            assertFalse("требование" in updated.working)
            assertEquals(1, updated.decisions.size)
            assertTrue(updated.changes.any { it.key == "срок" && it.from == "20 декабря" && it.to == "25 декабря" })
            assertFalse(agent.promptFacts(updated).contains("20 декабря"))
            assertFalse(agent.promptFacts(updated).contains("React"))

            val answer = agent.askCurrent(state.id, "срок")
            assertEquals("25 декабря", answer.answer)
            assertFalse(answer.stale); assertFalse(answer.promptContainsOldValues)
            assertFalse(lastBody!!.toString().contains("20 декабря"))

            val completed = agent.complete(state.id)
            assertTrue(completed.completed); assertTrue(completed.working.isEmpty()); assertEquals("25 декабря", completed.longTerm["срок"]?.value)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun response(text: String) = buildJsonObject {
        put("output", buildJsonArray { add(buildJsonObject { put("type", "message"); put("role", "assistant"); put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) }) }) })
    }
}
