package day7

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

internal fun Route.day7Routes(
    store: Day7ConversationStore = Day7ConversationStore(),
    agent: Day7Agent = Day7Agent(store),
) {
    val locks = ConcurrentHashMap<String, Mutex>()

    get("/api/day7/history") {
        val conversationId = call.request.queryParameters["conversationId"]
        if (conversationId.isNullOrBlank()) {
            call.respondText("""{"error":"Укажите идентификатор диалога."}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@get
        }
        val result = runCatching {
            val items = store.loadItems(conversationId)
            historyJson(conversationId, store.transcript(items))
        }
        result.fold(
            onSuccess = { call.respondText(it.toString(), ContentType.Application.Json) },
            onFailure = { call.respondText("""{"error":"Некорректный идентификатор диалога."}""", ContentType.Application.Json, HttpStatusCode.BadRequest) },
        )
    }

    post("/api/day7/chat") {
        val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val conversationId = body?.get("conversationId")?.jsonPrimitive?.contentOrNull
        val prompt = body?.get("message")?.jsonPrimitive?.contentOrNull?.trim()
        if (conversationId.isNullOrBlank() || prompt.isNullOrBlank() || prompt.length > 4_000) {
            call.respondText("""{"error":"Укажите id диалога и сообщение длиной до 4000 символов."}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val result = runCatching {
            locks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                val answer = agent.respond(conversationId, prompt)
                val bodyJson = buildJsonObject {
                    put("answer", answer.answer)
                    put("toolCalls", answer.toolCalls)
                    put("transcript", transcriptJson(answer.transcript))
                }
                bodyJson
            }
        }
        result.fold(
            onSuccess = { call.respondText(it.toString(), ContentType.Application.Json) },
            onFailure = { error ->
                val message = error.message?.take(500) ?: "Не удалось выполнить запрос агента."
                call.respondText(buildJsonObject { put("error", message) }.toString(), ContentType.Application.Json, HttpStatusCode.BadGateway)
            },
        )
    }
}

private fun historyJson(conversationId: String, transcript: List<Day7TranscriptEntry>) = buildJsonObject {
    put("conversationId", conversationId)
    put("transcript", transcriptJson(transcript))
}

private fun transcriptJson(transcript: List<Day7TranscriptEntry>) = JsonArray(transcript.map { entry ->
    buildJsonObject {
        put("type", entry.type)
        put("text", entry.text)
        entry.toolName?.let { put("toolName", it) }
        entry.details?.let { put("details", it) }
    }
})
