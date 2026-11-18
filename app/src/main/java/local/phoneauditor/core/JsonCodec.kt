package local.phoneauditor.core

/** Small deterministic writer. No reflection, no untrusted HTML, no invalid NaN/Infinity JSON. */
object JsonCodec {
    fun encode(value: Any?, pretty: Boolean = true): String = buildString { write(value, 0, pretty) }
    private fun StringBuilder.write(value: Any?, depth: Int, pretty: Boolean) {
        fun newline(d: Int) { if (pretty) { append('\n'); repeat(d) { append("  ") } } }
        when (value) {
            null -> append("null")
            is String -> quote(value)
            is Boolean -> append(value)
            is Float -> if (value.isFinite()) append(value) else append("null")
            is Double -> if (value.isFinite()) append(value) else append("null")
            is Number -> append(value)
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    newline(depth + 1); quote(entry.key.toString()); append(if (pretty) ": " else ":")
                    write(entry.value, depth + 1, pretty)
                }
                if (value.isNotEmpty()) newline(depth)
                append('}')
            }
            is Iterable<*> -> {
                append('['); var count = 0
                for (entry in value) {
                    if (count++ > 0) append(',')
                    newline(depth + 1); write(entry, depth + 1, pretty)
                }
                if (count > 0) newline(depth)
                append(']')
            }
            else -> error("Unsupported JSON type: ${value.javaClass.name}")
        }
    }
    private fun StringBuilder.quote(text: String) {
        append('"')
        text.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                else -> if (ch < ' ' || ch.isSurrogate()) {
                    append("\\u"); append(ch.code.toString(16).padStart(4, '0'))
                } else append(ch)
            }
        }
        append('"')
    }
}

data class ExportOptions(
    val includeApps: Boolean = true,
    val includeUsage: Boolean = false,
    val includeNetwork: Boolean = false
)

/** Removes raw fields AND findings derived from those fields. Works on historical reports too. */
object ReportRedactor {
    @Suppress("UNCHECKED_CAST")
    fun redact(source: Map<String, Any?>, options: ExportOptions): Map<String, Any?> {
        val result = LinkedHashMap(source)
        fun filterFindings(value: Any?): List<Any?> = (value as? List<*>)?.filter { item ->
            val m = item as? Map<*, *> ?: return@filter false
            (options.includeUsage || m["category"] != "usage") &&
                (options.includeNetwork || m["category"] != "network") &&
                (options.includeApps || m["package_name"] == null)
        } ?: emptyList()
        if (!options.includeApps) {
            result["apps"] = emptyList<Any>()
            result["apps_status"] = "REDACTED_BY_USER"
        } else {
            result["apps"] = (source["apps"] as? List<*>)?.mapNotNull { item ->
                val original = item as? Map<String, Any?> ?: return@mapNotNull null
                LinkedHashMap(original).apply {
                    if (!options.includeUsage) { put("usage", null); put("usage_status", "REDACTED_BY_USER") }
                    if (!options.includeNetwork) { put("network_usage", null); put("network_status", "REDACTED_BY_USER") }
                    put("findings", filterFindings(get("findings")))
                }
            } ?: emptyList<Any>()
        }
        result["findings"] = filterFindings(source["findings"])
        if (!options.includeNetwork) {
            result["network"] = linkedMapOf("status" to "REDACTED_BY_USER")
        }
        // Collector errors can contain package names supplied by Android exceptions.
        result["collection_errors"] = if (options.includeApps) source["collection_errors"] else
            listOf("Подробности ошибок исключены вместе с идентификаторами приложений.")
        result["privacy"] = linkedMapOf(
            "app_inventory_included" to options.includeApps,
            "usage_included" to (options.includeApps && options.includeUsage),
            "network_included" to options.includeNetwork,
            "serial_imei_android_id_accounts_contacts_messages_location_collected" to false,
            "automatic_upload" to false,
            "warning" to "Список программ и их метаданные могут раскрывать личные сведения. " +
                "После ручной передачи копия находится у выбранного получателя."
        )
        return result
    }
}

object DisplayFormat {
    fun bytes(value: Long?): String {
        if (value == null || value < 0) return "Недоступно"
        val units = arrayOf("Б", "КиБ", "МиБ", "ГиБ", "ТиБ")
        var n = value.toDouble(); var unit = 0
        while (n >= 1024 && unit < units.lastIndex) { n /= 1024; unit++ }
        return if (unit == 0) "$value ${units[unit]}" else "%.1f %s".format(java.util.Locale("ru"), n, units[unit])
    }
    fun duration(ms: Long?): String {
        if (ms == null) return "Недоступно"
        val minutes = ms.coerceAtLeast(0) / 60_000
        return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
    }
    fun yesNo(value: Boolean?) = when (value) { true -> "Да"; false -> "Нет"; null -> "Не удалось проверить" }
}
