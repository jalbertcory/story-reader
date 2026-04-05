package com.storyreader.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyreader.data.db.AppDatabase
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingPositionEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import androidx.core.content.edit
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncPayloadStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: SyncPayloadStore
    private lateinit var appStateStore: SyncAppStateStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        context.getSharedPreferences(READER_PREFS_NAME, android.content.Context.MODE_PRIVATE).edit { clear() }
        context.getSharedPreferences(GOALS_PREFS_NAME, android.content.Context.MODE_PRIVATE).edit { clear() }
        appStateStore = SyncAppStateStore(context)

        store = SyncPayloadStore(
            positionDao = db.readingPositionDao(),
            sessionDao = db.readingSessionDao(),
            bookDao = db.bookDao(),
            appStateStore = appStateStore
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `buildLatestJson groups book metadata progress and sessions`() = runTest {
        val syncId = "sync-book-1"
        db.bookDao().insert(
            BookEntity(
                bookId = "local-book-1",
                title = "Test Book",
                author = "Author",
                syncId = syncId,
                syncSourceKind = SyncSourceKinds.OPDS,
                syncSourceUrl = "https://catalog.example/books/1.epub",
                originalFileName = "test-book.epub",
                totalProgression = 0.55f,
                series = "Series A",
                seriesIndex = 2f
            )
        )
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(
                bookId = "local-book-1",
                locatorJson = """{"locations":{"totalProgression":0.6}}""",
                timestamp = 5_000L
            )
        )
        db.readingSessionDao().insert(
            ReadingSessionEntity(
                bookId = "local-book-1",
                startTime = 9_000L,
                durationSeconds = 120,
                rawDurationSeconds = 130,
                pagesTurned = 7,
                wordsRead = 900,
                isTts = false
            )
        )

        val json = store.buildLatestJson()

        assertEquals(3, json.getInt("schemaVersion"))
        val books = json.getJSONArray("books")
        assertEquals(1, books.length())

        val book = books.getJSONObject(0)
        assertEquals(syncId, book.getString("syncId"))
        assertEquals("Test Book", book.getString("title"))
        assertEquals("Author", book.getString("author"))
        assertEquals("Series A", book.getString("series"))
        assertEquals(2.0, book.getDouble("seriesIndex"), 0.001)

        val source = book.getJSONObject("source")
        assertEquals(SyncSourceKinds.OPDS, source.getString("kind"))
        assertEquals("https://catalog.example/books/1.epub", source.getString("url"))
        assertEquals("test-book.epub", source.getString("originalFileName"))

        val progress = book.getJSONObject("progress")
        assertEquals(0.6, progress.getDouble("furthestProgress"), 0.001)
        assertEquals(false, progress.getBoolean("isCompleted"))
        assertEquals(5_000L, progress.getJSONObject("latestPosition").getLong("timestamp"))

        val sessions = book.getJSONArray("sessions")
        assertEquals(1, sessions.length())
        assertEquals(9_000L, sessions.getJSONObject(0).getLong("startTime"))
    }

    @Test
    fun `buildLatestJson computes syncId for older books missing one`() = runTest {
        db.bookDao().insert(
            BookEntity(bookId = "legacy-book", title = "Dune", author = "Frank Herbert")
        )

        val json = store.buildLatestJson()
        val syncId = json.getJSONArray("books").getJSONObject(0).getString("syncId")

        assertEquals(BookSyncMetadata.syncIdFor("Dune", "Frank Herbert"), syncId)
        val persisted = db.bookDao().getByIdOnce("legacy-book")
        assertEquals(syncId, persisted?.syncId)
    }

    @Test
    fun `buildLatestJson excludes sessions shorter than 10 seconds`() = runTest {
        db.bookDao().insert(BookEntity(bookId = "book1", title = "Book", author = "Author"))
        db.readingSessionDao().insert(
            ReadingSessionEntity(bookId = "book1", startTime = 1_000L, durationSeconds = 5)
        )
        db.readingSessionDao().insert(
            ReadingSessionEntity(bookId = "book1", startTime = 2_000L, durationSeconds = 60)
        )

        val json = store.buildLatestJson()
        val sessions = json.getJSONArray("books").getJSONObject(0).getJSONArray("sessions")

        assertEquals(1, sessions.length())
        assertEquals(2_000L, sessions.getJSONObject(0).getLong("startTime"))
    }

    @Test
    fun `buildLatestJson infers story manager recovery metadata for older web books`() = runTest {
        db.bookDao().insert(
            BookEntity(
                bookId = "legacy-web-book",
                title = "Web Book",
                author = "Author",
                sourceType = "web",
                serverBookId = 42
            )
        )

        val json = store.buildLatestJson()
        val book = json.getJSONArray("books").getJSONObject(0)
        val source = book.getJSONObject("source")

        assertEquals(SyncSourceKinds.STORY_MANAGER, source.getString("kind"))
        assertEquals("/reader/books/42/download", source.getString("url"))
        assertEquals(42, source.getInt("serverBookId"))
        assertEquals("web", book.getString("sourceType"))
    }

    @Test
    fun `buildLatestJson includes reader tts and goal settings`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(READER_PREFS_NAME, android.content.Context.MODE_PRIVATE).edit {
            putFloat(KEY_FONT_SIZE, 1.8f)
            putString(KEY_THEME, "SEPIA")
            putString(KEY_FONT_FAMILY, "Literata")
            putBoolean(KEY_IS_NIGHT, false)
            putBoolean(KEY_SCROLL, true)
            putString(KEY_TEXT_ALIGN, "JUSTIFY")
            putFloat(KEY_BRIGHTNESS_LEVEL, 0.75f)
            putString(KEY_TTS_PREFS, """{"speed":1.7}""")
            putString(KEY_TTS_ENGINE, "engine.pkg")
            putLong(KEY_SYNC_READER_UPDATED_AT, 100L)
            putLong(KEY_SYNC_TTS_UPDATED_AT, 200L)
        }
        context.getSharedPreferences(GOALS_PREFS_NAME, android.content.Context.MODE_PRIVATE).edit {
            putInt(KEY_GOAL_HOURS, 120)
            putInt(KEY_GOAL_WORDS, 900_000)
            putLong(KEY_SYNC_GOALS_UPDATED_AT, 300L)
        }

        val json = store.buildLatestJson()
        val app = json.getJSONObject("app")
        val reader = app.getJSONObject("reader")
        val tts = app.getJSONObject("tts")
        val goals = app.getJSONObject("goals")

        assertEquals(1.8, reader.getDouble("fontSize"), 0.001)
        assertEquals("SEPIA", reader.getString("theme"))
        assertEquals("Literata", reader.getString("fontFamily"))
        assertEquals(true, reader.getBoolean("scrollMode"))
        assertEquals("JUSTIFY", reader.getString("textAlign"))
        assertEquals(0.75, reader.getDouble("brightnessLevel"), 0.001)
        assertEquals("""{"speed":1.7}""", tts.getString("preferencesJson"))
        assertEquals("engine.pkg", tts.getString("enginePackageName"))
        assertEquals(120, goals.getInt("hoursPerYear"))
        assertEquals(900_000, goals.getInt("wordsPerYear"))
    }

    @Test
    fun `mergeRemoteData matches local book by syncId and imports newer resume data`() = runTest {
        val syncId = BookSyncMetadata.syncIdFor("Shared Book", "Shared Author")
        db.bookDao().insert(
            BookEntity(
                bookId = "device-a-file",
                title = "Shared Book",
                author = "Shared Author",
                syncId = syncId,
                totalProgression = 0.2f
            )
        )

        store.mergeRemoteData(
            remoteJson(
                remoteBookJson(
                    syncId = syncId,
                    title = "Shared Book",
                    author = "Shared Author",
                    furthestProgress = 0.75f,
                    latestPositionTimestamp = 10_000L,
                    latestLocatorJson = """{"locations":{"totalProgression":0.4}}""",
                    sessions = listOf(
                        remoteSessionJson(
                            id = BookSyncMetadata.sessionIdFor(syncId, 12_000L, false),
                            startTime = 12_000L,
                            durationSeconds = 90
                        )
                    )
                )
            )
        )

        val latestPosition = db.readingPositionDao().getLatestPositionOnce("device-a-file")
        val session = db.readingSessionDao().findByBookIdAndStartTime("device-a-file", 12_000L)
        val updatedBook = db.bookDao().getByIdOnce("device-a-file")

        assertNotNull(latestPosition)
        assertEquals(10_000L, latestPosition?.timestamp)
        assertNotNull(session)
        assertEquals(90, session?.durationSeconds)
        assertEquals(0.75f, updatedBook?.totalProgression ?: 0f, 0.001f)
    }

    @Test
    fun `mergeRemoteData preserves complete progress separately from latest locator`() = runTest {
        val syncId = BookSyncMetadata.syncIdFor("Marked Complete", "Author")
        db.bookDao().insert(
            BookEntity(
                bookId = "local-complete",
                title = "Marked Complete",
                author = "Author",
                syncId = syncId,
                totalProgression = 0.6f
            )
        )

        store.mergeRemoteData(
            remoteJson(
                remoteBookJson(
                    syncId = syncId,
                    title = "Marked Complete",
                    author = "Author",
                    furthestProgress = 1.0f,
                    isCompleted = true,
                    latestPositionTimestamp = 8_000L,
                    latestLocatorJson = """{"locations":{"totalProgression":0.62}}"""
                )
            )
        )

        val latestPosition = db.readingPositionDao().getLatestPositionOnce("local-complete")
        val updatedBook = db.bookDao().getByIdOnce("local-complete")

        assertEquals(8_000L, latestPosition?.timestamp)
        assertEquals(1f, updatedBook?.totalProgression ?: 0f, 0.001f)
    }

    @Test
    fun `buildLatestJson excludes pre-restart positions and sessions`() = runTest {
        val restartAt = 10_000L
        db.bookDao().insert(
            BookEntity(
                bookId = "restart-book",
                title = "Restarted",
                author = "Author",
                syncId = "restart-sync",
                restartAt = restartAt
            )
        )
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(
                bookId = "restart-book",
                locatorJson = """{"locations":{"totalProgression":0.8}}""",
                timestamp = 9_000L
            )
        )
        db.readingSessionDao().insert(
            ReadingSessionEntity(
                bookId = "restart-book",
                startTime = 9_500L,
                durationSeconds = 120
            )
        )

        val json = store.buildLatestJson()
        val book = json.getJSONArray("books").getJSONObject(0)
        val progress = book.getJSONObject("progress")

        assertEquals(restartAt, progress.getLong("restartAt"))
        assertEquals(0.0, progress.getDouble("furthestProgress"), 0.001)
        assertEquals(0, book.getJSONArray("sessions").length())
        assertEquals(false, progress.has("latestPosition"))
    }

    @Test
    fun `mergeRemoteData honors newer remote restart over older local progress`() = runTest {
        val syncId = BookSyncMetadata.syncIdFor("Restart Sync", "Author")
        db.bookDao().insert(
            BookEntity(
                bookId = "restart-local",
                title = "Restart Sync",
                author = "Author",
                syncId = syncId,
                totalProgression = 1f
            )
        )
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(
                bookId = "restart-local",
                locatorJson = """{"locations":{"totalProgression":1.0}}""",
                timestamp = 5_000L
            )
        )
        db.readingSessionDao().insert(
            ReadingSessionEntity(
                bookId = "restart-local",
                startTime = 6_000L,
                durationSeconds = 180
            )
        )

        store.mergeRemoteData(
            remoteJson(
                remoteBookJson(
                    syncId = syncId,
                    title = "Restart Sync",
                    author = "Author",
                    furthestProgress = 0f,
                    restartAt = 20_000L
                )
            )
        )

        val updatedBook = db.bookDao().getByIdOnce("restart-local")
        assertEquals(0f, updatedBook?.totalProgression ?: -1f, 0.001f)
        assertEquals(20_000L, updatedBook?.restartAt)
        assertEquals(null, db.readingPositionDao().getLatestPositionOnce("restart-local"))
        assertTrue(db.readingSessionDao().getAllSessionsOnce().isEmpty())
    }

    @Test
    fun `mergeRemoteData skips remote-only books instead of creating stubs`() = runTest {
        store.mergeRemoteData(
            remoteJson(
                remoteBookJson(
                    syncId = "missing-book",
                    title = "Ghost",
                    author = "Writer",
                    furthestProgress = 0.4f
                )
            )
        )

        assertTrue(db.bookDao().getAllIncludingHiddenOnce().isEmpty())
        assertTrue(db.readingPositionDao().getLatestPositionPerBook().isEmpty())
        assertTrue(db.readingSessionDao().getAllSessionsOnce().isEmpty())
    }

    @Test
    fun `buildLatestJson preserves remote-only books while uploading merged payload`() = runTest {
        val localSyncId = BookSyncMetadata.syncIdFor("Local", "Author")
        db.bookDao().insert(
            BookEntity(
                bookId = "local-book",
                title = "Local",
                author = "Author",
                syncId = localSyncId
            )
        )

        val merged = store.buildLatestJson(
            remoteJson(
                remoteBookJson(
                    syncId = "remote-only",
                    title = "Remote Only",
                    author = "Elsewhere",
                    furthestProgress = 0.9f,
                    sourceKind = SyncSourceKinds.NEXTCLOUD,
                    sourceUrl = "https://nextcloud.example/remote-only.epub",
                    originalFileName = "remote-only.epub"
                )
            )
        )

        val books = merged.getJSONArray("books")
        val syncIds = (0 until books.length()).map { books.getJSONObject(it).getString("syncId") }.toSet()

        assertEquals(setOf(localSyncId, "remote-only"), syncIds)
    }

    @Test
    fun `mergeRemoteData backfills source metadata and series on matching local book`() = runTest {
        val syncId = BookSyncMetadata.syncIdFor("Metadata Book", "Author")
        db.bookDao().insert(
            BookEntity(
                bookId = "metadata-book",
                title = "Metadata Book",
                author = "Author",
                syncId = syncId
            )
        )

        store.mergeRemoteData(
            remoteJson(
                remoteBookJson(
                    syncId = syncId,
                    title = "Metadata Book",
                    author = "Author",
                    furthestProgress = 0.2f,
                    sourceType = "web",
                    sourceKind = SyncSourceKinds.OPDS,
                    sourceUrl = "https://catalog.example/metadata-book.epub",
                    originalFileName = "metadata-book.epub",
                    series = "Recovered Series",
                    seriesIndex = 4f,
                    serverBookId = 77
                )
            )
        )

        val updated = db.bookDao().getByIdOnce("metadata-book")
        assertEquals(SyncSourceKinds.OPDS, updated?.syncSourceKind)
        assertEquals("https://catalog.example/metadata-book.epub", updated?.syncSourceUrl)
        assertEquals("metadata-book.epub", updated?.originalFileName)
        assertEquals("Recovered Series", updated?.series)
        assertEquals(4f, updated?.seriesIndex ?: 0f, 0.001f)
        assertEquals(77, updated?.serverBookId)
        assertEquals("web", updated?.sourceType)
    }

    @Test
    fun `mergeRemoteData applies newer app settings`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(READER_PREFS_NAME, android.content.Context.MODE_PRIVATE).edit {
            putFloat(KEY_FONT_SIZE, 1.2f)
            putLong(KEY_SYNC_READER_UPDATED_AT, 100L)
            putLong(KEY_SYNC_TTS_UPDATED_AT, 100L)
        }
        context.getSharedPreferences(GOALS_PREFS_NAME, android.content.Context.MODE_PRIVATE).edit {
            putInt(KEY_GOAL_HOURS, 50)
            putInt(KEY_GOAL_WORDS, 500_000)
            putLong(KEY_SYNC_GOALS_UPDATED_AT, 100L)
        }

        store.mergeRemoteData(
            remoteJson(
                remoteBookJson(
                    syncId = "remote-book",
                    title = "Remote",
                    author = "Author",
                    furthestProgress = 0f
                )
            ).apply {
                put(
                    "app",
                    JSONObject().apply {
                        put(
                            "reader",
                            JSONObject().apply {
                                put("updatedAt", 200L)
                                put("fontSize", 1.9)
                                put("theme", "DARK")
                                put("isNightTheme", false)
                                put("brightnessLevel", 0.4)
                            }
                        )
                        put(
                            "tts",
                            JSONObject().apply {
                                put("updatedAt", 200L)
                                put("preferencesJson", """{"speed":2.0}""")
                                put("enginePackageName", "updated.engine")
                            }
                        )
                        put(
                            "goals",
                            JSONObject().apply {
                                put("updatedAt", 200L)
                                put("hoursPerYear", 80)
                                put("wordsPerYear", 750000)
                            }
                        )
                    }
                )
            }
        )

        val readerPrefs = context.getSharedPreferences(READER_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val goalsPrefs = context.getSharedPreferences(GOALS_PREFS_NAME, android.content.Context.MODE_PRIVATE)

        assertEquals(1.9f, readerPrefs.getFloat(KEY_FONT_SIZE, 0f), 0.001f)
        assertEquals("DARK", readerPrefs.getString(KEY_THEME, null))
        assertEquals(0.4f, readerPrefs.getFloat(KEY_BRIGHTNESS_LEVEL, 0f), 0.001f)
        assertEquals("""{"speed":2.0}""", readerPrefs.getString(KEY_TTS_PREFS, null))
        assertEquals("updated.engine", readerPrefs.getString(KEY_TTS_ENGINE, null))
        assertEquals(80, goalsPrefs.getInt(KEY_GOAL_HOURS, 0))
        assertEquals(750_000, goalsPrefs.getInt(KEY_GOAL_WORDS, 0))
    }

    private fun remoteJson(vararg books: JSONObject): JSONObject =
        JSONObject().apply {
            put("schemaVersion", 3)
            put("books", JSONArray().apply { books.forEach { put(it) } })
        }

    private fun remoteBookJson(
        syncId: String,
        title: String,
        author: String,
        furthestProgress: Float,
        sourceType: String? = null,
        isCompleted: Boolean = false,
        latestPositionTimestamp: Long? = null,
        latestLocatorJson: String? = null,
        sourceKind: String? = null,
        sourceUrl: String? = null,
        originalFileName: String? = null,
        series: String? = null,
        seriesIndex: Float? = null,
        serverBookId: Int? = null,
        restartAt: Long? = null,
        sessions: List<JSONObject> = emptyList()
    ): JSONObject = JSONObject().apply {
        put("syncId", syncId)
        put("title", title)
        put("author", author)
        if (sourceType != null) put("sourceType", sourceType)
        if (series != null) put("series", series)
        if (seriesIndex != null) put("seriesIndex", seriesIndex.toDouble())
        put(
            "source",
            JSONObject().apply {
                if (sourceKind != null) put("kind", sourceKind)
                if (sourceUrl != null) put("url", sourceUrl)
                if (serverBookId != null) put("serverBookId", serverBookId)
                if (originalFileName != null) put("originalFileName", originalFileName)
            }
        )
        put(
            "progress",
            JSONObject().apply {
                if (restartAt != null) put("restartAt", restartAt)
                put("furthestProgress", furthestProgress.toDouble())
                put("isCompleted", isCompleted)
                if (latestPositionTimestamp != null && latestLocatorJson != null) {
                    put(
                        "latestPosition",
                        JSONObject().apply {
                            put("timestamp", latestPositionTimestamp)
                            put("locatorJson", latestLocatorJson)
                        }
                    )
                }
            }
        )
        put("sessions", JSONArray().apply { sessions.forEach { put(it) } })
    }

    private fun remoteSessionJson(
        id: String,
        startTime: Long,
        durationSeconds: Int,
        rawDurationSeconds: Int = durationSeconds,
        pagesTurned: Int = 0,
        wordsRead: Int = 0,
        isTts: Boolean = false
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("startTime", startTime)
        put("durationSeconds", durationSeconds)
        put("rawDurationSeconds", rawDurationSeconds)
        put("pagesTurned", pagesTurned)
        put("wordsRead", wordsRead)
        put("isTts", isTts)
    }
}
