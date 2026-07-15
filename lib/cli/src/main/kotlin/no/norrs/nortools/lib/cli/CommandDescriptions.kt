package no.norrs.nortools.lib.cli

import com.google.gson.JsonParser

object CommandDescriptions {
    private val descriptions: Map<String, String> by lazy {
        loadDescriptions()
    }

    fun descriptionFor(command: String): String? = descriptions[command]

    private fun loadDescriptions(): Map<String, String> {
        val candidates = listOf(
            "docs-site/data/command-descriptions.json",
            "data/command-descriptions.json",
            "command-descriptions.json",
        )
        val stream = candidates.firstNotNullOfOrNull { path ->
            Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
                ?: ClassLoader.getSystemResourceAsStream(path)
        } ?: return emptyMap()

        return stream.bufferedReader().use { reader ->
            JsonParser.parseReader(reader).asJsonObject.entrySet().associate { (name, description) ->
                name to description.asString
            }
        }
    }
}
