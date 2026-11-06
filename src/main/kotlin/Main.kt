import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("Welcome to AI Terminal Assistant!\n")
    println("Enter your question:\n")
    print("> ")
    val prompt = readlnOrNull()?.trim()

    if (prompt.isNullOrEmpty()) {
        println("Please enter a question.")
        return@runBlocking
    }

    println("\nAI is thinking...\n")

    try {
        val response = OpenAIClient().generateResponse(prompt)
        println("AI:\n$response")
    } catch (exception: Exception) {
        System.err.println("Error: ${exception.message ?: "The request failed."}")
    }
}
