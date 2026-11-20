package day15

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

enum class Day15FactPolicy { REPLACE, REMOVE_WHEN_DONE }
data class Day15Fact(val key: String, val value: String, val recordedAt: String, val policy: Day15FactPolicy)
data class Day15Change(val at: String, val layer: String, val key: String, val from: String?, val to: String?, val action: String)
data class Day15MemoryState(
    val id: String,
    val task: String,
    val longTerm: Map<String, Day15Fact>,
    val working: Map<String, Day15Fact>,
    val decisions: List<Day15Change>,
    val changes: List<Day15Change>,
    val completed: Boolean = false,
)

/** Current values and their audit history are deliberately persisted in separate files. */
class Day15MemoryStore(private val root: Path = Path.of("results", "day15", "memory")) {
    private val json = Json { prettyPrint = true }
    @Synchronized fun save(state: Day15MemoryState) {
        Files.createDirectories(root)
        write(state.id, "long-term", buildJsonObject { put("task", state.task); put("facts", factsJson(state.longTerm)); put("decisions", changesJson(state.decisions)); put("completed", state.completed) })
        write(state.id, "working", buildJsonObject { put("facts", factsJson(state.working)) })
        write(state.id, "history", changesJson(state.changes))
    }
    @Synchronized fun load(id: String): Day15MemoryState {
        val long = read(id, "long-term").jsonObject; val working = read(id, "working").jsonObject; val history = read(id, "history")
        return Day15MemoryState(id, long["task"]?.jsonPrimitive?.contentOrNull.orEmpty(), facts(long["facts"]), facts(working["facts"]), changes(long["decisions"]), changes(history), long["completed"]?.jsonPrimitive?.booleanOrNull ?: false)
    }
    private fun write(id: String, part: String, value: JsonElement) {
        val file = pathFor(id, part); val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(JsonElement.serializer(), value))
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
    }
    private fun read(id: String, part: String): JsonElement { val file = pathFor(id, part); require(Files.isRegularFile(file)) { "Память задачи не найдена." }; return Json.parseToJsonElement(Files.readString(file)) }
    private fun pathFor(id: String, part: String): Path { require(runCatching { UUID.fromString(id) }.getOrNull()?.toString() == id.lowercase()) { "Некорректный id памяти." }; return root.resolve("$id.$part.json") }
    private fun facts(value: JsonElement?) = value?.jsonArray.orEmpty().associate { item -> item.jsonObject.let { it["key"]!!.jsonPrimitive.content to Day15Fact(it["key"]!!.jsonPrimitive.content, it["value"]!!.jsonPrimitive.content, it["recordedAt"]!!.jsonPrimitive.content, Day15FactPolicy.valueOf(it["policy"]!!.jsonPrimitive.content)) } }
    private fun changes(value: JsonElement?) = value?.jsonArray.orEmpty().map { item -> item.jsonObject.let { Day15Change(it["at"]!!.jsonPrimitive.content, it["layer"]!!.jsonPrimitive.content, it["key"]!!.jsonPrimitive.content, it["from"]?.jsonPrimitive?.contentOrNull, it["to"]?.jsonPrimitive?.contentOrNull, it["action"]!!.jsonPrimitive.content) } }
    private fun factsJson(facts: Map<String, Day15Fact>) = JsonArray(facts.values.map { fact -> buildJsonObject { put("key", fact.key); put("value", fact.value); put("recordedAt", fact.recordedAt); put("policy", fact.policy.name) } })
    private fun changesJson(changes: List<Day15Change>) = JsonArray(changes.map { change -> buildJsonObject { put("at", change.at); put("layer", change.layer); put("key", change.key); change.from?.let { put("from", it) }; change.to?.let { put("to", it) }; put("action", change.action) } })
}

object Day15Scenario {
    fun initial(task: String): Day15MemoryState {
        val now = Instant.now().toString()
        fun long(key: String, value: String) = Day15Fact(key, value, now, Day15FactPolicy.REPLACE)
        fun work(key: String, value: String) = Day15Fact(key, value, now, Day15FactPolicy.REMOVE_WHEN_DONE)
        return Day15MemoryState(UUID.randomUUID().toString(), task.trim(), mapOf("срок" to long("срок", "20 декабря"), "статус" to long("статус", "планирование"), "версия" to long("версия", "v1"), "подход" to long("подход", "React")), mapOf("требование" to work("требование", "Подтверждать email перед отправкой формы"), "текущий шаг" to work("текущий шаг", "Подготовить макет формы")), emptyList(), emptyList())
    }
}
