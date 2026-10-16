package ir.sobhanbeton.fleet.data

import ir.sobhanbeton.fleet.util.extractDigits

/**
 * Parses the one-time "ورود گروهی" text: one vehicle per line, fields separated by `|`, `،`,
 * `,` or a tab —
 *
 *     پلاک | گروه/مدل | نام راننده
 *
 * Group and driver are optional ("12345 | میکسر - آمیکو" or just "12345"). A line with no
 * plate but a driver name after a leading separator ("| | حسین یاری") registers a driver with
 * no vehicle — e.g. loader operators or spare drivers. Lines starting with `#` are comments.
 *
 * This is how the existing roster gets into the app without the fleet's plates and driver names
 * ever being written into the (public) source code: the data is pasted once on the phone and
 * lives only in the private Firebase project.
 */
object BulkImport {

    data class Row(val lineNumber: Int, val plate: String?, val group: String?, val driverName: String?)

    data class ParseResult(val rows: List<Row>, val errors: List<String>)

    data class Summary(
        val vehiclesCreated: Int,
        val vehiclesExisting: Int,
        val driversCreated: Int,
        val driversExisting: Int,
        val assignmentsCreated: Int
    )

    private val SEPARATORS = Regex("[|،,\t]")

    fun parse(text: String): ParseResult {
        val rows = mutableListOf<Row>()
        val errors = mutableListOf<String>()
        text.lines().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val parts = line.split(SEPARATORS).map { it.trim() }
            val plate = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }
            val group = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
            val driver = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }

            when {
                parts.size > 3 -> errors += "خط $lineNumber: بیش از سه بخش دارد"
                plate == null && driver == null -> errors += "خط $lineNumber: نه پلاک دارد نه نام راننده"
                plate != null && plateKey(plate).isEmpty() -> errors += "خط $lineNumber: پلاک «$plate» عدد ندارد"
                else -> rows += Row(lineNumber, plate, group, driver)
            }
        }
        return ParseResult(rows, errors)
    }

    /** Identity of a plate for de-duplication: its digits only, in Latin script, so "۱۲۳۴۵",
     *  "12345" and "پلاک ۱۲۳۴۵" all match the same vehicle. */
    fun plateKey(plate: String): String = plate.extractDigits()

    /** Identity of a driver name for de-duplication: Arabic ي/ك folded to Persian ی/ک, ZWNJ
     *  and repeated spaces collapsed — so the same name typed on two keyboards still matches. */
    fun nameKey(name: String): String = name
        .replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک')
        .replace('‌', ' ')
        .trim()
        .replace(Regex("\\s+"), " ")
}
