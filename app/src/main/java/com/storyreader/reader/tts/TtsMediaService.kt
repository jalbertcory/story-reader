package com.storyreader.reader.tts

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.storyreader.StoryReaderApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesSerializer
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * MediaLibraryService hosting the TTS playback session. This allows Android to show
 * media controls in the notification shade, on paired watches/headphones, and in Android Auto.
 * Android Auto can also browse the book library and start TTS playback.
 */
@OptIn(ExperimentalReadiumApi::class)
class TtsMediaService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Standalone playback state (for Android Auto initiated playback)
    private var standaloneTtsManager: TtsManager? = null
    private var standaloneTtsNavigator: AndroidTtsNavigator? = null
    private var standalonePublication: Publication? = null
    private var standaloneBookId: String? = null

    // Stub player used when no TTS is active (allows session to exist for browsing)
    private val stubPlayer by lazy { StubPlayer(Looper.getMainLooper()) }

    inner class LocalBinder : android.os.Binder() {

        fun openSession(navigator: AndroidTtsNavigator) {
            closeSession()
            // Clear any standalone playback
            cleanupStandalonePlayback()
            val player = navigator.asMedia3Player()
            createOrUpdateSession(player)
        }

        fun closeSession() {
            releaseSession()
        }
    }

    private val binder = LocalBinder()

    private val libraryCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("Story Reader")
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != "root") {
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.of(), params)
                )
            }
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    val app = application as StoryReaderApplication
                    val books = app.database.bookDao().getAllOnce()
                    val items = books.map { book ->
                        MediaItem.Builder()
                            .setMediaId(book.bookId)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(book.title)
                                    .setArtist(book.author)
                                    .setIsPlayable(true)
                                    .setIsBrowsable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .build()
                            )
                            .build()
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } catch (e: Exception) {
                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
            return future
        }

        @OptIn(UnstableApi::class)
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val bookId = mediaItems.firstOrNull()?.mediaId
                ?: return Futures.immediateFailedFuture(IllegalArgumentException("No book"))
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                startStandalonePlayback(bookId)
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        mediaItems, startIndex, startPositionMs
                    )
                )
            }
            return future
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Create session with stub player for browsing
        createOrUpdateSession(stubPlayer)
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_BIND) binder else super.onBind(intent)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onDestroy() {
        cleanupStandalonePlayback()
        releaseSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createOrUpdateSession(player: Player) {
        mediaSession?.let {
            removeSession(it)
            it.release()
        }
        val session = MediaLibrarySession.Builder(this, player, libraryCallback)
            .setSessionActivity(createReaderIntent())
            .build()
        mediaSession = session
        addSession(session)
    }

    private fun releaseSession() {
        mediaSession?.let {
            removeSession(it)
            it.release()
        }
        mediaSession = null
        // Re-create stub session for browsing
        if (standaloneTtsNavigator == null) {
            createOrUpdateSession(stubPlayer)
        }
    }

    private suspend fun startStandalonePlayback(bookId: String) {
        cleanupStandalonePlayback()

        val app = application as StoryReaderApplication
        val uri = Uri.parse(bookId)
        val publication = app.epubRepository.openPublication(uri).getOrNull() ?: return

        // Get last reading position
        val positionDao = app.database.readingPositionDao()
        val savedPosition = positionDao.getLatestPositionOnce(bookId)
        val locator = savedPosition?.let {
            Locator.fromJSON(org.json.JSONObject(it.locatorJson))
        }

        // Load TTS preferences
        val prefs = loadTtsPreferences()
        val enginePkg = loadTtsEngine()

        // Initialize and start TTS
        val manager = TtsManager(application as Application)
        manager.initialize(publication, enginePkg)
        val nav = manager.start(locator, prefs) ?: return

        standaloneTtsManager = manager
        standaloneTtsNavigator = nav
        standalonePublication = publication
        standaloneBookId = bookId

        // Create session with TTS player
        createOrUpdateSession(nav.asMedia3Player())

        // Start a reading session for stats
        app.readingRepository.startSession(bookId, isTts = true)

        nav.play()
    }

    private fun cleanupStandalonePlayback() {
        standaloneTtsNavigator?.close()
        standaloneTtsNavigator = null
        standaloneTtsManager?.stop()
        standaloneTtsManager = null
        standalonePublication = null
        standaloneBookId = null
    }

    private fun loadTtsPreferences(): AndroidTtsPreferences {
        val prefStore = getSharedPreferences("reader_preferences", Context.MODE_PRIVATE)
        val serialized = prefStore.getString("tts_prefs_json", null)
            ?: return AndroidTtsPreferences(speed = 1.5)
        return runCatching { AndroidTtsPreferencesSerializer().deserialize(serialized) }
            .getOrDefault(AndroidTtsPreferences(speed = 1.5))
    }

    private fun loadTtsEngine(): String? {
        val prefStore = getSharedPreferences("reader_preferences", Context.MODE_PRIVATE)
        return prefStore.getString("tts_engine", null)
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
            val deferred = kotlinx.coroutines.CompletableDeferred<LocalBinder?>()
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

/**
 * Minimal player implementation for session initialization before TTS is started.
 * Allows the MediaLibrarySession to exist for browse-only scenarios.
 */
@OptIn(UnstableApi::class)
private class StubPlayer(looper: Looper) : SimpleBasePlayer(looper) {
    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.EMPTY)
        .build()
}
