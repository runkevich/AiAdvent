package day12

import day6.requestOpenAi
import day7.Day7ConversationStore
import day11.Day11MemoryState
import day11.Day11MemoryStore
import day8.Day8TokenCounter
import kotlinx.serialization.json.*

data class Day12ChatResult(val answer: String, val profile: Day12Profile, val updatedFields: List<String>, val profileTokens: Int)
data class Day12Evaluation(val profile: Day12Profile, val answer: String, val checks: Map<String, Boolean>) { val complies get() = checks.values.all { it } }
data class Day12StabilityResult(val results: List<Day12Evaluation>) { val firstFailure: Int? get() = results.indexOfFirst { !it.complies }.takeIf { it >= 0 }?.plus(1) }

class Day12Agent(
    private val profiles: Day12ProfileStore = Day12ProfileStore(),
    private val memories: Day11MemoryStore = Day11MemoryStore(),
    private val conversations: Day7ConversationStore = Day7ConversationStore(java.nio.file.Path.of("results", "day12", "conversations")),
    private val request: suspend (JsonObject) -> JsonObject = ::requestOpenAi,
) {
    fun createProfile(name: String, preset: Day12Preset) = profiles.create(name, preset)
    fun profile(id: String) = profiles.load(id)

    suspend fun chat(profileId: String, day11SessionId: String?, text: String): Day12ChatResult {
        require(text.isNotBlank()) { "Введите сообщение." }
        val before = profiles.load(profileId)
        val (profile, updated) = learnFromDialogue(before, text)
        if (updated.isNotEmpty()) profiles.save(profile)
        val items = conversations.loadItems(profileId).toMutableList()
        items += userMessage(text)
        val response = request(agentRequest(profile, memory(day11SessionId), items.takeLast(8)))
        val answer = extractText(response["output"]?.jsonArray ?: JsonArray(emptyList())).ifBlank { "Модель не вернула текстовый ответ." }
        items += assistantMessage(answer)
        conversations.saveItems(profileId, JsonArray(items))
        return Day12ChatResult(answer, profile, updated, profileTokens(profile))
    }

    suspend fun compare(profileIds: List<String>, day11SessionId: String?, question: String): List<Day12Evaluation> {
        require(question.isNotBlank()) { "Введите общий вопрос." }
        return profileIds.distinct().map { profileId -> ask(profiles.load(profileId), memory(day11SessionId), question) }
    }

    suspend fun stability(profileId: String, day11SessionId: String?, question: String): Day12StabilityResult {
        require(question.isNotBlank()) { "Введите вопрос для проверки устойчивости." }
        val profile = profiles.load(profileId); val state = memory(day11SessionId)
        return Day12StabilityResult((1..10).map { number -> ask(profile, state, "Запрос №$number. $question") })
    }

    private suspend fun ask(profile: Day12Profile, memory: Day11MemoryState, question: String): Day12Evaluation {
        val response = request(agentRequest(profile, memory, listOf(userMessage(question))))
        val answer = extractText(response["output"]?.jsonArray ?: JsonArray(emptyList())).ifBlank { "Модель не вернула текстовый ответ." }
        return Day12Evaluation(profile, answer, ProfileCompliance.check(profile, answer))
    }

    /** Deterministic learning keeps profile updates inspectable and does not spend a second model call. */
    private fun learnFromDialogue(profile: Day12Profile, text: String): Pair<Day12Profile, List<String>> {
        val lower = text.lowercase(); var changed = profile; val fields = mutableListOf<String>(); val sources = profile.sources.toMutableMap()
        fun source(field: String) { sources[field] = JsonPrimitive("из диалога"); fields += field }
        when {
            listOf("кратко", "короче", "покороче").any(lower::contains) && profile.length != ResponseLength.CONCISE -> { changed = changed.copy(length = ResponseLength.CONCISE); source("length") }
            listOf("подробно", "развёрнуто", "развернуто", "детально").any(lower::contains) && profile.length != ResponseLength.DETAILED -> { changed = changed.copy(length = ResponseLength.DETAILED); source("length") }
        }
        when {
            lower.contains("без кода") && profile.code != CodePreference.WITHOUT_CODE -> { changed = changed.copy(code = CodePreference.WITHOUT_CODE); source("code") }
            (lower.contains("с кодом") || lower.contains("покажи код")) && profile.code != CodePreference.WITH_CODE -> { changed = changed.copy(code = CodePreference.WITH_CODE); source("code") }
        }
        when {
            (lower.contains("неформально") || lower.contains("на ты")) && profile.tone != TonePreference.INFORMAL -> { changed = changed.copy(tone = TonePreference.INFORMAL); source("tone") }
            (lower.contains("формально") || lower.contains("на вы")) && profile.tone != TonePreference.FORMAL -> { changed = changed.copy(tone = TonePreference.FORMAL); source("tone") }
        }
        return changed.copy(sources = JsonObject(sources)) to fields
    }

    private fun memory(sessionId: String?): Day11MemoryState = sessionId?.let { runCatching { memories.load(it) }.getOrNull() } ?: Day11MemoryState()
    private fun agentRequest(profile: Day12Profile, memory: Day11MemoryState, recentDialogue: List<JsonElement>) = buildJsonObject {
        put("model", "gpt-4.1")
        put("instructions", "Ты русскоязычный ассистент. Профиль пользователя обязателен для каждого ответа. Следуй ему, а факты бери только из слоёв памяти. Если данных нет, скажи «не помню».")
        put("input", JsonArray(listOf(profileMessage(profile)) + memoryMessages(memory) + recentDialogue))
        put("max_output_tokens", if (profile.length == ResponseLength.CONCISE) 240 else 900)
    }
    private fun profileMessage(profile: Day12Profile) = developerMessage("Профиль пользователя «${profile.name}»: отвечай ${profile.length.label}; ${profile.code.label}; ${profile.tone.label}. ${if (profile.code == CodePreference.WITH_CODE) "Добавь небольшой кодовый пример в fenced code block." else "Не добавляй код, псевдокод или fenced code block."} Используй обращение ${if (profile.tone == TonePreference.FORMAL) "«вы»" else "«ты»"} хотя бы один раз в ответе.")
    private fun memoryMessages(state: Day11MemoryState): List<JsonElement> = buildList {
        addAll(state.shortTerm.takeLast(6))
        if (state.working.isNotEmpty()) add(developerMessage("Рабочая память текущей задачи: ${state.working}"))
        if (state.longTerm.isNotEmpty()) add(developerMessage("Долговременная память: ${state.longTerm}"))
    }
    private fun profileTokens(profile: Day12Profile) = Day8TokenCounter.estimateJson(JsonArray(listOf(profileMessage(profile))))
    private fun userMessage(text: String) = message("user", "input_text", text)
    private fun assistantMessage(text: String) = message("assistant", "output_text", text)
    private fun developerMessage(text: String) = message("developer", "input_text", text)
    private fun message(role: String, type: String, text: String) = buildJsonObject { put("type", "message"); put("role", role); put("content", buildJsonArray { add(buildJsonObject { put("type", type); put("text", text) }) }) }
    private fun extractText(output: JsonArray) = output.flatMap { (it as? JsonObject)?.get("content")?.jsonArray.orEmpty() }.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
}

object ProfileCompliance {
    fun check(profile: Day12Profile, answer: String): Map<String, Boolean> {
        val words = answer.trim().split(Regex("\\s+")).filter(String::isNotBlank).size
        val length = if (profile.length == ResponseLength.CONCISE) words <= 120 else words >= 130
        val code = answer.contains("```") == (profile.code == CodePreference.WITH_CODE)
        val lower = answer.lowercase()
        // Java's \b does not reliably recognise Cyrillic word boundaries, so tokenize explicitly.
        val terms = lower.split(Regex("[^\\p{L}]+")).filter(String::isNotBlank)
        val tone = if (profile.tone == TonePreference.FORMAL) terms.any { it in setOf("вы", "вам", "вас") || it.startsWith("ваш") } else terms.any { it in setOf("ты", "тебе", "тебя") || it.startsWith("твой") }
        return linkedMapOf("длина" to length, "код" to code, "тон" to tone)
    }
}
