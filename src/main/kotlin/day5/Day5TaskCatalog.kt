package day5

import day3.Day3Task
import day3.Day3TaskCatalog

/** Day 5 comparison tasks: canonical Day 3 tasks plus Day 5-only creative work. */
internal object Day5TaskCatalog {
    private val day3TaskIds = listOf(
        "shopping",
        "mathematics",
        "number_sequence",
        "productivity",
        "logic",
    )

    val comparisonTasks: List<Day5Task> = day3TaskIds.map { taskId ->
        val source = requireNotNull(Day3TaskCatalog.find(taskId)) {
            "Day 5 task '$taskId' is missing from Day3TaskCatalog."
        }
        Day5Task(source.id, source.category, source.text, validationSource = source)
    }

    val all: List<Day5Task> = comparisonTasks + Day5Task(
        id = "coffee_shop_name",
        category = "Creativity · Coffee shop name",
        text = "Придумай одно короткое, запоминающееся название для небольшой уютной specialty coffee shop в современном стиле.",
    )

    fun find(id: String): Day5Task? = all.firstOrNull { it.id == id }
}

/** A Day 5 view over a Day 3 task, or a Day 5-only task without an automatic answer key. */
internal data class Day5Task(
    val id: String,
    val category: String,
    val text: String,
    val validationSource: Day3Task? = null,
)
