package day14

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

data class Day14Invariant(val id: String, val title: String, val value: String, val forbiddenTerms: List<String>)
data class Day14Change(val at: String, val invariantId: String, val from: String, val to: String)
data class Day14InvariantState(val id: String, val project: String, val invariants: List<Day14Invariant>, val changes: List<Day14Change> = emptyList())

/** Invariants have their own files: dialogue persistence cannot alter this registry. */
class Day14InvariantStore(private val root: Path = Path.of("results", "day14", "invariants")) {
    private val json = Json { prettyPrint = true }
    @Synchronized fun save(state: Day14InvariantState) {
        Files.createDirectories(root)
        val content = buildJsonObject {
            put("id", state.id); put("project", state.project)
            put("invariants", JsonArray(state.invariants.map(::invariantJson)))
            put("changes", JsonArray(state.changes.map { item -> buildJsonObject { put("at", item.at); put("invariantId", item.invariantId); put("from", item.from); put("to", item.to) } }))
        }
        val file = pathFor(state.id); val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(JsonObject.serializer(), content))
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
    }
    @Synchronized fun load(id: String): Day14InvariantState {
        val data = Json.parseToJsonElement(Files.readString(pathFor(id))).jsonObject
        return Day14InvariantState(
            id, data["project"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            data["invariants"]?.jsonArray.orEmpty().map { item -> item.jsonObject.let { Day14Invariant(it["id"]!!.jsonPrimitive.content, it["title"]!!.jsonPrimitive.content, it["value"]!!.jsonPrimitive.content, it["forbiddenTerms"]!!.jsonArray.map(JsonElement::jsonPrimitive).map(JsonPrimitive::content)) } },
            data["changes"]?.jsonArray.orEmpty().map { item -> item.jsonObject.let { Day14Change(it["at"]!!.jsonPrimitive.content, it["invariantId"]!!.jsonPrimitive.content, it["from"]!!.jsonPrimitive.content, it["to"]!!.jsonPrimitive.content) } },
        )
    }
    private fun invariantJson(value: Day14Invariant) = buildJsonObject { put("id", value.id); put("title", value.title); put("value", value.value); put("forbiddenTerms", JsonArray(value.forbiddenTerms.map(::JsonPrimitive))) }
    private fun pathFor(id: String): Path { require(runCatching { UUID.fromString(id) }.getOrNull()?.toString() == id.lowercase()) { "Некорректный id реестра." }; return root.resolve("$id.json") }
}

object Day14Defaults {
    fun state(project: String) = Day14InvariantState(UUID.randomUUID().toString(), project.trim(), listOf(
        Day14Invariant("architecture", "Архитектура", "Модульный монолит на Kotlin/Ktor", listOf("микросервис", "microservice", "node.js", "express", "django", "fastapi", "spring boot")),
        Day14Invariant("stack", "Технологический стек", "Kotlin, Ktor и нативный JavaScript без SPA-фреймворков", listOf("python", "react", "angular", "vue", "typescript")),
        Day14Invariant("business", "Бизнес-правило", "Персональные данные обрабатываются только в ЕС", listOf("сша", "usa", "us-east", "американск")),
    ))
}
