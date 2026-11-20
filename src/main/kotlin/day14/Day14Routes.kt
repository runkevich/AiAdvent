package day14

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

internal fun Route.day14Routes(agent: Day14Agent = Day14Agent()) {
    post("/api/day14/projects") {
        val project = parse(call.receiveText())["project"]?.jsonPrimitive?.contentOrNull.orEmpty()
        runCatching { stateJson(agent.create(project)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Не удалось создать реестр.") })
    }
    get("/api/day14/projects/{id}") {
        val id = call.parameters["id"] ?: return@get bad(call, "Нужен id проекта.")
        runCatching { stateJson(agent.state(id)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, "Реестр не найден.", HttpStatusCode.NotFound) })
    }
    post("/api/day14/chat") {
        val body = parse(call.receiveText()); val id = body["projectId"]?.jsonPrimitive?.contentOrNull; val prompt = body["message"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank() || prompt.isNullOrBlank()) return@post bad(call, "Нужны проект и запрос.")
        runCatching { agent.respond(id, prompt) }.fold(
            { answer -> call.respondText(buildJsonObject { put("answer", answer.answer); put("checkedBeforeModel", answer.checkedBeforeModel); put("checkedAfterModel", answer.checkedAfterModel); put("violations", JsonArray(answer.violations.map { violationJson(it) })) }.toString(), ContentType.Application.Json) },
            { bad(call, it.message ?: "Запрос не выполнен.", HttpStatusCode.BadGateway) },
        )
    }
    post("/api/day14/change") {
        val body = parse(call.receiveText()); val id = body["projectId"]?.jsonPrimitive?.contentOrNull; val invariantId = body["invariantId"]?.jsonPrimitive?.contentOrNull; val replacement = body["replacement"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank() || invariantId.isNullOrBlank() || replacement.isNullOrBlank()) return@post bad(call, "Нужны проект, инвариант и новое значение.")
        val terms = body["forbiddenTerms"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        runCatching { stateJson(agent.change(id, invariantId, replacement, terms)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Не удалось изменить инвариант.", HttpStatusCode.Conflict) })
    }
    post("/api/day14/bypass-test") {
        val id = parse(call.receiveText())["projectId"]?.jsonPrimitive?.contentOrNull ?: return@post bad(call, "Нужен проект.")
        runCatching { agent.bypassAttempts(id) }.fold(
            { attempts -> call.respondText(buildJsonObject { put("attempts", JsonArray(attempts.map { item -> buildJsonObject { put("prompt", item.prompt); put("blocked", item.blocked); put("explanation", item.explanation) } })) }.toString(), ContentType.Application.Json) },
            { bad(call, it.message ?: "Не удалось выполнить проверку.") },
        )
    }
}

private fun stateJson(state: Day14InvariantState) = buildJsonObject {
    put("id", state.id); put("project", state.project)
    put("invariants", JsonArray(state.invariants.map { value -> buildJsonObject { put("id", value.id); put("title", value.title); put("value", value.value); put("forbiddenTerms", JsonArray(value.forbiddenTerms.map(::JsonPrimitive))) } }))
    put("changes", JsonArray(state.changes.map { value -> buildJsonObject { put("at", value.at); put("invariantId", value.invariantId); put("from", value.from); put("to", value.to) } }))
}
private fun violationJson(value: Day14Violation) = buildJsonObject { put("invariantId", value.invariant.id); put("title", value.invariant.title); put("term", value.term) }
private fun parse(text: String) = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
private suspend fun bad(call: io.ktor.server.application.ApplicationCall, message: String, status: HttpStatusCode = HttpStatusCode.BadRequest) { call.respondText(buildJsonObject { put("error", message) }.toString(), ContentType.Application.Json, status) }
