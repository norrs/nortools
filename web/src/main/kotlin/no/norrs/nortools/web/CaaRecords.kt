package no.norrs.nortools.web

internal data class ParsedCaaRecord(
    val flags: Int,
    val tag: String,
    val value: String,
    val raw: String,
)

internal fun parseCaaRecord(raw: String): ParsedCaaRecord? {
    val trimmed = raw.trim()
    val match = Regex("""^(\d+)\s+([A-Za-z0-9_-]+)\s+(.+)$""").find(trimmed) ?: return null
    val flags = match.groupValues[1].toIntOrNull() ?: return null
    val tag = match.groupValues[2].lowercase()
    val value = match.groupValues[3].trim().trim('"')
    if (tag.isBlank()) return null
    return ParsedCaaRecord(flags = flags, tag = tag, value = value, raw = raw)
}

