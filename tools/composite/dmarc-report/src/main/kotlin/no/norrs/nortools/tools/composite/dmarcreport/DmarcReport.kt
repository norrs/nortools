package no.norrs.nortools.tools.composite.dmarcreport

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DMARC Report Analyzer tool — parses and analyzes DMARC aggregate XML reports.
 *
 * Reads DMARC aggregate reports (RFC 7489 Section 7.2) in XML format
 * and presents a summary of authentication results, source IPs, and policy actions.
 */
class DmarcReportCommand : BaseCommand(
    name = "dmarc-report",
    helpText = "Parse and analyze DMARC aggregate XML reports (RFC 7489)",
) {
    private val file by argument(help = "Path to DMARC aggregate report XML file")

    override fun run() {
        val formatter = createFormatter()
        val xmlFile = File(file)

        if (!xmlFile.exists()) {
            echo("File not found: $file")
            return
        }

        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xmlFile)
            doc.documentElement.normalize()

            // Report metadata
            val reportMeta = doc.getElementsByTagName("report_metadata")
            val details = linkedMapOf<String, Any?>()
            if (reportMeta.length > 0) {
                val meta = reportMeta.item(0)
                val children = meta.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                        when (node.nodeName) {
                            "org_name" -> details["Organization"] = node.textContent
                            "email" -> details["Email"] = node.textContent
                            "report_id" -> details["Report ID"] = node.textContent
                        }
                    }
                }
            }

            // Date range
            val dateRange = doc.getElementsByTagName("date_range")
            if (dateRange.length > 0) {
                val range = dateRange.item(0)
                val children = range.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                        when (node.nodeName) {
                            "begin" -> details["Period Start"] = java.time.Instant.ofEpochSecond(node.textContent.toLong()).toString()
                            "end" -> details["Period End"] = java.time.Instant.ofEpochSecond(node.textContent.toLong()).toString()
                        }
                    }
                }
            }

            echo("=== DMARC Report Summary ===")
            echo(formatter.formatDetail(details))
            echo()

            // Parse records
            val records = doc.getElementsByTagName("record")
            val rows = mutableListOf<Map<String, String>>()
            var totalMessages = 0
            var passCount = 0
            var failCount = 0

            for (i in 0 until records.length) {
                val record = records.item(i)
                val children = record.childNodes
                var sourceIp = ""
                var count = 0
                var disposition = ""
                var dkim = ""
                var spf = ""

                for (j in 0 until children.length) {
                    val child = children.item(j)
                    if (child.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                    when (child.nodeName) {
                        "row" -> {
                            val rowChildren = child.childNodes
                            for (k in 0 until rowChildren.length) {
                                val rc = rowChildren.item(k)
                                if (rc.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                                when (rc.nodeName) {
                                    "source_ip" -> sourceIp = rc.textContent
                                    "count" -> count = rc.textContent.toIntOrNull() ?: 0
                                    "policy_evaluated" -> {
                                        val pe = rc.childNodes
                                        for (l in 0 until pe.length) {
                                            val p = pe.item(l)
                                            if (p.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                                            when (p.nodeName) {
                                                "disposition" -> disposition = p.textContent
                                                "dkim" -> dkim = p.textContent
                                                "spf" -> spf = p.textContent
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                totalMessages += count
                if (dkim == "pass" && spf == "pass") passCount += count else failCount += count

                rows.add(mapOf(
                    "Source IP" to sourceIp,
                    "Count" to "$count",
                    "Disposition" to disposition,
                    "DKIM" to dkim,
                    "SPF" to spf,
                ))
            }

            echo("=== Records (${rows.size} sources, $totalMessages messages) ===")
            echo(formatter.format(rows))
            echo()
            echo("Total: $totalMessages messages — $passCount passed, $failCount failed")
        } catch (e: Exception) {
            echo("Error parsing DMARC report: ${e.message}")
        }
    }
}

fun main(args: Array<String>) = DmarcReportCommand().main(args)
