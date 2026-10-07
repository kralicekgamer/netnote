package cz.kralicekgamer.netnote.logic

/** RFC 4180: NULL je prázdné pole, uvozovky se zdvojují. */
fun csvField(v: String?): String {
    if (v == null) return ""
    val needsQuotes = v.isEmpty() || v.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
        v.first() == ' ' || v.last() == ' '
    return if (needsQuotes) "\"" + v.replace("\"", "\"\"") + "\"" else v
}

fun csvLine(values: List<String?>): String = values.joinToString(",") { csvField(it) }
