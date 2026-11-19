package day12

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

internal fun Route.day12Routes(agent: Day12Agent = Day12Agent()) {
    post("/api/day12/profiles") {
        val body = parse(call.receiveText()); val name = body["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val preset = body["preset"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Day12Preset.valueOf(it) }.getOrNull() }
        if (preset == null) return@post bad(call, "Выберите набор предпочтений.")
        runCatching { profileJson(agent.createProfile(name, preset)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Не удалось создать профиль.") })
    }
    get("/api/day12/profiles/{id}") {
        val id = call.parameters["id"] ?: return@get bad(call, "Нужен id профиля.")
        runCatching { profileJson(agent.profile(id)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, "Профиль не найден.", HttpStatusCode.NotFound) })
    }
    post("/api/day12/chat") {
        val body = parse(call.receiveText()); val id = body["profileId"]?.jsonPrimitive?.contentOrNull; val text = body["message"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank() || text.isNullOrBlank()) return@post bad(call, "Нужны профиль и сообщение.")
        runCatching { agent.chat(id, body["day11SessionId"]?.jsonPrimitive?.contentOrNull, text) }.fold(
            { value -> call.respondText(buildJsonObject { put("answer", value.answer); put("profile", profileJson(value.profile)); put("updatedFields", JsonArray(value.updatedFields.map(::JsonPrimitive))); put("profileTokens", value.profileTokens) }.toString(), ContentType.Application.Json) },
            { bad(call, it.message ?: "Запрос не выполнен.", HttpStatusCode.BadGateway) },
        )
    }
    post("/api/day12/compare") {
        val body = parse(call.receiveText()); val question = body["question"]?.jsonPrimitive?.contentOrNull
        val ids = body["profileIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (question.isNullOrBlank() || ids.size !in 2..3) return@post bad(call, "Нужны один вопрос и от двух до трёх профилей.")
        runCatching { agent.compare(ids, body["day11SessionId"]?.jsonPrimitive?.contentOrNull, question) }.fold(
            { results -> call.respondText(evaluationsJson(results).toString(), ContentType.Application.Json) },
            { bad(call, it.message ?: "Сравнение не выполнено.", HttpStatusCode.BadGateway) },
        )
    }
    post("/api/day12/stability") {
        val body = parse(call.receiveText()); val id = body["profileId"]?.jsonPrimitive?.contentOrNull; val question = body["question"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank() || question.isNullOrBlank()) return@post bad(call, "Нужны профиль и вопрос.")
        runCatching { agent.stability(id, body["day11SessionId"]?.jsonPrimitive?.contentOrNull, question) }.fold(
            { value -> call.respondText(buildJsonObject { put("results", evaluationsJson(value.results)["results"]!!); value.firstFailure?.let { put("firstFailure", it) } }.toString(), ContentType.Application.Json) },
            { bad(call, it.message ?: "Проверка устойчивости не выполнена.", HttpStatusCode.BadGateway) },
        )
    }
}

private fun evaluationsJson(values: List<Day12Evaluation>) = buildJsonObject {
    put("results", JsonArray(values.map { value -> buildJsonObject { put("profile", profileJson(value.profile)); put("answer", value.answer); put("complies", value.complies); put("checks", buildJsonObject { value.checks.forEach { (name, passed) -> put(name, passed) } }) } }))
}
private fun profileJson(profile: Day12Profile) = buildJsonObject {
    put("id", profile.id); put("name", profile.name); put("length", profile.length.name); put("lengthLabel", profile.length.label); put("code", profile.code.name); put("codeLabel", profile.code.label); put("tone", profile.tone.name); put("toneLabel", profile.tone.label); put("sources", profile.sources)
}
private fun parse(text: String) = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
private suspend fun bad(call: io.ktor.server.application.ApplicationCall, message: String, status: HttpStatusCode = HttpStatusCode.BadRequest) { call.respondText(buildJsonObject { put("error", message) }.toString(), ContentType.Application.Json, status) }
