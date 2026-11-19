package day13

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

enum class TaskStage { PLANNING, EXECUTION, VALIDATION, DONE }
enum class TaskAction { APPROVE_PLAN, COMPLETE_IMPLEMENTATION, PASS_VALIDATION }
data class Day13Event(val at: String, val type: String, val message: String)
data class Day13TaskState(
    val id: String,
    val title: String,
    val context: String,
    val stage: TaskStage,
    val currentStep: String,
    val expectedAction: TaskAction?,
    val paused: Boolean = false,
    val events: List<Day13Event> = emptyList(),
)

class Day13TaskStore(private val root: Path = Path.of("results", "day13", "tasks")) {
    private val json = Json { prettyPrint = true }
    @Synchronized fun save(state: Day13TaskState) {
        Files.createDirectories(root)
        val value = buildJsonObject {
            put("id", state.id); put("title", state.title); put("context", state.context); put("stage", state.stage.name); put("currentStep", state.currentStep); state.expectedAction?.let { put("expectedAction", it.name) }; put("paused", state.paused)
            put("events", JsonArray(state.events.map { event -> buildJsonObject { put("at", event.at); put("type", event.type); put("message", event.message) } }))
        }
        val path = pathFor(state.id); val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(JsonObject.serializer(), value))
        try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
    }
    @Synchronized fun load(id: String): Day13TaskState {
        val path = pathFor(id); require(Files.isRegularFile(path)) { "Задача не найдена." }
        val value = Json.parseToJsonElement(Files.readString(path)).jsonObject
        return Day13TaskState(
            id, value["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), value["context"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            TaskStage.valueOf(value["stage"]!!.jsonPrimitive.content), value["currentStep"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            value["expectedAction"]?.jsonPrimitive?.contentOrNull?.let(TaskAction::valueOf), value["paused"]?.jsonPrimitive?.booleanOrNull ?: false,
            value["events"]?.jsonArray.orEmpty().map { item -> item.jsonObject.let { Day13Event(it["at"]!!.jsonPrimitive.content, it["type"]!!.jsonPrimitive.content, it["message"]!!.jsonPrimitive.content) } },
        )
    }
    private fun pathFor(id: String): Path { require(runCatching { UUID.fromString(id) }.getOrNull()?.toString() == id.lowercase()) { "Некорректный id задачи." }; return root.resolve("$id.json") }
}
