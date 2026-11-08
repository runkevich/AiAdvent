package day4

internal data class Day4ValidationResult(
    val status: String,
    val details: String,
    val itemCount: Int? = null,
    val duplicateNames: List<String> = emptyList(),
)

internal object Day4AnswerValidator {
    private val expectedAmount = Regex("(?<!\\d)200(?:[,.]0+)?(?!\\d)")
    private val numberedLine = Regex("^\\s*\\d+[.)]\\s+(.+?)\\s*$")

    fun validateFactual(response: String?): Day4ValidationResult {
        if (response.isNullOrBlank()) {
            return Day4ValidationResult("NEEDS_REVIEW", "Ответ отсутствует.")
        }
        val correct = expectedAmount.containsMatchIn(response)
        return Day4ValidationResult(
            status = if (correct) "CORRECT" else "INCORRECT",
            details = if (correct) {
                "В ответе указана сумма 200 (эквивалент 200 PLN)."
            } else {
                "В ответе не найдена ожидаемая сумма 200 PLN."
            },
        )
    }

    fun validateCreative(response: String?): Day4ValidationResult {
        if (response.isNullOrBlank()) {
            return Day4ValidationResult("NEEDS_REVIEW", "Ответ отсутствует.", itemCount = 0)
        }
        val names = response.lineSequence()
            .mapNotNull { numberedLine.matchEntire(it)?.groupValues?.get(1)?.trim() }
            .filter(String::isNotEmpty)
            .toList()
        val normalizedNames = names.map(::normalizeName)
        val duplicates = normalizedNames.withIndex()
            .filter { (index, name) -> normalizedNames.indexOf(name) != index }
            .map { names[it.index] }
            .distinct()
        val correctCount = names.size == 10
        val noDuplicates = duplicates.isEmpty()
        val status = if (correctCount && noDuplicates) "PASS" else "FAIL"
        val details = buildList {
            add(if (correctCount) "Получено ровно 10 названий." else "Получено названий: ${names.size}; ожидалось 10.")
            if (duplicates.isNotEmpty()) add("Повторы внутри ответа: ${duplicates.joinToString(", ")}.")
            if (duplicates.isEmpty()) add("Повторов внутри ответа не найдено.")
        }.joinToString(" ")
        return Day4ValidationResult(status, details, itemCount = names.size, duplicateNames = duplicates)
    }

    private fun normalizeName(value: String): String = value
        .lowercase()
        .filter(Char::isLetterOrDigit)
}
