package ir.sobhanbeton.fleet.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class XlsxWriterTest {

    @Test
    fun `plain numbers become numeric cells, leading zeros and text stay text`() {
        val file = File.createTempFile("xlsx-test", ".xlsx")
        XlsxWriter.write(file, "s", listOf("a", "b", "c", "d"), listOf(listOf("120.5", "09121234567", "۱۲۰", "12345")))
        val sheet = ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).reader().readText() }
        file.delete()

        assertTrue(sheet.contains("<c r=\"A2\"><v>120.5</v></c>"))
        assertTrue(sheet.contains("<c r=\"D2\"><v>12345</v></c>"))
        assertTrue(sheet.contains("09121234567</t>"))
        assertTrue(sheet.contains("۱۲۰</t>"))
        assertFalse(sheet.contains("<v>a</v>")) // headers stay text
    }
}
