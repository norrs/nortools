package no.norrs.nortools.lib.cli

import com.google.gson.JsonParser

data class CommandMetadata(
    val description: String,
    val category: String,
    val uiPath: String? = null,
    val icon: String? = null,
)

object CommandDescriptions {
    private val metadata: Map<String, CommandMetadata> by lazy {
        loadMetadata()
    }

    fun descriptionFor(command: String): String? = metadata[command]?.description

    fun categoryFor(command: String): String? = metadata[command]?.category

    fun metadataFor(command: String): CommandMetadata? = metadata[command]

    private fun loadMetadata(): Map<String, CommandMetadata> {
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
                if (description.isJsonObject) {
                    val obj = description.asJsonObject
                    name to CommandMetadata(
                        description = obj["description"]?.asString ?: "",
                        category = obj["category"]?.asString ?: "Uncategorized",
                        uiPath = obj["uiPath"]?.asString,
                        icon = obj["icon"]?.asString,
                    )
                } else {
                    name to CommandMetadata(
                        description = description.asString,
                        category = "Uncategorized",
                    )
                }
            }
        }
    }
}
