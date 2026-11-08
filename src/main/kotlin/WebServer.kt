import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val HOST = "127.0.0.1"
private const val PORT = 8080

fun main() {
    println("Everyday AI Lab is available at http://$HOST:$PORT")
    val day2 = Day2ExperimentService()
    val day3 = Day3TestService()
    val day3RunEstimate = Day3RunEstimate()
    val operationMutex = Mutex()
    val runManager = ExperimentRunManager(day2, operationMutex)
    val day3RunManager = Day3RunManager(day3, operationMutex)
    embeddedServer(Netty, host = HOST, port = PORT) {
        routing {
            staticResources("/", "web")
            get("/api/day3/config") {
                call.respondText(day3.publicConfig().toString(), ContentType.Application.Json)
            }
            get("/api/day3/experiment/estimate") {
                call.respondText(day3RunEstimate.estimate().toString(), ContentType.Application.Json)
            }
            post("/api/day3/experiment") {
                val start = day3RunManager.start(day3RunEstimate.estimate())
                val state = start.state
                when {
                    state != null -> call.respondText(state.toJson().toString(), ContentType.Application.Json, HttpStatusCode.Accepted)
                    start.error?.contains("уже выполняется", ignoreCase = true) == true -> call.respondText(
                        Json.encodeToString(mapOf("error" to start.error)), ContentType.Application.Json, HttpStatusCode.Conflict,
                    )
                    else -> call.respondText(
                        Json.encodeToString(mapOf("error" to (start.error ?: "Не удалось подготовить эксперимент."))),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError,
                    )
                }
            }
            get("/api/day3/runs/active") {
                call.respondText(day3RunManager.active()?.toJson()?.toString() ?: "null", ContentType.Application.Json)
            }
            get("/api/day3/runs/{runId}") {
                val run = call.parameters["runId"]?.let(day3RunManager::get)
                if (run == null) {
                    call.respondText(
                        """{"error":"Эксперимент не найден."}""",
                        ContentType.Application.Json,
                        HttpStatusCode.NotFound,
                    )
                } else {
                    call.respondText(run.toJson().toString(), ContentType.Application.Json)
                }
            }
            post("/api/day3/test") {
                val request = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                val taskId = runCatching { request?.get("task_id")?.jsonPrimitive?.contentOrNull }.getOrNull()
                val effortValue = runCatching { request?.get("reasoning_effort")?.jsonPrimitive?.contentOrNull }.getOrNull()
                val methodValue = runCatching { request?.get("method")?.jsonPrimitive?.contentOrNull }.getOrNull()
                val effort = effortValue?.let(Day3ReasoningEffort::fromValue)
                val method = methodValue?.let(Day3Method::fromId)
                if (taskId.isNullOrBlank() || effort == null || method == null) {
                    call.respondText(
                        """{"error":"Укажите задачу, reasoning_effort (low или max) и метод direct."}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }
                if (Day3TaskCatalog.find(taskId) == null) {
                    call.respondText(
                        """{"error":"Выбрана неизвестная задача."}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }

                val result = operationMutex.withLock { day3.runTest(taskId, effort, method) }
                call.respondText(result.toString(), ContentType.Application.Json)
            }
            post("/api/day2/experiment") {
                val request = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                val prompt = runCatching { request?.get("prompt")?.jsonPrimitive?.contentOrNull }.getOrNull()
                if (prompt.isNullOrBlank()) {
                    call.respondText(
                        """{"error":"Введите базовый prompt перед запуском эксперимента."}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }

                val run = runManager.start(prompt)
                if (run == null) {
                    call.respondText(
                        """{"error":"Эксперимент уже выполняется."}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Conflict,
                    )
                } else {
                    call.respondText(run.toJson(includeResults = false).toString(), ContentType.Application.Json, HttpStatusCode.Accepted)
                }
            }
            get("/api/day2/runs/active") {
                val activeRun = runManager.active()
                call.respondText(activeRun?.toJson()?.toString() ?: "null", ContentType.Application.Json)
            }
            get("/api/day2/runs/{runId}") {
                val run = call.parameters["runId"]?.let(runManager::get)
                if (run == null) {
                    call.respondText(
                        """{"error":"Эксперимент не найден."}""",
                        ContentType.Application.Json,
                        HttpStatusCode.NotFound,
                    )
                } else {
                    call.respondText(run.toJson().toString(), ContentType.Application.Json)
                }
            }
            post("/api/day2/test") {
                val request = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                val prompt = runCatching { request?.get("prompt")?.jsonPrimitive?.contentOrNull }.getOrNull()
                val variant = runCatching { request?.get("variant")?.jsonPrimitive?.contentOrNull }.getOrNull()
                if (prompt.isNullOrBlank() || variant.isNullOrBlank()) {
                    call.respondText(
                        """{"error":"Заполните prompt и выберите вариант эксперимента."}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }
                if (Day2Variant.fromId(variant) == null) {
                    call.respondText(
                        """{"error":"Выбран неизвестный вариант эксперимента."}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }

                val result = operationMutex.withLock { day2.runTest(prompt, variant) }
                call.respondText(result.toString(), ContentType.Application.Json)
            }
        }
    }.start(wait = true)
}
