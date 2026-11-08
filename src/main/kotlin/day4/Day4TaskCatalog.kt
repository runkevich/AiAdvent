package day4

internal data class Day4Task(
    val id: String,
    val category: String,
    val prompt: String,
)

internal object Day4TaskCatalog {
    val factual = Day4Task(
        id = "factual",
        category = "Shopping",
        prompt = "Товар стоит 250 PLN. На него действует скидка 20%.\n\nСколько нужно заплатить после скидки? Объясни расчёт одним предложением.",
    )

    val creative = Day4Task(
        id = "creative",
        category = "Creativity",
        prompt = "Придумай 10 оригинальных названий для небольшой specialty coffee shop.\n\n" +
            "Требования:\n" +
            "- короткие и запоминающиеся;\n" +
            "- современные;\n" +
            "- с уютной атмосферой;\n" +
            "- без банальных названий;\n" +
            "- каждое название должно отличаться от остальных.\n\n" +
            "Верни ровно 10 названий в нумерованном списке.",
    )

    val all: List<Day4Task> = listOf(factual, creative)

    fun find(id: String): Day4Task? = all.firstOrNull { it.id == id }
}
