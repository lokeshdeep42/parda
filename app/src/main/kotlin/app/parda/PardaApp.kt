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
        store = PardaStore(this)
        thread(name = "parda-model") { store.loadModel() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_INTERCEPTS, getString(R.string.channel_intercepts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_INTERCEPTS = "intercepts"
    }
}

val android.content.Context.store: PardaStore get() = (applicationContext as PardaApp).store
