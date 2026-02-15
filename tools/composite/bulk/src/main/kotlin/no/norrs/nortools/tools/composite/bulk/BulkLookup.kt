package no.norrs.nortools.tools.composite.bulk

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type
import java.io.File

/**
 * Bulk Lookup tool — performs DNS lookups for a list of domains/IPs from a file.
 *
 * Reads one domain/IP per line from the input file and performs the specified
 * DNS lookup type for each entry. Results are displayed in a table.
 */
class BulkLookupCommand : BaseCommand(
    name = "bulk",
    helpText = "Bulk DNS lookups from a file of domains/IPs (one per line)",
) {
    private val file by argument(help = "File containing domains/IPs (one per line)")
    private val type by option("--type", "-T", help = "Record type (A, AAAA, MX, TXT, CNAME, NS, SOA, PTR)")
        .default("A")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val inputFile = File(file)
        if (!inputFile.exists()) {
            echo("File not found: $file")
            return
        }

        val entries = inputFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        val recordType = Type.value(type.uppercase())
        if (recordType == -1) {
            echo("Unknown record type: $type")
            return
        }

        echo("Bulk ${type.uppercase()} lookup for ${entries.size} entries")
        echo()

        val rows = entries.map { entry ->
            try {
                val result = if (type.uppercase() == "PTR") {
                    resolver.reverseLookup(entry)
                } else {
                    resolver.lookup(entry, recordType)
                }

                mapOf(
                    "Query" to entry,
                    "Status" to if (result.isSuccessful) "OK" else result.status,
                    "Records" to if (result.records.isNotEmpty())
                        result.records.joinToString("; ") { it.data }
                    else "No records",
                    "TTL" to if (result.records.isNotEmpty()) "${result.records.first().ttl}s" else "-",
                )
            } catch (e: Exception) {
                mapOf(
                    "Query" to entry,
                    "Status" to "ERROR",
                    "Records" to (e.message ?: "Unknown error"),
                    "TTL" to "-",
                )
            }
        }

        echo(formatter.format(rows))

        val okCount = rows.count { it["Status"] == "OK" }
        echo()
        echo("Results: $okCount/${entries.size} successful")
    }
}

fun main(args: Array<String>) = BulkLookupCommand().main(args)
