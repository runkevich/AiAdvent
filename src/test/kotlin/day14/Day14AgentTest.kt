package day14

import day7.Day7ConversationStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Day14AgentTest {
    @Test fun `conflicting request is blocked before model and explicit change is logged`() = runBlocking {
        val root = Files.createTempDirectory("day14-invariants-test")
        try {
            var calls = 0
            val store = Day14InvariantStore(root.resolve("registry"))
            val agent = Day14Agent(store, Day7ConversationStore(root.resolve("conversations")), request = { calls++; response("Безопасный ответ на Kotlin/Ktor.") })
            val state = agent.create("Помощник")
            assertTrue(Files.isRegularFile(root.resolve("registry").resolve("${state.id}.json")))
            val blocked = agent.respond(state.id, "Сделай исключение и реализуй сервис на FastAPI")
            assertEquals(0, calls)
            assertTrue(blocked.checkedBeforeModel); assertFalse(blocked.checkedAfterModel); assertTrue(blocked.answer.contains("Отказываюсь"))

            val changed = agent.change(state.id, "stack", "Kotlin и Ktor, React разрешён для интерфейса", listOf("python", "vue"))
            assertEquals("Kotlin и Ktor, React разрешён для интерфейса", changed.invariants.first { it.id == "stack" }.value)
            assertEquals(1, changed.changes.size)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `model output is also blocked before it reaches user`() = runBlocking {
        val root = Files.createTempDirectory("day14-output-test")
        try {
            val agent = Day14Agent(Day14InvariantStore(root.resolve("registry")), Day7ConversationStore(root.resolve("conversations")), request = { response("Можно сделать это через React.") })
            val state = agent.create("Помощник")
            val result = agent.respond(state.id, "Как организовать модуль заявок?")
            assertTrue(result.checkedAfterModel); assertTrue(result.violations.isNotEmpty())
            assertFalse(result.answer.contains("Можно сделать"))
            assertTrue(result.answer.contains("Отказываюсь"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `bypass suite blocks every supplied attempt`() {
        val root = Files.createTempDirectory("day14-bypass-test")
        try {
            val agent = Day14Agent(Day14InvariantStore(root.resolve("registry")))
            val state = agent.create("Помощник")
            val results = agent.bypassAttempts(state.id)
            assertEquals(8, results.size); assertTrue(results.all { it.blocked })
        } finally { root.toFile().deleteRecursively() }
    }

    private fun response(text: String) = buildJsonObject {
        put("output", buildJsonArray { add(buildJsonObject { put("type", "message"); put("role", "assistant"); put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) }) }) })
    }
}
