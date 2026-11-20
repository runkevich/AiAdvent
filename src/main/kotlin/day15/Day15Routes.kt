package day15

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

internal fun Route.day15Routes(agent: Day15Agent = Day15Agent()) {
    post("/api/day15/tasks") {
        val task = parse(call.receiveText())["task"]?.jsonPrimitive?.contentOrNull.orEmpty()
        runCatching { stateJson(agent.create(task)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Не удалось создать память задачи.") })
    }
    get("/api/day15/tasks/{id}") {
        val id = call.parameters["id"] ?: return@get bad(call, "Нужен id задачи.")
        runCatching { stateJson(agent.state(id)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, "Память задачи не найдена.", HttpStatusCode.NotFound) })
    }
    post("/api/day15/update") {
        val body = parse(call.receiveText()); val id = body["taskId"]?.jsonPrimitive?.contentOrNull; val layer = body["layer"]?.jsonPrimitive?.contentOrNull; val key = body["key"]?.jsonPrimitive?.contentOrNull; val value = body["value"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank() || layer !in setOf("long-term", "working") || key.isNullOrBlank()) return@post bad(call, "Нужны задача, слой и ключ факта.")
        runCatching { stateJson(agent.update(id, layer!!, key, value)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Не удалось обновить факт.", HttpStatusCode.Conflict) })
    }
    post("/api/day15/complete") {
        val id = parse(call.receiveText())["taskId"]?.jsonPrimitive?.contentOrNull ?: return@post bad(call, "Нужна задача.")
        runCatching { stateJson(agent.complete(id)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Не удалось завершить задачу.", HttpStatusCode.Conflict) })
    }
    post("/api/day15/ask") {
        val body = parse(call.receiveText()); val id = body["taskId"]?.jsonPrimitive?.contentOrNull; val key = body["key"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank() || key.isNullOrBlank()) return@post bad(call, "Нужны задача и ключ факта.")
        runCatching { agent.askCurrent(id, key) }.fold(
            { answer -> call.respondText(buildJsonObject { put("answer", answer.answer); answer.expected?.let { put("expected", it) }; put("stale", answer.stale); put("promptContainsOldValues", answer.promptContainsOldValues) }.toString(), ContentType.Application.Json) },
            { bad(call, it.message ?: "Не удалось спросить агента.", HttpStatusCode.BadGateway) },
        )
    }
}

private fun stateJson(state: Day15MemoryState) = buildJsonObject {
    put("id", state.id); put("task", state.task); put("completed", state.completed); put("longTerm", factsJson(state.longTerm)); put("working", factsJson(state.working)); put("decisions", changesJson(state.decisions)); put("changes", changesJson(state.changes))
}
private fun factsJson(values: Map<String, Day15Fact>) = JsonArray(values.values.map { fact -> buildJsonObject { put("key", fact.key); put("value", fact.value); put("recordedAt", fact.recordedAt); put("policy", fact.policy.name) } })
private fun changesJson(values: List<Day15Change>) = JsonArray(values.map { change -> buildJsonObject { put("at", change.at); put("layer", change.layer); put("key", change.key); change.from?.let { put("from", it) }; change.to?.let { put("to", it) }; put("action", change.action) } })
private fun parse(text: String) = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
private suspend fun bad(call: io.ktor.server.application.ApplicationCall, message: String, status: HttpStatusCode = HttpStatusCode.BadRequest) { call.respondText(buildJsonObject { put("error", message) }.toString(), ContentType.Application.Json, status) }
