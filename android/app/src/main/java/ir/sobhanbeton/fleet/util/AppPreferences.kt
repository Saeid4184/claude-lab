package ir.sobhanbeton.fleet.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Lightweight wrapper around SharedPreferences for the app's display/interaction settings.
 * Read directly (no LiveData) since these are checked at the moment of an action/bind, not
 * observed continuously.
 */
object AppPreferences {

    private const val PREFS_NAME = "app_settings"

    private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_AI_API_KEY = "ai_api_key"
    private const val KEY_DAILY_ALERTS = "daily_alerts"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Haptic feedback on successful saves ---
    fun isHapticEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_HAPTIC_ENABLED, true)
    fun setHapticEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    // --- Theme: SYSTEM (default), LIGHT, DARK ---
    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    fun getThemeMode(context: Context): ThemeMode {
        val raw = prefs(context).getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(raw ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
        applyThemeMode(mode)
    }

    fun applyThemeMode(mode: ThemeMode) {
        val nightMode = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    // --- AI analysis API key (user-supplied, never bundled with the app) ---
    fun getAiApiKey(context: Context): String = prefs(context).getString(KEY_AI_API_KEY, "") ?: ""
    fun setAiApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_AI_API_KEY, key.trim()).apply()
    }

    // --- Daily reminder notification (see DailyAlertsWorker) ---
    fun isDailyAlertsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_DAILY_ALERTS, true)
    fun setDailyAlertsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DAILY_ALERTS, enabled).apply()
    }
}
