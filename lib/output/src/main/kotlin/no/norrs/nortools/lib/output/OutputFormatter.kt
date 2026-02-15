package no.norrs.nortools.lib.output

import com.google.gson.GsonBuilder

/**
 * Unified output formatter supporting table and JSON output modes.
 */
class OutputFormatter(
    private val json: Boolean = false,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Format a list of rows as either a table or JSON.
     * Each row is a map of column name to value.
     */
    fun format(rows: List<Map<String, Any?>>): String {
        return if (json) {
            formatJson(rows)
        } else {
            formatTable(rows)
        }
    }

    /**
     * Format a single key-value result as either a detail view or JSON.
     */
    fun formatDetail(data: Map<String, Any?>): String {
        return if (json) {
            gson.toJson(data)
        } else {
            formatDetailTable(data)
        }
    }

    /**
     * Format rows as a JSON array.
     */
    private fun formatJson(rows: List<Map<String, Any?>>): String {
        return gson.toJson(rows)
    }

    /**
     * Format rows as an aligned ASCII table.
     */
    private fun formatTable(rows: List<Map<String, Any?>>): String {
        if (rows.isEmpty()) return "(no results)"

        val columns = rows.first().keys.toList()
        val widths = columns.map { col ->
            maxOf(
                col.length,
                rows.maxOf { row -> (row[col]?.toString() ?: "").length },
            )
        }

        val sb = StringBuilder()

        // Header
        sb.appendLine(
            columns.zip(widths).joinToString("  ") { (col, width) ->
                col.uppercase().padEnd(width)
            },
        )

        // Separator
        sb.appendLine(widths.joinToString("  ") { "-".repeat(it) })

        // Data rows
        for (row in rows) {
            sb.appendLine(
                columns.zip(widths).joinToString("  ") { (col, width) ->
                    (row[col]?.toString() ?: "").padEnd(width)
                },
            )
        }

        return sb.toString().trimEnd()
    }

    /**
     * Format a key-value map as a detail table.
     */
    private fun formatDetailTable(data: Map<String, Any?>): String {
        if (data.isEmpty()) return "(no data)"

        val keyWidth = data.keys.maxOf { it.length }
        return data.entries.joinToString("\n") { (key, value) ->
            "${key.padEnd(keyWidth)}  ${value ?: ""}"
        }
    }
}
