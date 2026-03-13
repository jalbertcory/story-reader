package com.storyreader.reader.tts

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CompletableDeferred
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * MediaSessionService hosting the TTS playback session. This allows Android to show
 * media controls in the notification shade and on paired watches / headphones.
 */
@OptIn(ExperimentalReadiumApi::class)
class TtsMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    inner class LocalBinder : android.os.Binder() {

        fun openSession(navigator: AndroidTtsNavigator) {
            closeSession()
            val session = MediaSession.Builder(applicationContext, navigator.asMedia3Player())
                .setSessionActivity(createReaderIntent())
                .build()
            addSession(session)
            mediaSession = session
        }

        fun closeSession() {
            mediaSession?.release()
            mediaSession = null
        }
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_BIND) binder else super.onBind(intent)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun createReaderIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    companion object {
        const val ACTION_BIND = "com.storyreader.reader.tts.TtsMediaService"

        suspend fun bind(application: Application): LocalBinder? {
            val deferred = CompletableDeferred<LocalBinder?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    deferred.complete(service as? LocalBinder)
                }
                override fun onServiceDisconnected(name: ComponentName) {}
                override fun onNullBinding(name: ComponentName) {
                    deferred.complete(null)
                }
            }
            val intent = Intent(ACTION_BIND).setClass(application, TtsMediaService::class.java)
            application.startService(intent)
            application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            return deferred.await()
        }
    }
}
