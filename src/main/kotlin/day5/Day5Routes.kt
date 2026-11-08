package day5

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun Route.day5Routes(
    service: Day5ExperimentService,
    resultStore: Day5ResultStore,
    requestMutex: Mutex = Mutex(),
    runManager: Day5RunManager = Day5RunManager(service, resultStore, requestMutex),
) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    get("/api/day5/config") {
        val config = buildJsonObject {
            put("api", Day5Config.api)
            put("reasoningEffort", Day5Config.reasoningEffort)
            put("maxOutputTokens", Day5Config.maxOutputTokens)
            put("models", JsonArray(Day5Config.models.map { model ->
                buildJsonObject {
                    put("id", model.id)
                    put("displayName", model.displayName)
                    put("tier", model.tier.name)
                }
            }))
            put("tasks", JsonArray(Day5TaskCatalog.all.map { task ->
                buildJsonObject {
                    put("id", task.id)
                    put("category", task.category)
                    put("text", task.text)
                }
            }))
        }
        call.respondText(config.toString(), ContentType.Application.Json)
    }

    post("/api/day5/test") {
        val request = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val modelId = request.string("modelId")
        val taskId = request.string("taskId")
        val model = modelId?.let(Day5Config::findModel)
        val task = taskId?.let(Day5TaskCatalog::find)
        if (model == null || task == null) {
            call.respondText(
                """{"error":"Выберите модель и задачу из списка Day 5."}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        if (!requestMutex.tryLock()) {
            call.respondText(
                """{"error":"Тестовый запрос Day 5 уже выполняется."}""",
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            return@post
        }

        try {
            val files = try {
                resultStore.createRun(model, task)
            } catch (_: Exception) {
                call.respondText(
                    """{"error":"Не удалось создать папку для сохранения результата."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
                return@post
            }
            val result = service.runTest(model.id, task.id)
            val resultFile = try {
                resultStore.saveResult(files, result)
            } catch (_: Exception) {
                val unsaved = buildJsonObject {
                    put("runId", files.runId)
                    put("result", json.encodeToJsonElement(Day5ExperimentResult.serializer(), result))
                    put("resultFile", JsonNull)
                    put("persistenceError", "Результат получен, но не удалось сохранить файл.")
                }
                call.respondText(unsaved.toString(), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                return@post
            }
            val response = buildJsonObject {
                put("runId", files.runId)
                put("resultFile", resultFile)
                put("result", json.encodeToJsonElement(Day5ExperimentResult.serializer(), result))
            }
            call.respondText(response.toString(), ContentType.Application.Json)
        } finally {
            requestMutex.unlock()
        }
    }

    post("/api/day5/experiment") {
        val start = runManager.start()
        when {
            start.state != null -> call.respondText(start.state.toJson().toString(), ContentType.Application.Json, HttpStatusCode.Accepted)
            start.error?.contains("выполняется", ignoreCase = true) == true -> call.respondText(
                buildJsonObject { put("error", start.error) }.toString(), ContentType.Application.Json, HttpStatusCode.Conflict,
            )
            else -> call.respondText(
                buildJsonObject { put("error", start.error ?: "Не удалось запустить эксперимент.") }.toString(),
                ContentType.Application.Json, HttpStatusCode.InternalServerError,
            )
        }
    }

    get("/api/day5/runs/active") {
        call.respondText(runManager.active()?.toJson()?.toString() ?: "null", ContentType.Application.Json)
    }

    get("/api/day5/runs") {
        call.respondText(JsonArray(runManager.all().map { it.toHistoryJson() }).toString(), ContentType.Application.Json)
    }

    get("/api/day5/runs/{runId}") {
        val state = call.parameters["runId"]?.let(runManager::get)
        if (state == null) call.respondText("""{"error":"Эксперимент не найден."}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        else call.respondText(state.toJson().toString(), ContentType.Application.Json)
    }

    get("/api/day5/runs/{runId}/results") {
        val state = call.parameters["runId"]?.let(runManager::get)
        if (state == null) call.respondText("""{"error":"Эксперимент не найден."}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        else call.respondText(JsonArray(state.records).toString(), ContentType.Application.Json)
    }

    get("/api/day5/runs/{runId}/analytics") {
        val state = call.parameters["runId"]?.let(runManager::get)
        if (state == null) call.respondText("""{"error":"Эксперимент не найден."}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        else call.respondText(Day5Analytics.calculate(state).toString(), ContentType.Application.Json)
    }
}

private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
