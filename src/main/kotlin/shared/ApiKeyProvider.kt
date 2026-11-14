package shared

internal object ApiKeyProvider {
    fun get(): String = System.getenv("OPENAI_API_KEY")
        ?.takeIf(String::isNotBlank)
        ?: readFromEnvFile()
        ?: readFromKeychain()

    private fun readFromEnvFile(): String? {
        val envFile = java.nio.file.Path.of(System.getProperty("user.dir"), ".env")
        if (!java.nio.file.Files.isRegularFile(envFile)) return null

        return java.nio.file.Files.readAllLines(envFile)
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator < 0 || line.substring(0, separator).trim() != "OPENAI_API_KEY") return@mapNotNull null
                line.substring(separator + 1).trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                    .takeIf(String::isNotBlank)
            }
            .firstOrNull()
    }

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
