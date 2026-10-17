package ir.sobhanbeton.fleet.util

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import ir.sobhanbeton.fleet.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes an .xlsx with [XlsxWriter], drops a copy in Downloads/SobhanFleetReports (easy to find
 * without sharing), and opens the share sheet — the same flow the security app's reports used.
 * Exports only read data, so the manager can use it too.
 */
object ExcelExport {

    private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val FOLDER = "SobhanFleetReports"

    fun exportAndShare(activity: Activity, filePrefix: String, sheets: List<XlsxWriter.Sheet>) {
        val outDir = File(activity.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(outDir, "${filePrefix}_$stamp.xlsx")
        XlsxWriter.write(file, sheets)
        saveToDownloads(activity, file)

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = XLSX_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.export_excel)))
    }

    private fun saveToDownloads(activity: Activity, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, XLSX_MIME)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                }
                val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                activity.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val folder = File(downloads, FOLDER).apply { mkdirs() }
                file.copyTo(File(folder, file.name), overwrite = true)
            }
        } catch (_: Exception) {
            // Sharing the cache copy still works even if the Downloads copy fails.
        }
    }
}
