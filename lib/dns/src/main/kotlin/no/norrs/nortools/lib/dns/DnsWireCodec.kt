package no.norrs.nortools.lib.dns

import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type

data class DnsWireRecord(
    val section: String,
    val name: String,
    val type: String,
    val dnsClass: String,
    val ttl: Long,
    val data: String,
)

object DnsWireCodec {
    fun normalizeName(name: String): String =
        if (name.endsWith(".")) name else "$name."

    fun typeCode(type: String): Int {
        val code = Type.value(type.uppercase())
        require(code > 0) { "Unknown DNS record type: $type" }
        return code
    }

    fun buildQueryWire(name: String, type: Int, recursionDesired: Boolean = false): ByteArray {
        val dnsName = Name.fromString(normalizeName(name))
        val record = Record.newRecord(dnsName, type, DClass.IN)
        val message = Message.newQuery(record)
        if (!recursionDesired) {
            message.header.unsetFlag(Flags.RD.toInt())
        }
        return message.toWire()
    }

    fun parseRecords(
        payload: ByteArray,
        sections: List<Pair<Int, String>> = listOf(
            Section.ANSWER to "answer",
            Section.AUTHORITY to "authority",
            Section.ADDITIONAL to "additional",
        ),
    ): List<DnsWireRecord> {
        val message = Message(payload)
        return sections.flatMap { (section, label) ->
            message.getSection(section).map { record ->
                DnsWireRecord(
                    section = label,
                    name = record.name.toString(),
                    type = Type.string(record.type),
                    dnsClass = DClass.string(record.getDClass() and 0x7fff),
                    ttl = record.ttl,
                    data = record.rdataToString(),
                )
            }
        }
    }
}
