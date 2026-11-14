package day8

import day7.Day7Agent
import day7.Day7ConversationStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Day8TokenStatsTest {
    @Test
    fun `counts text and estimates persisted JSON`() {
        assertTrue(Day8TokenCounter.countText("Сколько токенов в этой реплике?") > 0)
        assertTrue(Day8TokenCounter.estimateJson(buildJsonArray { add(buildJsonObject { put("text", "Привет") }) }) > 0)
    }

    @Test
    fun `persists turn metrics in a JSON file`() {
        val root = Files.createTempDirectory("day8-metric-store-test")
        try {
            val id = "4de6172e-faf8-4e8b-a2ca-280ca110d9d3"
            val store = Day8StatsStore(root)
            store.append(id, Day8TurnStats("2026-11-14T10:00:00Z", "success", 5, 12, 30, 20, 28, 4, 9, 8, 1, 0.0001, 8_000))

            val stored = store.load(id).single().jsonObject.toDay8TurnStats()

            assertEquals(28, stored.apiInputTokens)
            assertEquals("success", stored.status)
            assertEquals(0.0001, stored.estimatedCostUsd)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `captures exact API usage and estimated cost for a turn`() = runBlocking {
        val root = Files.createTempDirectory("day8-stats-test")
        try {
            val agent = Day7Agent(
                store = Day7ConversationStore(root),
                request = {
                    buildJsonObject {
                        put("usage", buildJsonObject {
                            put("input_tokens", 37)
                            put("output_tokens", 9)
                            put("input_tokens_details", buildJsonObject { put("cached_tokens", 7) })
                        })
                        put("output", buildJsonArray { add(buildJsonObject {
                            put("type", "message")
                            put("role", "assistant")
                            put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", "Готово") }) })
                        }) })
                    }
                },
            )

            val result = agent.respond("4de6172e-faf8-4e8b-a2ca-280ca110d9d3", "Ответь коротко")
            val stats = result.tokenStats!!
            assertEquals("success", stats.status)
            assertEquals(37, stats.apiInputTokens)
            assertEquals(9, stats.apiOutputTokens)
            assertEquals(7, stats.cachedInputTokens)
            assertEquals(135.5 / 1_000_000.0, stats.estimatedCostUsd, 1e-12)
            assertTrue(stats.currentPromptTokens > 0)
            assertTrue(stats.historyTokensAfter > stats.historyTokensBefore)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects an over-limit request before calling the API`() = runBlocking {
        val root = Files.createTempDirectory("day8-overflow-test")
        try {
            var called = false
            val agent = Day7Agent(store = Day7ConversationStore(root), request = {
                called = true
                error("API не должен быть вызван")
            })

            val error = assertFailsWith<Day8AgentFailure> {
                agent.respond("4de6172e-faf8-4e8b-a2ca-280ca110d9d3", "Слишком длинный для лимита", contextLimitTokens = 20)
            }

            assertEquals("context_overflow", error.stats.status)
            assertTrue(error.stats.estimatedRequestTokens > 20)
            assertEquals(0, error.stats.apiCalls)
            assertEquals(0.0, error.stats.estimatedCostUsd)
            assertTrue(!called)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `records an API context error as an attempted request`() = runBlocking {
        val root = Files.createTempDirectory("day8-api-overflow-test")
        try {
            val agent = Day7Agent(store = Day7ConversationStore(root), request = {
                throw IllegalStateException("Maximum context length exceeded")
            })

            val error = assertFailsWith<Day8AgentFailure> {
                agent.respond("4de6172e-faf8-4e8b-a2ca-280ca110d9d3", "Короткий вопрос", contextLimitTokens = 1_000_000)
            }

            assertEquals("context_overflow", error.stats.status)
            assertEquals(1, error.stats.apiCalls)
            assertEquals(0, error.stats.apiInputTokens)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
