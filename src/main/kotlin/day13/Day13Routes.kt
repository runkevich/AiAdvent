package day13

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

internal fun Route.day13Routes(machine: Day13StateMachine = Day13StateMachine()) {
    post("/api/day13/tasks") {
        val body = parse(call.receiveText()); val title = body["title"]?.jsonPrimitive?.contentOrNull.orEmpty(); val context = body["context"]?.jsonPrimitive?.contentOrNull.orEmpty()
        runCatching { taskJson(machine.create(title, context)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Не удалось создать задачу.") })
    }
    get("/api/day13/tasks/{id}") {
        val id = call.parameters["id"] ?: return@get bad(call, "Нужен id задачи.")
        runCatching { taskJson(machine.load(id)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, "Задача не найдена.", HttpStatusCode.NotFound) })
    }
    post("/api/day13/action") {
        val body = parse(call.receiveText()); val id = body["taskId"]?.jsonPrimitive?.contentOrNull; val action = body["action"]?.jsonPrimitive?.contentOrNull?.let { runCatching { TaskAction.valueOf(it) }.getOrNull() }
        if (id.isNullOrBlank() || action == null) return@post bad(call, "Нужны задача и допустимое действие.")
        runCatching { taskJson(machine.transition(id, action)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Переход запрещён.", HttpStatusCode.Conflict) })
    }
    post("/api/day13/pause") {
        val id = parse(call.receiveText())["taskId"]?.jsonPrimitive?.contentOrNull ?: return@post bad(call, "Нужна задача.")
        runCatching { taskJson(machine.pause(id)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Не удалось поставить на паузу.", HttpStatusCode.Conflict) })
    }
    post("/api/day13/resume") {
        val id = parse(call.receiveText())["taskId"]?.jsonPrimitive?.contentOrNull ?: return@post bad(call, "Нужна задача.")
        runCatching { taskJson(machine.resume(id)) }.fold({ call.respondText(it.toString(), ContentType.Application.Json) }, { bad(call, it.message ?: "Не удалось продолжить задачу.", HttpStatusCode.Conflict) })
    }
    post("/api/day13/assistant") {
        val body = parse(call.receiveText()); val id = body["taskId"]?.jsonPrimitive?.contentOrNull; val message = body["message"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank() || message.isNullOrBlank()) return@post bad(call, "Нужны задача и сообщение.")
        runCatching { machine.assistantRequest(id, message) }.fold(
            { reply -> call.respondText(buildJsonObject { put("message", reply.message); put("transitionApplied", reply.transitionApplied); put("state", taskJson(reply.state)) }.toString(), ContentType.Application.Json) },
            { bad(call, it.message ?: "Не удалось обработать сообщение.") },
        )
    }
}

private fun taskJson(state: Day13TaskState) = buildJsonObject {
    put("id", state.id); put("title", state.title); put("context", state.context); put("stage", state.stage.name); put("stageLabel", state.stage.uiLabel()); put("currentStep", state.currentStep); state.expectedAction?.let { put("expectedAction", it.name); put("expectedActionLabel", it.uiLabel()) }; put("paused", state.paused)
    put("events", JsonArray(state.events.map { event -> buildJsonObject { put("at", event.at); put("type", event.type); put("message", event.message) } }))
}
private fun parse(text: String) = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
private suspend fun bad(call: io.ktor.server.application.ApplicationCall, message: String, status: HttpStatusCode = HttpStatusCode.BadRequest) { call.respondText(buildJsonObject { put("error", message) }.toString(), ContentType.Application.Json, status) }
