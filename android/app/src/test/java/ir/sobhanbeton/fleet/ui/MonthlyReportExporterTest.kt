package ir.sobhanbeton.fleet.ui

import ir.sobhanbeton.fleet.data.FleetReport
import ir.sobhanbeton.fleet.ui.reports.MonthlyReportExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class MonthlyReportExporterTest {

    @Test
    fun `workbook has a summary first, every topic sheet, and no money columns`() {
        val report = FleetReport.build(
            0L, 1_000L, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), now = 2_000L
        )
        val sheets = MonthlyReportExporter.sheets(report)
        assertEquals("خلاصه", sheets.first().name)
        assertEquals(9, sheets.size)
        val allText = sheets.flatMap { it.headers + it.rows.flatten() }
        assertTrue(allText.none { it.contains("ریال") || it.contains("مبلغ") || it.contains("هزینه") })
    }

    @Test
    fun `numbers are plain latin digits for excel`() {
        assertEquals("87.5", MonthlyReportExporter.percent(0.875))
        assertEquals("64", MonthlyReportExporter.decimal(64.0))
        assertEquals("12", MonthlyReportExporter.hours(12 * 3_600_000L + 59_000L))
    }
}
