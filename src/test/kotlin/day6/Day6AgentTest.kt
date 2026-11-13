package day6

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Day6AgentTest {
    @Test
    fun `returns a response without calling tools`() = runBlocking {
        var requests = 0
        val agent = Day6Agent(request = {
            requests++
            response("resp_final", "Привет!")
        })

        val result = agent.respond("Привет")

        assertEquals("Привет!", result.answer)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(1, requests)
    }

    @Test
    fun `executes one requested tool and returns its result`() = runBlocking {
        val agent = Day6Agent(request = { body ->
            if (body["previous_response_id"] == null) {
                toolResponse("resp_tool", "call_calc", "calculator", """{"operation":"multiply","a":184,"b":27}""")
            } else {
                val returned = body["input"]!!.jsonArray.single().jsonObject["output"]!!.jsonPrimitive.content
                assertEquals("4968.0", returned)
                response("resp_final", "184 × 27 = 4968.")
            }
        })

        val result = agent.respond("Умножь 184 на 27")

        assertEquals("184 × 27 = 4968.", result.answer)
        assertEquals(1, result.toolCalls.size)
        assertEquals("calculator", result.toolCalls.single().name)
    }

    @Test
    fun `runs two tool rounds and sends results back to the model`() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val agent = Day6Agent(request = { body ->
            requests += body
            when (requests.size) {
                1 -> toolResponse("resp_one", "call_one", "search_tasks", """{"query":"сохранение контекста"}""")
                2 -> {
                    val returned = body["input"]!!.jsonArray.single().jsonObject["output"]!!.jsonPrimitive.content
                    assertTrue(returned.contains("День 7"))
                    toolResponse("resp_two", "call_two", "calculator", """{"operation":"add","a":7,"b":10}""")
                }
                else -> response("resp_final", "Номер дня 7 плюс 10 равен 17.")
            }
        })

        val result = agent.respond("Посчитай и добавь 32")

        assertEquals("Номер дня 7 плюс 10 равен 17.", result.answer)
        assertEquals(2, result.toolCalls.size)
        assertEquals(3, requests.size)
        assertEquals("resp_one", requests[1]["previous_response_id"]!!.jsonPrimitive.content)
        assertEquals(3, requests.first()["tools"]!!.jsonArray.size)
    }

    private fun response(id: String, text: String) = buildJsonObject {
        put("id", id)
        put("output", buildJsonArray { add(buildJsonObject {
            put("type", "message")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
        }) })
    }

    private fun toolResponse(id: String, callId: String, name: String, arguments: String) = buildJsonObject {
        put("id", id)
        put("output", buildJsonArray { add(buildJsonObject {
            put("type", "function_call")
            put("call_id", callId)
            put("name", name)
            put("arguments", arguments)
        }) })
    }
}
