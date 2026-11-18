import local.phoneauditor.CoreChecks
import local.phoneauditor.core.JsonCodec

fun main(args: Array<String>) {
    val results = CoreChecks.runAll()
    results.forEach { println("PASS $it") }
    println("TOTAL: ${results.size} passed")
    if (args.isNotEmpty()) {
        java.io.File(args[0]).writeText(JsonCodec.encode(linkedMapOf(
            "label" to "Проверка \"кавычек\" \\ путь\n😀",
            "unknown" to null, "not_a_number" to Double.NaN,
            "counts" to listOf(0, 1, 9223372036854775807L),
            "nested" to mapOf("flag" to true)
        )))
    }
}
