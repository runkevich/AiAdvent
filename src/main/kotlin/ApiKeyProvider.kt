internal object ApiKeyProvider {
    fun get(): String = System.getenv("OPENAI_API_KEY")
        ?.takeIf(String::isNotBlank)
        ?: readFromKeychain()

    private fun readFromKeychain(): String {
        val process = try {
            ProcessBuilder(
                "security",
                "find-generic-password",
                "-a",
                System.getProperty("user.name"),
                "-s",
                "AiAdvent-OpenAI",
                "-w",
            ).redirectErrorStream(true).start()
        } catch (exception: Exception) {
            throw IllegalStateException(
                "OPENAI_API_KEY is not set and macOS Keychain could not be accessed. " +
                    "Set OPENAI_API_KEY or run this app on macOS with the AiAdvent-OpenAI Keychain item.",
                exception,
            )
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode != 0 || output.isBlank()) {
            throw IllegalStateException(
                "OPENAI_API_KEY is not set and no usable AiAdvent-OpenAI item was found in macOS Keychain. " +
                    "Save it with: security add-generic-password -U -a \"\$USER\" -s AiAdvent-OpenAI -w",
            )
        }
        return output
    }
}
