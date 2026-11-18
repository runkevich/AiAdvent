package day11

import day10.Day10StateStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

internal fun Route.day11Routes(agent: Day11Agent = Day11Agent(), day10: Day10StateStore = Day10StateStore()) {
    post("/api/day11/session") {
        val body = parse(call.receiveText())
        val imported = body["day10ConversationId"]?.jsonPrimitive?.contentOrNull?.let { runCatching { day10.loadFacts(it) }.getOrDefault(JsonObject(emptyMap())) } ?: JsonObject(emptyMap())
        call.respondText(snapshotJson(agent, agent.createSession(imported), imported.isNotEmpty()).toString(), ContentType.Application.Json)
    }
    get("/api/day11/state") {
        val id = call.request.queryParameters["sessionId"]
        if (id.isNullOrBlank()) return@get bad(call, "Нужен id сессии.")
        runCatching { snapshotJson(agent, id) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, "Сессия памяти не найдена.") })
    }
    post("/api/day11/remember") {
        val body = parse(call.receiveText()); val id = body["sessionId"]?.jsonPrimitive?.contentOrNull; val layer = layer(body); val text = body["text"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank() || layer == null || text.isNullOrBlank()) return@post bad(call, "Нужны сессия, слой и запись.")
        runCatching { agent.remember(id, layer, text); snapshotJson(agent, id) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Не удалось сохранить запись.") })
    }
    post("/api/day11/clear") {
        val body = parse(call.receiveText()); val id = body["sessionId"]?.jsonPrimitive?.contentOrNull; val layer = layer(body)
        if (id.isNullOrBlank() || layer == null) return@post bad(call, "Нужны сессия и слой.")
        runCatching { agent.clear(id, layer); snapshotJson(agent, id) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Не удалось очистить слой.") })
    }
    post("/api/day11/chat") {
        val body = parse(call.receiveText()); val id = body["sessionId"]?.jsonPrimitive?.contentOrNull; val prompt = body["message"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank() || prompt.isNullOrBlank()) return@post bad(call, "Нужны сессия и сообщение.")
        runCatching { agent.respond(id, prompt) }.fold({ value -> call.respondText(buildJsonObject { put("answer", value.answer); put("tokens", tokenJson(value.stats)); put("apiInputTokens", value.apiInputTokens); put("apiOutputTokens", value.apiOutputTokens) }.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Запрос не выполнен.", HttpStatusCode.BadGateway) })
    }
    post("/api/day11/check") {
        val body = parse(call.receiveText()); val id = body["sessionId"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank()) return@post bad(call, "Нужна сессия.")
        runCatching { agent.checkRecall(id) }.fold({ checks -> call.respondText(buildJsonObject { put("checks", JsonArray(checks.map { buildJsonObject { put("layer", it.layer.name.lowercase()); put("question", it.question); put("answer", it.answer); put("contextTokens", it.contextTokens) } })) }.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Проверка не выполнена.", HttpStatusCode.BadGateway) })
    }
}

private fun parse(text: String) = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
private fun layer(body: JsonObject) = body["layer"]?.jsonPrimitive?.contentOrNull?.uppercase()?.let { runCatching { MemoryLayer.valueOf(it) }.getOrNull() }
private fun snapshotJson(agent: Day11Agent, id: String, imported: Boolean = false): JsonObject {
    val (state, tokens) = agent.snapshot(id)
    return buildJsonObject { put("sessionId", id); put("shortTerm", state.shortTerm); put("working", state.working); put("longTerm", state.longTerm); put("tokens", tokenJson(tokens)); put("importedDay10Facts", imported) }
}
private fun tokenJson(tokens: Day11TokenBreakdown) = buildJsonObject { put("shortTerm", tokens.shortTerm); put("working", tokens.working); put("longTerm", tokens.longTerm); put("total", tokens.total) }
private suspend fun bad(call: io.ktor.server.application.ApplicationCall, message: String, status: HttpStatusCode = HttpStatusCode.BadRequest) { call.respondText(buildJsonObject { put("error", message) }.toString(), ContentType.Application.Json, status) }
