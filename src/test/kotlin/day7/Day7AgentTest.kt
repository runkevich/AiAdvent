package day7

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Day7AgentTest {
    @Test
    fun `restores tool calls and results after a new agent is created`() = runBlocking {
        val root = Files.createTempDirectory("day7-history-test")
        try {
            val conversationId = "4de6172e-faf8-4e8b-a2ca-280ca110d9d3"
            val store = Day7ConversationStore(root)
            var firstRequests = 0
            val firstAgent = Day7Agent(store = store, request = { body ->
                firstRequests++
                if (firstRequests == 1) {
                    assertEquals("input_text", body["input"]!!.jsonArray.first().jsonObject["content"]!!.jsonArray.first().jsonObject["type"]!!.jsonPrimitive.content)
                    functionResponse("resp_tool", "call_1", "calculator", """{"operation":"multiply","a":184,"b":27}""")
                } else {
                    val history = body["input"]!!.jsonArray
                    assertTrue(history.any { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" })
                    response("resp_answer", "184 × 27 = 4968.")
                }
            })

            val firstTurn = firstAgent.respond(conversationId, "Сколько будет 184 умножить на 27?")
            assertEquals("184 × 27 = 4968.", firstTurn.answer)
            assertEquals(1, firstTurn.toolCalls)

            // A new store and agent instance model an application restart.
            val restartedStore = Day7ConversationStore(root)
            val restartedAgent = Day7Agent(store = restartedStore, request = { body ->
                val history = body["input"]!!.jsonArray
                assertTrue(history.any { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" })
                assertTrue(history.any {
                    val item = it.jsonObject
                    item["type"]?.jsonPrimitive?.content == "function_call_output" && item["output"]?.jsonPrimitive?.content == "4968.0"
                })
                assertTrue(history.any { it.jsonObject["role"]?.jsonPrimitive?.content == "user" })
                response("resp_followup", "Да, помню: результат был 4968.")
            })

            val followUp = restartedAgent.respond(conversationId, "Какой результат мы получили раньше?")
            assertEquals("Да, помню: результат был 4968.", followUp.answer)
            assertTrue(followUp.transcript.any { it.type == "tool_call" })
            assertTrue(followUp.transcript.any { it.type == "tool_result" && it.text.contains("4968.0") })
            assertEquals(6, restartedStore.loadItems(conversationId).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun response(id: String, text: String) = buildJsonObject {
        put("id", id)
        put("output", buildJsonArray { add(buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
        }) })
    }

    private fun functionResponse(id: String, callId: String, name: String, arguments: String) = buildJsonObject {
        put("id", id)
        put("output", buildJsonArray { add(buildJsonObject {
            put("type", "function_call")
            put("call_id", callId)
            put("name", name)
            put("arguments", arguments)
        }) })
    }
}
