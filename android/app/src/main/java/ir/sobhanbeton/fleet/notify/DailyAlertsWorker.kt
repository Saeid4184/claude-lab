package ir.sobhanbeton.fleet.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.AppDatabase
import ir.sobhanbeton.fleet.data.FuelStats
import ir.sobhanbeton.fleet.data.TankConfigEntity
import ir.sobhanbeton.fleet.data.TankStock
import ir.sobhanbeton.fleet.ui.LoginActivity
import ir.sobhanbeton.fleet.util.AppPreferences
import ir.sobhanbeton.fleet.util.toPersianDigitsInString
import java.util.concurrent.TimeUnit

/**
 * Once a day: reads the on-phone mirror (no network needed) and, if anything needs attention,
 * posts one notification summarising it (services, documents, fuel, tank stock). Silent when everything is fine. Turned on/off in
 * Settings; the Room mirror is emptied at sign-out, so nothing is posted for a signed-out phone.
 */
class DailyAlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!AppPreferences.isDailyAlertsEnabled(applicationContext)) return Result.success()
        val db = AppDatabase.getInstance(applicationContext)
        val now = System.currentTimeMillis()
        val fuelFrom = now - TimeUnit.DAYS.toMillis((FuelStats.RECENT_DAYS + FuelStats.BASELINE_DAYS).toLong())
        val counts = DailyAlerts.count(
            vehicles = db.vehicleDao().getAllOnce(),
            drivers = db.driverDao().getAllOnce(),
            plans = db.servicePlanDao().getAllOnce(),
            serviceLogs = db.serviceLogDao().getAllOnce(),
            fuelLogs = db.fuelLogDao().getInRange(fuelFrom, now),
            now = now,
            tankStock = TankStock.status(db.tankDao().deliveriesOnce(), db.tankDao().withdrawalsOnce(), db.tankDao().readingsOnce(), db.fuelLogDao().tankFuelOnce(), now).stock,
            tankLowLevel = (db.tankDao().configOnce() ?: TankConfigEntity()).lowLevelLiters
        )
        if (!counts.isEmpty) post(applicationContext, DailyAlerts.lines(counts).map { it.toPersianDigitsInString() })
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily_alerts"
        private const val CHANNEL_ID = "daily_alerts"
        private const val NOTIFICATION_ID = 1001

        /** Idempotent: keeps an already-scheduled daily job. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyAlertsWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun post(context: Context, lines: List<String>) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_settings_gear)
                .setContentTitle(context.getString(R.string.notify_title))
                .setContentText(lines.joinToString(" · "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
