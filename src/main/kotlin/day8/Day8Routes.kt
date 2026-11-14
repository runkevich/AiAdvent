package day8

import day7.Day7Agent
import day7.Day7ConversationStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

private const val MODEL_CONTEXT_LIMIT = 1_047_576
private const val DEMO_CONTEXT_LIMIT = 8_000

internal fun Route.day8Routes(
    conversationStore: Day7ConversationStore = Day7ConversationStore(),
    agent: Day7Agent = Day7Agent(conversationStore),
    statsStore: Day8StatsStore = Day8StatsStore(),
) {
    val locks = ConcurrentHashMap<String, Mutex>()

    get("/api/day8/config") {
        call.respondText(buildJsonObject {
            put("model", "gpt-4.1")
            put("modelContextLimit", MODEL_CONTEXT_LIMIT)
            put("demoContextLimit", DEMO_CONTEXT_LIMIT)
            put("inputUsdPerMillion", 2.0)
            put("cachedInputUsdPerMillion", 0.5)
            put("outputUsdPerMillion", 8.0)
            put("encoding", "o200k_base")
        }.toString(), ContentType.Application.Json)
    }

    get("/api/day8/metrics") {
        val conversationId = call.request.queryParameters["conversationId"]
        if (conversationId.isNullOrBlank()) {
            call.respondText("""{"error":"Укажите идентификатор диалога."}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val result = runCatching { statsStore.load(conversationId) }
        result.fold(
            onSuccess = { turns ->
                val values = turns.mapNotNull { (it as? JsonObject)?.toDay8TurnStats() }
                call.respondText(buildJsonObject {
                    put("turns", turns)
                    put("turnCount", values.size)
                    put("totalApiInputTokens", values.sumOf { it.apiInputTokens })
                    put("totalApiOutputTokens", values.sumOf { it.apiOutputTokens })
                    put("totalEstimatedCostUsd", values.sumOf { it.estimatedCostUsd })
                }.toString(), ContentType.Application.Json)
            },
            onFailure = { call.respondText("""{"error":"Некорректный идентификатор диалога."}""", ContentType.Application.Json, HttpStatusCode.BadRequest) },
        )
    }

    post("/api/day8/chat") {
        val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val conversationId = body?.get("conversationId")?.jsonPrimitive?.contentOrNull
        val prompt = body?.get("message")?.jsonPrimitive?.contentOrNull?.trim()
        val mode = body?.get("contextMode")?.jsonPrimitive?.contentOrNull ?: "demo"
        val contextLimit = when (mode) {
            "model" -> MODEL_CONTEXT_LIMIT
            "demo" -> DEMO_CONTEXT_LIMIT
            else -> null
        }
        if (conversationId.isNullOrBlank() || prompt.isNullOrBlank() || prompt.length > 20_000 || contextLimit == null) {
            call.respondText("""{"error":"Укажите id диалога, корректный режим и сообщение до 20000 символов."}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }

        val result = runCatching {
            locks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                try {
                    val answer = agent.respond(conversationId, prompt, contextLimit)
                    val stats = answer.tokenStats ?: error("Статистика токенов не сформирована.")
                    statsStore.append(conversationId, stats)
                    buildJsonObject {
                        put("answer", answer.answer)
                        put("toolCalls", answer.toolCalls)
                        put("transcript", transcriptJson(answer.transcript))
                        put("stats", stats.toJson())
                        put("status", "success")
                    }
                } catch (failure: Day8AgentFailure) {
                    statsStore.append(conversationId, failure.stats)
                    buildJsonObject {
                        put("error", failure.message ?: "Запрос не выполнен.")
                        put("transcript", transcriptJson(conversationStore.transcript(conversationStore.loadItems(conversationId))))
                        put("stats", failure.stats.toJson())
                        put("status", failure.stats.status)
                    }
                }
            }
        }
        result.fold(
            onSuccess = { payload ->
                val status = if (payload["status"]?.jsonPrimitive?.content == "context_overflow") HttpStatusCode.PayloadTooLarge
                    else if (payload["status"]?.jsonPrimitive?.content == "api_error") HttpStatusCode.BadGateway
                    else HttpStatusCode.OK
                call.respondText(payload.toString(), ContentType.Application.Json, status)
            },
            onFailure = { error ->
                call.respondText(buildJsonObject { put("error", error.message ?: "Не удалось выполнить запрос.") }.toString(), ContentType.Application.Json, HttpStatusCode.BadGateway)
            },
        )
    }
}

private fun transcriptJson(entries: List<day7.Day7TranscriptEntry>) = JsonArray(entries.map { entry ->
    buildJsonObject {
        put("type", entry.type)
        put("text", entry.text)
        entry.toolName?.let { put("toolName", it) }
        entry.details?.let { put("details", it) }
    }
})
