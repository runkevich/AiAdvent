package day10

import day7.Day7ConversationStore
import day9.Day9Scenario
import day8.toJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID
import kotlinx.serialization.json.*

internal fun Route.day10Routes(
    history: Day7ConversationStore = Day7ConversationStore(java.nio.file.Path.of("results", "day10", "conversations")),
    state: Day10StateStore = Day10StateStore(),
    agent: Day10Agent = Day10Agent(history, state),
) {
    post("/api/day10/scenario") {
        val checkpoint = UUID.randomUUID().toString()
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        val initial = Day9Scenario.seedItems()
        history.saveItems(a, initial); history.saveItems(b, initial)
        state.saveCheckpoint(Day10Checkpoint(checkpoint, Day10Branch("A", a), Day10Branch("B", b)))
        call.respondText(buildJsonObject {
            put("checkpointId", checkpoint); put("messageCount", initial.size)
            put("branchA", a); put("branchB", b)
            put("facts", JsonArray(Day9Scenario.facts.map { buildJsonObject { put("key", it.key); put("question", it.question); put("accepted", JsonArray(it.accepted.map(::JsonPrimitive))) } }))
        }.toString(), ContentType.Application.Json)
    }
    get("/api/day10/history") {
        val id = call.request.queryParameters["conversationId"]
        if (id.isNullOrBlank()) { call.respondText("""{"error":"Нет id диалога."}""", ContentType.Application.Json, HttpStatusCode.BadRequest); return@get }
        val result = runCatching { buildJsonObject {
            put("transcript", JsonArray(history.transcript(history.loadItems(id)).map { e -> buildJsonObject { put("type", e.type); put("text", e.text) } }))
            put("facts", state.loadFacts(id))
        } }
        result.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { call.respondText("""{"error":"Не удалось загрузить ветку."}""", ContentType.Application.Json, HttpStatusCode.BadRequest) })
    }
    post("/api/day10/chat") {
        val data = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val id = data?.get("conversationId")?.jsonPrimitive?.contentOrNull
        val strategy = runCatching { Day10Strategy.valueOf(data?.get("strategy")?.jsonPrimitive?.content?.uppercase().orEmpty()) }.getOrNull()
        val prompt = data?.get("message")?.jsonPrimitive?.contentOrNull?.trim()
        if (id.isNullOrBlank() || strategy == null || prompt.isNullOrBlank()) { call.respondText("""{"error":"Нужны ветка, стратегия и сообщение."}""", ContentType.Application.Json, HttpStatusCode.BadRequest); return@post }
        runCatching { agent.respond(id, prompt, strategy) }.fold(
            { value -> call.respondText(buildJsonObject {
                put("answer", value.answer); put("facts", value.facts); put("stats", buildJsonObject {
                    put("apiInputTokens", value.stats.inputTokens); put("apiOutputTokens", value.stats.outputTokens); put("apiCalls", value.stats.apiCalls); put("estimatedCostUsd", value.stats.estimatedCostUsd)
                }); put("transcript", JsonArray(value.transcript.map { e -> buildJsonObject { put("type", e.type); put("text", e.text) } }))
            }.toString(), ContentType.Application.Json) },
            { error -> call.respondText(buildJsonObject { put("error", error.message ?: "Запрос к модели не выполнен.") }.toString(), ContentType.Application.Json, HttpStatusCode.BadGateway) },
        )
    }
    post("/api/day10/evaluate") {
        val data = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val id = data?.get("conversationId")?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank()) { call.respondText("""{"error":"Выберите ветку."}""", ContentType.Application.Json, HttpStatusCode.BadRequest); return@post }
        val results = runCatching { Day10Strategy.entries.map { agent.evaluate(id, it) } }
        results.fold(
            onSuccess = { values ->
                val payload = buildJsonObject {
                    put("results", JsonArray(values.map { value -> buildJsonObject {
                        put("strategy", value.strategy)
                        put("correctCount", value.correctCount)
                        put("totalDetails", value.answers.size)
                        put("inputTokens", value.inputTokens)
                        put("outputTokens", value.outputTokens)
                        put("apiCalls", value.apiCalls)
                        put("estimatedCostUsd", value.estimatedCostUsd)
                        put("facts", value.facts)
                        put("answers", JsonArray(value.answers.map { answer -> buildJsonObject {
                            put("key", answer.key); put("answer", answer.answer); put("correct", answer.correct)
                        } }))
                    } }))
                }
                call.respondText(payload.toString(), ContentType.Application.Json)
            },
            onFailure = { error ->
                call.respondText(buildJsonObject { put("error", error.message ?: "Не удалось сравнить стратегии.") }.toString(), ContentType.Application.Json, HttpStatusCode.BadGateway)
            },
        )
    }
}
