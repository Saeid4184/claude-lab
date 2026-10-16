package ir.sobhanbeton.fleet

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import ir.sobhanbeton.fleet.util.AppPreferences

class FactoryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPreferences.applyThemeMode(AppPreferences.getThemeMode(this))
        firebaseConfigured = FirebaseApp.getApps(this).isNotEmpty()
        if (firebaseConfigured) configureOfflineCache()
    }

    /**
     * Turns on Firestore's on-device persistent cache with NO size limit (the default is a
     * ~100MB LRU cache that starts evicting old data once full). Fuel, repair and service
     * records entered while offline (in the workshop, out on a site) must survive on the phone
     * until the connection is back, at which point Firestore uploads them on its own. This must
     * run before anything else touches Firestore.
     */
    private fun configureOfflineCache() {
        val firestore = FirebaseFirestore.getInstance()
        firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(
                PersistentCacheSettings.newBuilder()
                    .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build()
            )
            .build()
    }

    companion object {
        /** False when the APK was built without app/google-services.json (see
         *  FIREBASE_SETUP.md) — LoginActivity then explains what's missing instead of the app
         *  crashing on the first Firebase call. */
        @Volatile
        var firebaseConfigured: Boolean = false
            private set
    }
}
