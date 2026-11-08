package day5

import day3.Day3ResponsesResult
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Day5RoutesTest {
    @Test
    fun `full run endpoint starts once and exposes progress by run id`() = testApplication {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val root = Files.createTempDirectory("day5-full-route")
        val service = Day5ExperimentService(Day5ResponsesGateway { _, _ ->
            entered.complete(Unit)
            release.await()
            fakeResponse()
        })
        val mutex = kotlinx.coroutines.sync.Mutex()
        val manager = Day5RunManager(service, Day5ResultStore(root), mutex)
        application { routing { day5Routes(service, Day5ResultStore(root), mutex, manager) } }

        val first = client.post("/api/day5/experiment")
        entered.await()
        val started = Json.parseToJsonElement(first.bodyAsText()).jsonObject
        val runId = started["runId"]!!.jsonPrimitive.content
        val second = client.post("/api/day5/experiment")
        val progress = client.get("/api/day5/runs/$runId")
        release.complete(Unit)

        assertEquals(HttpStatusCode.Accepted, first.status)
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals(HttpStatusCode.OK, progress.status)
        assertEquals(15, Json.parseToJsonElement(progress.bodyAsText()).jsonObject["total"]!!.jsonPrimitive.content.toInt())
        assertTrue(Files.isRegularFile(root.resolve(runId).resolve("config.json")))
        var finished = false
        repeat(500) {
            if (!finished) {
                finished = manager.get(runId)?.status in setOf(Day5RunStatus.COMPLETED, Day5RunStatus.COMPLETED_WITH_ERRORS, Day5RunStatus.FAILED)
                if (!finished) kotlinx.coroutines.delay(10)
            }
        }
        assertTrue(finished)
    }

    @Test
    fun `config endpoint exposes configured models and Day 5 tasks`() = testApplication {
        application {
            routing {
                staticResources("/", "web")
                day5Routes(Day5ExperimentService(fakeGateway()), Day5ResultStore(Files.createTempDirectory("day5-config")))
            }
        }

        val response = client.get("/api/day5/config")
        val config = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().contains("application/json"))
        assertEquals(3, config["models"]!!.jsonArray.size)
        assertEquals(6, config["tasks"]!!.jsonArray.size)
        assertTrue(config["tasks"]!!.jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == "coffee_shop_name" })
        assertEquals(1024, config["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
        assertFalse(response.bodyAsText().contains("Authorization", ignoreCase = true))
    }

    @Test
    fun `test route rejects models outside Day 5 config`() = testApplication {
        var invoked = false
        application {
            routing {
                day5Routes(
                    Day5ExperimentService(Day5ResponsesGateway { _, _ -> invoked = true; fakeResponse() }),
                    Day5ResultStore(Files.createTempDirectory("day5-invalid")),
                )
            }
        }

        val response = client.post("/api/day5/test") {
            contentType(ContentType.Application.Json)
            setBody("""{"modelId":"arbitrary-model","taskId":"shopping"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertFalse(invoked)
    }

    @Test
    fun `single run writes immutable config and result files`() = testApplication {
        val resultRoot = Files.createTempDirectory("day5-results")
        application {
            routing { day5Routes(Day5ExperimentService(fakeGateway()), Day5ResultStore(resultRoot)) }
        }

        val response = client.post("/api/day5/test") {
            contentType(ContentType.Application.Json)
            setBody("""{"modelId":"gpt-6-luna","taskId":"shopping"}""")
        }
        val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val runId = payload["runId"]!!.jsonPrimitive.content
        val runDirectory = resultRoot.resolve(runId)
        val configText = Files.readString(runDirectory.resolve("config.json"))
        val resultText = Files.readString(runDirectory.resolve("result.json"))
        val result = payload["result"]!!.jsonObject
        val secondResponse = client.post("/api/day5/test") {
            contentType(ContentType.Application.Json)
            setBody("""{"modelId":"gpt-6-luna","taskId":"shopping"}""")
        }
        val secondRunId = Json.parseToJsonElement(secondResponse.bodyAsText()).jsonObject["runId"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(Files.isRegularFile(runDirectory.resolve("config.json")))
        assertTrue(Files.isRegularFile(runDirectory.resolve("result.json")))
        assertEquals("CORRECT", result["validationStatus"]!!.jsonPrimitive.content)
        assertEquals("gpt-6-luna-2026-10-15", result["actualModelId"]!!.jsonPrimitive.content)
        assertFalse(configText.contains("Authorization", ignoreCase = true))
        assertFalse(resultText.contains("Authorization", ignoreCase = true))
        assertFalse(resultText.contains("reasoning_item"))
        assertFalse(resultText.contains("rawApiResponse"))
        assertTrue(payload["resultFile"]!!.jsonPrimitive.content.endsWith("/result.json"))
        assertTrue(runId != secondRunId)
        assertTrue(Files.isRegularFile(resultRoot.resolve(secondRunId).resolve("result.json")))
    }

    @Test
    fun `concurrent repeat request is rejected while one is running`() = testApplication {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gateway = Day5ResponsesGateway { _, _ ->
            started.complete(Unit)
            release.await()
            fakeResponse()
        }
        application {
            routing { day5Routes(Day5ExperimentService(gateway), Day5ResultStore(Files.createTempDirectory("day5-lock"))) }
        }

        coroutineScope {
            val first = async {
                client.post("/api/day5/test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"modelId":"gpt-6-luna","taskId":"shopping"}""")
                }
            }
            started.await()
            val second = client.post("/api/day5/test") {
                contentType(ContentType.Application.Json)
                setBody("""{"modelId":"gpt-6-luna","taskId":"shopping"}""")
            }
            release.complete(Unit)
            first.await()
            assertEquals(HttpStatusCode.Conflict, second.status)
        }
    }

    private fun fakeGateway() = Day5ResponsesGateway { _, _ -> fakeResponse() }

    private fun fakeResponse() = Day3ResponsesResult(
        responseText = """{"answer":"200 PLN","explanation":"250 PLN less 20 percent is 200 PLN."}""",
        inputTokens = 100,
        outputTokens = 20,
        totalTokens = 120,
        reasoningTokens = 5,
        latencyMs = 250,
        estimatedCostUsd = null,
        apiStatus = 200,
        responseStatus = "completed",
        finishReason = null,
        incompleteReason = null,
        incompleteDetails = null,
        rawApiResponse = JsonNull,
        error = null,
        cachedInputTokens = 10,
        actualModelId = "gpt-6-luna-2026-10-15",
    )
}
