package app.parda

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.parda.data.PardaStore
import kotlin.concurrent.thread

class PardaApp : Application() {
    lateinit var store: PardaStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = PardaStore(this)
        app.parda.data.BuildConfigCompat.debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        thread(name = "parda-model") { store.loadModel() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_INTERCEPTS, getString(R.string.channel_intercepts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** When Android runs short of memory, the resting model is the first thing Parda gives back. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            thread(name = "parda-model-rest") { store.restModel() }
        }
    }

    companion object {
        const val CHANNEL_INTERCEPTS = "intercepts"

        /** For strings in the app's language where no screen is at hand; see [app.parda.ui.str]. */
        lateinit var instance: PardaApp
            private set
    }
}

val android.content.Context.store: PardaStore get() = (applicationContext as PardaApp).store
