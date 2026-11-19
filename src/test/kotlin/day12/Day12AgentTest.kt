package day12

import day11.Day11MemoryStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Day12AgentTest {
    @Test fun `profile is injected into every request and learns preferences from dialogue`() = runBlocking {
        val root = Files.createTempDirectory("day12-profile-test")
        try {
            val seen = mutableListOf<JsonObject>()
            val profiles = Day12ProfileStore(root.resolve("profiles"))
            val agent = Day12Agent(
                profiles = profiles,
                memories = Day11MemoryStore(root.resolve("memory")),
                conversations = day7.Day7ConversationStore(root.resolve("conversations")),
                request = { body -> seen += body; response(longFormalAnswer()) },
            )
            val profile = agent.createProfile("Тест", Day12Preset.CONCISE_CODE_INFORMAL)
            val result = agent.chat(profile.id, null, "Отвечай подробно, без кода, на вы. Как подготовиться?")

            assertEquals(listOf("length", "code", "tone"), result.updatedFields)
            assertEquals(ResponseLength.DETAILED, result.profile.length)
            assertEquals(CodePreference.WITHOUT_CODE, result.profile.code)
            assertEquals(TonePreference.FORMAL, result.profile.tone)
            assertEquals("из диалога", profiles.load(profile.id).sources["tone"]!!.jsonPrimitive.content)
            val input = seen.single()["input"]!!.jsonArray.toString()
            assertTrue(input.contains("Профиль пользователя «Тест»"))
            assertTrue(input.contains("отвечай подробно"))
            assertTrue(input.contains("без кода"))
            assertTrue(input.contains("на «вы»"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `compliance checks evaluate opposite preferences`() {
        val concise = Day12Profile("id", "Ира", ResponseLength.CONCISE, CodePreference.WITH_CODE, TonePreference.INFORMAL, JsonObject(emptyMap()))
        val detailed = Day12Profile("id", "Анна", ResponseLength.DETAILED, CodePreference.WITHOUT_CODE, TonePreference.FORMAL, JsonObject(emptyMap()))
        assertTrue(ProfileCompliance.check(concise, "Ты можешь начать так:\n```kotlin\nprintln(\"ok\")\n```").values.all { it })
        assertTrue(ProfileCompliance.check(detailed, longFormalAnswer()).values.all { it })
    }

    private fun longFormalAnswer() = "Вы можете начать с плана и распределения задач. ".repeat(35)
    private fun response(text: String) = buildJsonObject {
        put("usage", buildJsonObject { put("input_tokens", 30); put("output_tokens", 20) })
        put("output", buildJsonArray { add(buildJsonObject { put("type", "message"); put("role", "assistant"); put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) }) }) })
    }
}
