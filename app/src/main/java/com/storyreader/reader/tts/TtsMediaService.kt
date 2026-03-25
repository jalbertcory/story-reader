package com.storyreader.reader.tts

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
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
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.ui.reader.ChapterMatch
import com.storyreader.ui.reader.flattenTocLinks
import com.storyreader.ui.reader.matchChapterByHref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesSerializer
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.content.content
import kotlin.math.abs

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
    private var standaloneSessionId: Long? = null
    private var standaloneSessionStartMs: Long = 0L
    private var standaloneSessionStartProgression: Float = 0f
    private var standaloneCurrentLocator: Locator? = null
    private var standaloneLastSaveMs: Long = 0L
    private var standaloneLocationJob: Job? = null
    private var standaloneStartupJob: Job? = null
    private var standaloneMetadataPlayer: NowPlayingPlayer? = null
    private var standaloneCoverArt: ByteArray? = null

    // Stub player used when no TTS is active (allows session to exist for browsing)
    private val stubPlayer by lazy { StubPlayer(Looper.getMainLooper()) }

    /**
     * Callback for when Android Auto requests TTS playback. If a [ReaderViewModel] is
     * active and registers this callback, it can handle the request directly (with
     * highlight sync) instead of falling back to standalone mode.
     */
    fun interface TtsPlaybackRequestListener {
        /** Called on the main thread. Return true if handled. */
        fun onTtsPlaybackRequested(bookId: String): Boolean
    }

    inner class LocalBinder : android.os.Binder() {

        /** True when Android Auto is driving TTS playback independently. */
        val isStandaloneTtsActive: Boolean get() = standaloneTtsNavigator != null

        /**
         * Register a listener for Android Auto TTS requests. When AA starts a book
         * that the phone UI already has open, the listener can start TTS through the
         * normal ViewModel flow (with highlight sync) instead of standalone mode.
         */
        var ttsPlaybackRequestListener: TtsPlaybackRequestListener? = null

        private var sessionMetadataPlayer: NowPlayingPlayer? = null

        fun openSession(navigator: AndroidTtsNavigator) {
            closeSession()
            // Clear any standalone playback
            cleanupStandalonePlayback()
            val metadataPlayer = NowPlayingPlayer(navigator.asMedia3Player())
            sessionMetadataPlayer = metadataPlayer
            createOrUpdateSession(metadataPlayer)
        }

        /** Push updated now-playing metadata (chapter name, progress, cover). */
        fun updateSessionMetadata(metadata: MediaMetadata) {
            sessionMetadataPlayer?.updateMetadata(metadata)
        }

        fun closeSession() {
            sessionMetadataPlayer = null
            releaseSession()
        }
    }

    private val binder = LocalBinder()

    private val libraryCallback = object : MediaLibrarySession.Callback {

        // Explicitly accept all controllers (including Android Auto) with the default session and
        // library commands plus all available player commands. Without this, Media3's default
        // grants Player.Commands.EMPTY to external controllers, which causes Android Auto to treat
        // the app as an invalid media source and hide it from the apps carousel.
        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
                Player.Commands.Builder().addAllCommands().build()
            )

        @OptIn(UnstableApi::class)
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
            // Android Auto requires content style extras to display and recognise the app as a
            // valid media source. Without CONTENT_STYLE_SUPPORTED the app may be hidden entirely.
            val extras = Bundle().apply {
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1) // LIST
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)  // LIST
            }
            val rootParams = LibraryParams.Builder().setExtras(extras).build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    val items = buildChildrenFor(parentId)
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } catch (_: Exception) {
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

            Log.d(TAG, "onSetMediaItems: bookId=$bookId, current=$standaloneBookId")

            // If the phone UI has this book open, let the ViewModel handle TTS
            // (gives highlight sync, visual page-turning, unified position tracking).
            val handled = binder.ttsPlaybackRequestListener?.onTtsPlaybackRequested(bookId) == true
            if (handled) {
                Log.d(TAG, "onSetMediaItems: delegated to ViewModel")
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        mediaItems, startIndex, startPositionMs
                    )
                )
            }

            // Cancel any in-flight startup so two coroutines don't race
            standaloneStartupJob?.cancel()
            this@TtsMediaService.mediaSession?.setPlayer(stubPlayer)
            cleanupStandalonePlayback()

            standaloneStartupJob = serviceScope.launch {
                startStandalonePlayback(bookId)
            }

            // Resolve immediately — actual playback starts inside the coroutine.
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    mediaItems, startIndex, startPositionMs
                )
            )
        }

        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Log.d(TAG, "onPlaybackResumption: current=$standaloneBookId")
            // When AA resumes after a pause, re-start the last book if we have one
            val bookId = standaloneBookId
            if (bookId != null && standaloneTtsNavigator != null) {
                standaloneTtsNavigator?.play()
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        listOf(MediaItem.Builder().setMediaId(bookId).build()),
                        0, 0
                    )
                )
            }
            return Futures.immediateFailedFuture(
                IllegalStateException("No book to resume")
            )
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
        mediaSession?.let {
            removeSession(it)
            it.release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Build the child items for a given parent in the browse tree.
     *
     * Browse hierarchy:
     *   root
     *   ├── recently_played  → books sorted by last-read time (max 20)
     *   ├── all_books        → all books alphabetically
     *   ├── by_author        → folder per author
     *   │   └── author:<name> → books by that author
     *   └── by_series        → folder per series
     *       └── series:<name> → books in that series
     */
    private suspend fun buildChildrenFor(parentId: String): List<MediaItem> {
        val app = application as StoryReaderApplication
        return when {
            parentId == "root" -> buildRootCategories()
            parentId == BROWSE_RECENTLY_PLAYED -> buildRecentlyPlayed(app)
            parentId == BROWSE_ALL_BOOKS -> buildAllBooks(app)
            parentId == BROWSE_BY_AUTHOR -> buildAuthorFolders(app)
            parentId == BROWSE_BY_SERIES -> buildSeriesFolders(app)
            parentId.startsWith(PREFIX_AUTHOR) -> buildBooksForAuthor(app, parentId.removePrefix(PREFIX_AUTHOR))
            parentId.startsWith(PREFIX_SERIES) -> buildBooksForSeries(app, parentId.removePrefix(PREFIX_SERIES))
            else -> emptyList()
        }
    }

    private fun buildRootCategories(): List<MediaItem> = listOf(
        buildFolderItem(BROWSE_RECENTLY_PLAYED, "Recently Played"),
        buildFolderItem(BROWSE_ALL_BOOKS, "All Books"),
        buildFolderItem(BROWSE_BY_AUTHOR, "By Author"),
        buildFolderItem(BROWSE_BY_SERIES, "By Series"),
    )

    private suspend fun buildRecentlyPlayed(app: StoryReaderApplication): List<MediaItem> {
        val books = app.database.bookDao().getAllOnce()
        val lastReadTimes = app.database.readingSessionDao().getLastReadTimesOnce()
            .associate { it.bookId to it.lastReadAt }
        // Only include books that have been read, sorted most-recent first
        return books
            .filter { lastReadTimes.containsKey(it.bookId) }
            .sortedByDescending { lastReadTimes[it.bookId] ?: 0L }
            .take(MAX_RECENT_BOOKS)
            .map { buildBookItem(it) }
    }

    private suspend fun buildAllBooks(app: StoryReaderApplication): List<MediaItem> =
        app.database.bookDao().getAllOnce().map { buildBookItem(it) }

    private suspend fun buildAuthorFolders(app: StoryReaderApplication): List<MediaItem> {
        val books = app.database.bookDao().getAllOnce()
        return books.map { it.author }.distinct().sorted().map { author ->
            buildFolderItem("$PREFIX_AUTHOR$author", author)
        }
    }

    private suspend fun buildSeriesFolders(app: StoryReaderApplication): List<MediaItem> {
        val seriesNames = app.database.bookDao().getLocalSeriesNames().sorted()
        return seriesNames.map { series ->
            buildFolderItem("$PREFIX_SERIES$series", series)
        }
    }

    private suspend fun buildBooksForAuthor(app: StoryReaderApplication, author: String): List<MediaItem> =
        app.database.bookDao().getAllOnce()
            .filter { it.author == author }
            .map { buildBookItem(it) }

    private suspend fun buildBooksForSeries(app: StoryReaderApplication, series: String): List<MediaItem> =
        app.database.bookDao().getAllOnce()
            .filter { it.series == series }
            .sortedWith(compareBy<BookEntity> { it.seriesIndex == null }.thenBy { it.seriesIndex }.thenBy { it.title })
            .map { buildBookItem(it) }

    private fun buildFolderItem(mediaId: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    private fun buildBookItem(book: com.storyreader.data.db.entity.BookEntity): MediaItem {
        val progressPercent = (book.totalProgression * 100).toInt()
        val displayTitle = if (progressPercent > 0) "${book.title} · $progressPercent%" else book.title
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(displayTitle)
            .setArtist(book.author)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        book.coverUri?.let { path ->
            try {
                val art = downsampleArtwork(java.io.File(path).readBytes(), BROWSE_ARTWORK_SIZE)
                metaBuilder.setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            } catch (_: Exception) { /* cover file missing */ }
        }
        return MediaItem.Builder()
            .setMediaId(book.bookId)
            .setMediaMetadata(metaBuilder.build())
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun createOrUpdateSession(player: Player) {
        val existing = mediaSession
        if (existing != null) {
            // Swap the player in-place — keeps Android Auto connected and is safe to call
            // from within active session callbacks (e.g. onSetMediaItems). Releasing and
            // recreating the session here would crash because the callback's pending future
            // still holds a reference to the old session.
            existing.setPlayer(player)
        } else {
            val session = MediaLibrarySession.Builder(this, player, libraryCallback)
                .setSessionActivity(createReaderIntent())
                .build()
            mediaSession = session
            addSession(session)
        }
    }

    @OptIn(UnstableApi::class)
    private fun releaseSession() {
        // Reset to stub player rather than releasing the session — keeps Android Auto
        // connected for browsing after TTS playback ends.
        mediaSession?.setPlayer(stubPlayer)
    }

    @OptIn(UnstableApi::class)
    private suspend fun startStandalonePlayback(bookId: String) {
        Log.d(TAG, "startStandalonePlayback: bookId=$bookId")
        val app = application as StoryReaderApplication
        val uri = bookId.toUri()
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

        // Enhance the locator so TTS can position within the chapter (needs cssSelector)
        val ttsLocator = locator?.let { enhanceLocatorForTts(publication, it) }

        // Initialize and start TTS
        val manager = TtsManager(application as Application)
        manager.initialize(publication, enginePkg)
        val nav = manager.start(ttsLocator, prefs) ?: return

        standaloneTtsManager = manager
        standaloneTtsNavigator = nav
        standalonePublication = publication
        standaloneBookId = bookId
        val bookEntity = app.database.bookDao().getByIdOnce(bookId)
        standaloneCoverArt = bookEntity?.coverUri?.let { path ->
            try { downsampleArtwork(java.io.File(path).readBytes()) } catch (_: Exception) { null }
        }

        // Create session with a wrapper that allows dynamic metadata updates
        val metadataPlayer = NowPlayingPlayer(nav.asMedia3Player())
        standaloneMetadataPlayer = metadataPlayer
        createOrUpdateSession(metadataPlayer)

        // Start a reading session for stats and record start info for finalization on stop
        standaloneSessionId = app.readingRepository.startSession(bookId, isTts = true)
        standaloneSessionStartMs = System.currentTimeMillis()
        standaloneSessionStartProgression = locator?.locations?.totalProgression?.toFloat() ?: 0f
        standaloneCurrentLocator = locator

        // Collect location updates so we can persist position periodically and update
        // the now-playing metadata (chapter name + progress) on Android Auto.
        standaloneLocationJob = serviceScope.launch {
            nav.location.collect { location ->
                val utteranceLoc = location.utteranceLocator
                standaloneCurrentLocator = utteranceLoc
                saveStandalonePositionIfDue(bookId, utteranceLoc)
                updateNowPlayingMetadata(
                    publication, nav,
                    chapterHref = location.href,
                    utteranceLocator = location.utteranceLocator
                )
            }
        }

        nav.play()
    }

    private fun cleanupStandalonePlayback() {
        Log.d(TAG, "cleanupStandalonePlayback: bookId=$standaloneBookId, hasNav=${standaloneTtsNavigator != null}")
        standaloneStartupJob?.cancel()
        standaloneStartupJob = null
        standaloneLocationJob?.cancel()
        standaloneLocationJob = null

        // Capture state before clearing fields
        val bookId = standaloneBookId
        val locator = standaloneCurrentLocator
        val sessionId = standaloneSessionId
        val startMs = standaloneSessionStartMs
        val startProgression = standaloneSessionStartProgression

        standaloneTtsNavigator?.pause()
        standaloneTtsNavigator?.close()
        standaloneTtsNavigator = null
        standaloneTtsManager?.stop()
        standaloneTtsManager = null
        standalonePublication = null
        standaloneBookId = null
        standaloneSessionId = null
        standaloneCurrentLocator = null
        standaloneMetadataPlayer = null
        standaloneCoverArt = null
        standaloneSessionStartMs = 0L
        standaloneSessionStartProgression = 0f
        standaloneLastSaveMs = 0L

        // Persist final position so the phone picks up exactly where Android Auto stopped.
        // Use a standalone scope so this survives service destruction / serviceScope cancellation.
        if (locator != null && bookId != null) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val app = application as StoryReaderApplication
                app.readingRepository.savePosition(bookId, locator.toJSON().toString())
                app.bookRepository.updateProgression(
                    bookId,
                    locator.locations.totalProgression?.toFloat() ?: 0f
                )
                if (sessionId != null) {
                    val wordCount = app.bookRepository.getWordCount(bookId)
                    app.readingRepository.finalizeSession(
                        sessionId = sessionId,
                        pageTurnTimestampsMs = emptyList(),
                        sessionStartMs = startMs,
                        isTts = true,
                        progressionStart = startProgression,
                        progressionEnd = locator.locations.totalProgression?.toFloat()
                            ?: startProgression,
                        bookWordCount = wordCount
                    )
                }
            }
        }
    }

    /**
     * Android Auto's now-playing screen shows three lines: title, artist, album.
     * We use them as: "Chapter Name · 42%" / author / "58% · Book Title"
     * where the title % is chapter-local and the album % is book-wide.
     */
    private fun updateNowPlayingMetadata(
        publication: Publication,
        nav: AndroidTtsNavigator,
        chapterHref: org.readium.r2.shared.util.Url,
        utteranceLocator: Locator
    ) {
        val player = standaloneMetadataPlayer ?: return
        val chapterIndex = nav.readingOrder.items.indexOfFirst { it.href == chapterHref }
            .takeIf { it >= 0 } ?: return
        val allLinks = flattenTocLinks(publication.tableOfContents)
        val chapterMatch = matchChapterByHref(utteranceLocator.href.toString(), allLinks)
        val chapterTitle = when (chapterMatch) {
            is ChapterMatch.Single -> chapterMatch.link.title
            is ChapterMatch.Multiple -> chapterMatch.candidates.lastOrNull()?.title
            is ChapterMatch.NormalizedFallback -> chapterMatch.link.title
            ChapterMatch.None -> null
        } ?: "Chapter ${chapterIndex + 1}"
        val chapterPercent = ((utteranceLocator.locations.progression ?: 0.0) * 100).toInt()
        val bookPercent = ((utteranceLocator.locations.totalProgression ?: 0.0) * 100).toInt()
        val bookTitle = publication.metadata.title
        val author = publication.metadata.authors.firstOrNull()?.name

        val metaBuilder = MediaMetadata.Builder()
            .setTitle("$chapterTitle · $chapterPercent%")
            .setArtist(author)
            .setAlbumTitle("$bookPercent% · $bookTitle")
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .setIsBrowsable(false)
        standaloneCoverArt?.let { metaBuilder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        val metadata = metaBuilder.build()

        player.updateMetadata(metadata)
    }

    /**
     * If the locator lacks a cssSelector (e.g. saved from the visual epub reader), Readium's
     * HtmlResourceContentIterator cannot position within the chapter and falls back to position 0.
     * Work around this by iterating the publication content to find the element whose
     * totalProgression is closest to the saved value — its locator will carry a cssSelector.
     */
    private suspend fun enhanceLocatorForTts(
        publication: Publication,
        locator: Locator
    ): Locator {
        if (locator.locations.otherLocations.containsKey("cssSelector")) return locator

        val targetProgression = locator.locations.totalProgression ?: return locator
        val readingOrderIndex =
            publication.readingOrder.indexOfFirstWithHref(locator.href) ?: return locator
        val link = publication.readingOrder[readingOrderIndex]
        val chapterLocator = publication.locatorFromLink(link) ?: return locator

        val content = publication.content(chapterLocator) ?: return locator
        val iterator = content.iterator()

        var bestLocator: Locator? = null
        var bestDiff = Double.MAX_VALUE

        while (iterator.hasNext()) {
            val element = iterator.next()
            if (element.locator.href != locator.href) break
            val elementProgression = element.locator.locations.totalProgression ?: continue
            val diff = abs(elementProgression - targetProgression)
            if (diff < bestDiff) {
                bestDiff = diff
                bestLocator = element.locator
            }
            if (elementProgression > targetProgression) break
        }

        return bestLocator ?: locator
    }

    /**
     * Debounced position save during standalone TTS — writes at most every 5 seconds
     * so the position survives app kill without hammering the DB.
     */
    private fun saveStandalonePositionIfDue(bookId: String, locator: Locator) {
        val now = System.currentTimeMillis()
        if (now - standaloneLastSaveMs < 5_000) return
        standaloneLastSaveMs = now
        serviceScope.launch {
            val app = application as StoryReaderApplication
            app.readingRepository.savePosition(bookId, locator.toJSON().toString())
            app.bookRepository.updateProgression(
                bookId,
                locator.locations.totalProgression?.toFloat() ?: 0f
            )
        }
    }

    private fun loadTtsPreferences(): AndroidTtsPreferences {
        val prefStore = getSharedPreferences("reader_preferences", MODE_PRIVATE)
        val serialized = prefStore.getString("tts_prefs_json", null)
            ?: return AndroidTtsPreferences(speed = 1.5)
        return runCatching { AndroidTtsPreferencesSerializer().deserialize(serialized) }
            .getOrDefault(AndroidTtsPreferences(speed = 1.5))
    }

    private fun loadTtsEngine(): String? {
        val prefStore = getSharedPreferences("reader_preferences", MODE_PRIVATE)
        return prefStore.getString("tts_engine", null)
    }


    private fun createReaderIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    companion object {
        private const val TAG = "TtsMediaService"

        // Browse tree node IDs
        private const val BROWSE_RECENTLY_PLAYED = "recently_played"
        private const val BROWSE_ALL_BOOKS = "all_books"
        private const val BROWSE_BY_AUTHOR = "by_author"
        private const val BROWSE_BY_SERIES = "by_series"
        private const val PREFIX_AUTHOR = "author:"
        private const val PREFIX_SERIES = "series:"
        private const val MAX_RECENT_BOOKS = 20

        /** Small thumbnail for browse lists where many items are sent at once. */
        private const val BROWSE_ARTWORK_SIZE = 300
        /** Larger artwork for the now-playing screen (Android Auto, Wear OS, notification). */
        private const val NOW_PLAYING_ARTWORK_SIZE = 800

        /**
         * Downscale cover art bytes to fit within [maxSize]x[maxSize]
         * so the MediaSession metadata stays well under the 1 MB Binder transaction limit.
         */
        fun downsampleArtwork(raw: ByteArray, maxSize: Int = NOW_PLAYING_ARTWORK_SIZE): ByteArray {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= maxSize && h <= maxSize) return raw

            val scale = maxOf(w, h).toFloat() / maxSize
            val sampleSize = Integer.highestOneBit(scale.toInt().coerceAtLeast(1))
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOpts) ?: return raw

            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            return out.toByteArray()
        }
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
            application.bindService(intent, connection, BIND_AUTO_CREATE)
            return deferred.await()
        }
    }
}

/**
 * Wraps the TTS media3 player to allow dynamic metadata updates. Android Auto reads metadata from
 * [Player.getMediaMetadata]; by overriding it we can show the current chapter name and progress
 * percentage on the now-playing screen without modifying Readium's internal TtsSessionAdapter.
 */
@OptIn(UnstableApi::class)
private class NowPlayingPlayer(player: Player) : ForwardingPlayer(player) {

    private var customMetadata: MediaMetadata? = null
    private val metadataListeners = mutableListOf<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        metadataListeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        metadataListeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getMediaMetadata(): MediaMetadata {
        return customMetadata ?: super.getMediaMetadata()
    }

    fun updateMetadata(metadata: MediaMetadata) {
        if (metadata == customMetadata) return
        customMetadata = metadata
        for (listener in metadataListeners.toList()) {
            listener.onMediaMetadataChanged(metadata)
        }
    }
}

/**
 * Minimal player implementation for session initialization before TTS is started.
 * Exposes the basic commands Android Auto expects so it treats the app as a valid
 * media source while no TTS session is active. Actual playback is initiated via
 * onSetMediaItems in the session callback, so these handlers are intentional no-ops.
 */
@OptIn(UnstableApi::class)
private class StubPlayer(looper: Looper) : SimpleBasePlayer(looper) {
    override fun getState(): State = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_STOP)
                .add(Player.COMMAND_SET_MEDIA_ITEM)
                .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
        )
        .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> = Futures.immediateVoidFuture()
}
