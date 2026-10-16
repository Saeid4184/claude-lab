package ir.sobhanbeton.fleet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkImportTest {

    @Test
    fun `parses all three fields with any separator`() {
        val result = BulkImport.parse(
            """
            12345 | کمپرسی تریلی - فاو | رانندهٔ الف
            ۲۳۴۵۶، میکسر - ایویکو، رانندهٔ ب
            34567	میکسر - ایویکو	رانندهٔ ج
            """.trimIndent()
        )
        assertTrue(result.errors.isEmpty())
        assertEquals(3, result.rows.size)
        assertEquals("12345", result.rows[0].plate)
        assertEquals("کمپرسی تریلی - فاو", result.rows[0].group)
        assertEquals("رانندهٔ الف", result.rows[0].driverName)
        assertEquals("۲۳۴۵۶", result.rows[1].plate)
        assertEquals("رانندهٔ ج", result.rows[2].driverName)
    }

    @Test
    fun `group and driver are optional, comments and blanks skipped`() {
        val result = BulkImport.parse("# ناوگان\n\n45678 | کمپرسی - آمیکو\n56789\n")
        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.rows.size)
        assertNull(result.rows[0].driverName)
        assertNull(result.rows[1].group)
        assertEquals(3, result.rows[0].lineNumber)
    }

    @Test
    fun `driver without vehicle`() {
        val result = BulkImport.parse("| | رانندهٔ لودر")
        assertTrue(result.errors.isEmpty())
        assertNull(result.rows.single().plate)
        assertEquals("رانندهٔ لودر", result.rows.single().driverName)
    }

    @Test
    fun `invalid lines are reported with their line number`() {
        val result = BulkImport.parse("12345 | a | b | c\n| |\nپلاک | میکسر")
        assertTrue(result.rows.isEmpty())
        assertEquals(3, result.errors.size)
        assertTrue(result.errors[0].startsWith("خط 1"))
        assertTrue(result.errors[2].startsWith("خط 3"))
    }

    @Test
    fun `plate key ignores digit script and surrounding text`() {
        assertEquals("12345", BulkImport.plateKey("۱۲۳۴۵"))
        assertEquals("12345", BulkImport.plateKey("پلاک 12345"))
    }

    @Test
    fun `name key folds arabic letters and spacing`() {
        assertEquals(BulkImport.nameKey("علی  کریمی"), BulkImport.nameKey("علي كريمي "))
        assertEquals(BulkImport.nameKey("محمدی‌نیا"), BulkImport.nameKey("محمدی نیا"))
    }
}
