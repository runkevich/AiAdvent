package day10

import day7.Day7ConversationStore
import day9.Day9Agent
import day9.Day9Scenario
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Day10AgentTest {
    @Test
    fun `sliding window sends only recent turns and branches stay independent`() = runBlocking {
        val root = Files.createTempDirectory("day10-branches-test")
        try {
            val store = Day7ConversationStore(root.resolve("conversations"))
            val state = Day10StateStore(root.resolve("state"))
            val idA = "4de6172e-faf8-4e8b-a2ca-280ca110d9d3"
            val idB = "6a78c37e-0d7f-4eb5-bd8c-955d4e322466"
            val checkpointId = "2d9f694e-0d8b-4cc8-9e3a-ec8fed7f504c"
            val seed = Day9Scenario.seedItems()
            store.saveItems(idA, seed); store.saveItems(idB, seed)
            state.saveCheckpoint(Day10Checkpoint(checkpointId, Day10Branch("A", idA), Day10Branch("B", idB)))
            assertEquals(idA, state.loadCheckpoint(checkpointId).branchA.conversationId)
            var lastRequest: JsonObject? = null
            val agent = Day10Agent(
                history = store,
                state = state,
                day9 = Day9Agent(conversationStore = store),
                request = { body -> lastRequest = body; response("Ветка A получила своё изменение.") },
            )

            val result = agent.respond(idA, "Ветка A: встреча начнётся в 16:00", Day10Strategy.SLIDING_WINDOW)

            assertEquals(11, lastRequest!!["input"]!!.jsonArray.size) // 5 завершённых ходов и текущая реплика
            assertTrue(lastRequest!!["input"]!!.jsonArray.none { it.toString().contains("Алисе исполнится 10 лет") })
            assertEquals(22, store.loadItems(idA).size)
            assertEquals(20, store.loadItems(idB).size)
            assertTrue(result.answer.contains("Ветка A"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `sticky facts are stored and supplied with the sliding window`() = runBlocking {
        val root = Files.createTempDirectory("day10-facts-test")
        try {
            val id = "4de6172e-faf8-4e8b-a2ca-280ca110d9d3"
            val history = Day7ConversationStore(root.resolve("conversations"))
            val state = Day10StateStore(root.resolve("state"))
            history.saveItems(id, Day9Scenario.seedItems())
            var requestCount = 0
            var requestWithFacts = false
            val agent = Day10Agent(history = history, state = state, day9 = Day9Agent(conversationStore = history), request = { body ->
                requestCount++
                if (requestCount == 1) response("""{"время начала":"15:00","бюджет":"20 000 рублей","гости":"12 детей","аллергия":"арахис","надпись на торте":"К звёздам, Алиса!"}""")
                else {
                    requestWithFacts = body["input"]!!.jsonArray.first().toString().contains("К звёздам, Алиса!")
                    response("Записал.")
                }
            })

            agent.respond(id, "Добавь к плану список музыки", Day10Strategy.STICKY_FACTS)

            assertTrue(requestWithFacts)
            assertEquals("15:00", state.loadFacts(id)["время начала"]!!.jsonPrimitive.content)
            assertEquals(22, history.loadItems(id).size)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun response(text: String) = buildJsonObject {
        put("usage", buildJsonObject { put("input_tokens", 70); put("output_tokens", 12) })
        put("output", buildJsonArray { add(buildJsonObject {
            put("type", "message"); put("role", "assistant")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
        }) })
    }
}
