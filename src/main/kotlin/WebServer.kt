import day2.Day2ExperimentService
import day2.Day2Variant
import day2.ExperimentRunManager
import day3.Day3Method
import day3.Day3ReasoningEffort
import day3.Day3RunEstimate
import day3.Day3RunManager
import day3.Day3TaskCatalog
import day3.Day3TestService
import day4.Day4Config
import day4.Day4RunManager
import day4.Day4TaskCatalog
import day5.Day5ExperimentService
import day5.Day5ResultStore
import day5.Day5RunManager
import day5.day5Routes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val HOST = "127.0.0.1"
private val PORT = System.getenv("PORT")?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 8080

fun main() {
    println("Everyday AI Lab is available at http://$HOST:$PORT")
    val day2 = Day2ExperimentService()
    val day3 = Day3TestService()
    val day3RunEstimate = Day3RunEstimate()
    val operationMutex = Mutex()
    val runManager = ExperimentRunManager(day2, operationMutex)
    val day3RunManager = Day3RunManager(day3, operationMutex)
    val day4RunManager = Day4RunManager(day4.Day4ExperimentService(), operationMutex)
    val day5 = Day5ExperimentService()
    val day5ResultStore = Day5ResultStore()
    val day5RequestMutex = Mutex()
    val day5RunManager = Day5RunManager(day5, day5ResultStore, day5RequestMutex)
    embeddedServer(Netty, host = HOST, port = PORT) {
        routing {
            staticResources("/", "web")
            day5Routes(day5, day5ResultStore, day5RequestMutex, day5RunManager)
            get("/api/day4/config") {
                val config = buildJsonObject {
                    put("model", Day4Config.model)
                    put("temperatures", JsonArray(Day4Config.temperatures.map(::JsonPrimitive)))
                    put("top_p", Day4Config.topP)
                    put("max_completion_tokens", Day4Config.maxCompletionTokens)
                    put("tasks", JsonArray(Day4TaskCatalog.all.map { task -> buildJsonObject {
                        put("id", task.id)
                        put("category", task.category)
                        put("text", task.prompt)
                    } }))
                }
                call.respondText(config.toString(), ContentType.Application.Json)
            }
            post("/api/day4/test") {
                val request = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                val taskId = runCatching { request?.get("task_id")?.jsonPrimitive?.contentOrNull }.getOrNull()
                val temperature = runCatching { request?.get("temperature")?.jsonPrimitive?.doubleOrNull }.getOrNull()
                val repetitions = runCatching { request?.get("repetitions")?.jsonPrimitive?.intOrNull ?: 1 }.getOrNull() ?: 1
                if (taskId == null || Day4TaskCatalog.find(taskId) == null || temperature == null || temperature !in Day4Config.temperatures || repetitions !in 1..5) {
                    call.respondText("""{"error":"Укажите доступное задание, температуру и от 1 до 5 повторений."}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                val start = day4RunManager.startTest(taskId, temperature, repetitions)
                respondDay4Start(call, start.state, start.error)
            }
            post("/api/day4/experiment") {
                val start = day4RunManager.startFull()
                respondDay4Start(call, start.state, start.error)
            }
            post("/api/day4/pilot") {
                val start = day4RunManager.startPilot()
                respondDay4Start(call, start.state, start.error)
            }
            get("/api/day4/runs/active") {
                call.respondText(day4RunManager.active()?.toJson()?.toString() ?: "null", ContentType.Application.Json)
            }
            get("/api/day4/runs/{runId}") {
                val state = call.parameters["runId"]?.let(day4RunManager::get)
                if (state == null) call.respondText("""{"error":"Эксперимент не найден."}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                else call.respondText(state.toJson().toString(), ContentType.Application.Json)
            }
            get("/api/day4/runs/{runId}/results") {
                val state = call.parameters["runId"]?.let(day4RunManager::get)
                if (state == null) call.respondText("""{"error":"Эксперимент не найден."}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                else call.respondText(JsonArray(state.results).toString(), ContentType.Application.Json)
            }
            get("/api/day4/runs/{runId}/analytics") {
                val state = call.parameters["runId"]?.let(day4RunManager::get)
                if (state == null) call.respondText("""{"error":"Эксперимент не найден."}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                else call.respondText((state.summary ?: day4.Day4Analytics.summarize(state.results)).toString(), ContentType.Application.Json)
            }
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

private suspend fun respondDay4Start(
    call: ApplicationCall,
    state: day4.Day4RunState?,
    error: String?,
) {
    if (state != null) {
        call.respondText(state.toJson().toString(), ContentType.Application.Json, HttpStatusCode.Accepted)
    } else {
        val status = if (error?.contains("уже выполняется", ignoreCase = true) == true) HttpStatusCode.Conflict else HttpStatusCode.InternalServerError
        call.respondText(Json.encodeToString(mapOf("error" to (error ?: "Не удалось запустить эксперимент."))), ContentType.Application.Json, status)
    }
}
