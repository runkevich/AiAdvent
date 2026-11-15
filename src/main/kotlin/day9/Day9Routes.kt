package day9

import day7.Day7ConversationStore
import day8.Day8AgentFailure
import day8.Day8StatsStore
import day8.Day8TokenCounter
import day8.toJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal fun Route.day9Routes(
    conversationStore: Day7ConversationStore = Day7ConversationStore(),
    memoryStore: Day9MemoryStore = Day9MemoryStore(),
    agent: Day9Agent = Day9Agent(conversationStore, memoryStore),
    statsStore: Day8StatsStore = Day8StatsStore(),
) {
    val locks = ConcurrentHashMap<String, Mutex>()

    get("/api/day9/history") {
        val id = call.request.queryParameters["conversationId"]
        val strategy = parseStrategy(call.request.queryParameters["strategy"])
        if (id.isNullOrBlank() || strategy == null) {
            call.respondText("""{"error":"Укажите id диалога и стратегию."}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val result = runCatching {
            val items = conversationStore.loadItems(id)
            val state = if (strategy == Day9Strategy.SUMMARY) memoryStore.load(id) else Day9MemoryState()
            buildJsonObject {
                put("conversationId", id)
                put("strategy", strategy.name.lowercase())
                put("transcript", transcriptJson(conversationStore.transcript(items)))
                put("summary", state.summary)
                put("summarizedTurns", state.summarizedTurns)
                put("summaryTokens", Day8TokenCounter.countText(state.summary))
                put("summaryApiCalls", state.summaryApiCalls)
            }
        }
        result.fold(
            onSuccess = { call.respondText(it.toString(), ContentType.Application.Json) },
            onFailure = { call.respondText("""{"error":"Не удалось загрузить диалог."}""", ContentType.Application.Json, HttpStatusCode.BadRequest) },
        )
    }

    post("/api/day9/scenario") {
        val fullId = UUID.randomUUID().toString()
        val summaryId = UUID.randomUUID().toString()
        val items = Day9Scenario.seedItems()
        runCatching {
            conversationStore.saveItems(fullId, items)
            conversationStore.saveItems(summaryId, items)
            memoryStore.save(summaryId, Day9MemoryState())
        }.fold(
            onSuccess = {
                call.respondText(buildJsonObject {
                    put("fullId", fullId)
                    put("summaryId", summaryId)
                    put("messageCount", items.size)
                    put("facts", factsJson())
                }.toString(), ContentType.Application.Json)
            },
            onFailure = { call.respondText("""{"error":"Не удалось создать сценарий проверки."}""", ContentType.Application.Json, HttpStatusCode.InternalServerError) },
        )
    }

    post("/api/day9/chat") {
        val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val id = body?.get("conversationId")?.jsonPrimitive?.contentOrNull
        val strategy = parseStrategy(body?.get("strategy")?.jsonPrimitive?.contentOrNull)
        val prompt = body?.get("message")?.jsonPrimitive?.contentOrNull?.trim()
        if (id.isNullOrBlank() || strategy == null || prompt.isNullOrBlank() || prompt.length > 4_000) {
            call.respondText("""{"error":"Укажите id, стратегию и сообщение длиной до 4000 символов."}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val result = runCatching {
            locks.computeIfAbsent(id) { Mutex() }.withLock { agent.respond(id, prompt, strategy) }
        }
        result.fold(
            onSuccess = { value ->
                statsStore.append(id, value.tokenStats)
                call.respondText(buildJsonObject {
                    put("answer", value.answer)
                    put("toolCalls", value.toolCalls)
                    put("transcript", transcriptJson(value.transcript))
                    put("summary", value.summary)
                    put("summarizedTurns", value.summarizedTurns)
                    put("summaryTokens", value.summaryTokens)
                    put("summaryApiCalls", if (strategy == Day9Strategy.SUMMARY) memoryStore.load(id).summaryApiCalls else 0)
                    put("stats", value.tokenStats.toJson())
                }.toString(), ContentType.Application.Json)
            },
            onFailure = { error ->
                val status = if (error is Day8AgentFailure) error.stats.status else "api_error"
                if (error is Day8AgentFailure) statsStore.append(id, error.stats)
                val http = if (status == "context_overflow") HttpStatusCode.PayloadTooLarge else HttpStatusCode.BadGateway
                call.respondText(buildJsonObject {
                    put("error", error.message ?: "Не удалось выполнить запрос.")
                    put("status", status)
                    if (error is Day8AgentFailure) put("stats", error.stats.toJson())
                }.toString(), ContentType.Application.Json, http)
            },
        )
    }

    post("/api/day9/evaluate") {
        val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val fullId = body?.get("fullId")?.jsonPrimitive?.contentOrNull
        val summaryId = body?.get("summaryId")?.jsonPrimitive?.contentOrNull
        if (fullId.isNullOrBlank() || summaryId.isNullOrBlank()) {
            call.respondText("""{"error":"Сначала загрузите сценарий сравнения."}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val result = runCatching {
            val full = locks.computeIfAbsent(fullId) { Mutex() }.withLock { agent.evaluateRecall(fullId, Day9Strategy.FULL) }
            val compressed = locks.computeIfAbsent(summaryId) { Mutex() }.withLock { agent.evaluateRecall(summaryId, Day9Strategy.SUMMARY) }
            statsStore.append(fullId, full.stats)
            statsStore.append(summaryId, compressed.stats)
            buildJsonObject {
                put("details", factsJson())
                put("full", recallJson(full))
                put("summary", recallJson(compressed))
            }
        }
        result.fold(
            onSuccess = { call.respondText(it.toString(), ContentType.Application.Json) },
            onFailure = { error ->
                val status = if (error is Day8AgentFailure && error.stats.status == "context_overflow") HttpStatusCode.PayloadTooLarge else HttpStatusCode.BadGateway
                call.respondText(buildJsonObject { put("error", error.message ?: "Не удалось проверить детали.") }.toString(), ContentType.Application.Json, status)
            },
        )
    }
}

private fun parseStrategy(value: String?): Day9Strategy? = when (value?.lowercase()) {
    "full" -> Day9Strategy.FULL
    "summary" -> Day9Strategy.SUMMARY
    else -> null
}

private fun factsJson() = JsonArray(Day9Scenario.facts.map { fact -> buildJsonObject {
    put("key", fact.key)
    put("question", fact.question)
    put("accepted", JsonArray(fact.accepted.map(::JsonPrimitive)))
} })

private fun transcriptJson(entries: List<day7.Day7TranscriptEntry>) = JsonArray(entries.map { entry -> buildJsonObject {
    put("type", entry.type)
    put("text", entry.text)
    entry.toolName?.let { put("toolName", it) }
    entry.details?.let { put("details", it) }
} })

private fun recallJson(value: Day9RecallResult) = buildJsonObject {
    put("strategy", value.strategy)
    put("correctCount", value.correctCount)
    put("totalDetails", value.answers.size)
    put("summary", value.summary)
    put("summaryTokens", value.summaryTokens)
    put("summaryApiCalls", value.summaryApiCalls)
    put("stats", value.stats.toJson())
    put("answers", JsonArray(value.answers.map { answer -> buildJsonObject {
        put("key", answer.key)
        put("question", answer.question)
        put("answer", answer.answer)
        put("correct", answer.correct)
    } }))
}
