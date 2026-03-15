package com.storyreader.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyreader.data.db.AppDatabase
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingPositionEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import kotlinx.coroutines.test.runTest
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

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        store = SyncPayloadStore(
            positionDao = db.readingPositionDao(),
            sessionDao = db.readingSessionDao(),
            bookDao = db.bookDao()
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertBook(bookId: String) {
        db.bookDao().insert(BookEntity(bookId = bookId, title = "Test Book", author = "Author"))
    }

    // ── buildLatestJson ──────────────────────────────────────────────────────

    @Test
    fun `buildLatestJson returns empty arrays when no data`() = runTest {
        val json = store.buildLatestJson()
        assertEquals(0, json.getJSONArray("positions").length())
        assertEquals(0, json.getJSONArray("sessions").length())
    }

    @Test
    fun `buildLatestJson includes only latest position per book`() = runTest {
        insertBook("book1")
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(bookId = "book1", locatorJson = "{\"page\":1}", timestamp = 1000L)
        )
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(bookId = "book1", locatorJson = "{\"page\":5}", timestamp = 2000L)
        )

        val json = store.buildLatestJson()
        val positions = json.getJSONArray("positions")
        assertEquals(1, positions.length())
        assertEquals("book1", positions.getJSONObject(0).getString("bookId"))
        assertEquals("{\"page\":5}", positions.getJSONObject(0).getString("locatorJson"))
    }

    @Test
    fun `buildLatestJson includes latest position for each book independently`() = runTest {
        insertBook("book1")
        insertBook("book2")
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(bookId = "book1", locatorJson = "{\"page\":3}", timestamp = 1000L)
        )
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(bookId = "book2", locatorJson = "{\"page\":7}", timestamp = 1500L)
        )

        val json = store.buildLatestJson()
        val positions = json.getJSONArray("positions")
        assertEquals(2, positions.length())
    }

    @Test
    fun `buildLatestJson serializes position fields correctly`() = runTest {
        insertBook("book1")
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(bookId = "book1", locatorJson = "{\"href\":\"ch1.html\"}", timestamp = 9999L)
        )

        val json = store.buildLatestJson()
        val position = json.getJSONArray("positions").getJSONObject(0)
        assertEquals("book1", position.getString("bookId"))
        assertEquals("{\"href\":\"ch1.html\"}", position.getString("locatorJson"))
        assertEquals(9999L, position.getLong("timestamp"))
    }

    @Test
    fun `buildLatestJson includes all sessions`() = runTest {
        insertBook("book1")
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "book1", startTime = 1000L, durationSeconds = 100))
        db.readingSessionDao().insert(ReadingSessionEntity(bookId = "book1", startTime = 2000L, durationSeconds = 200))

        val json = store.buildLatestJson()
        assertEquals(2, json.getJSONArray("sessions").length())
    }

    @Test
    fun `buildLatestJson serializes session fields correctly`() = runTest {
        insertBook("book1")
        db.readingSessionDao().insert(ReadingSessionEntity(
            bookId = "book1",
            startTime = 5000L,
            durationSeconds = 300,
            rawDurationSeconds = 360,
            pagesTurned = 15,
            wordsRead = 1000,
            isTts = true
        ))

        val json = store.buildLatestJson()
        val session = json.getJSONArray("sessions").getJSONObject(0)
        assertEquals("book1", session.getString("bookId"))
        assertEquals(5000L, session.getLong("startTime"))
        assertEquals(300, session.getInt("durationSeconds"))
        assertEquals(360, session.getInt("rawDurationSeconds"))
        assertEquals(15, session.getInt("pagesTurned"))
        assertEquals(1000, session.getInt("wordsRead"))
        assertTrue(session.getBoolean("isTts"))
    }

    // ── mergeRemoteData – positions ──────────────────────────────────────────

    @Test
    fun `mergeRemoteData imports newer remote position`() = runTest {
        insertBook("book1")
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(bookId = "book1", locatorJson = "{\"page\":1}", timestamp = 1000L)
        )

        val remoteJson = buildRemoteJson(
            positions = """[{"bookId":"book1","locatorJson":"{\"page\":10}","timestamp":5000}]""",
            sessions = "[]"
        )

        store.mergeRemoteData(remoteJson)

        val latest = db.readingPositionDao().getLatestPositionOnce("book1")
        assertEquals("{\"page\":10}", latest?.locatorJson)
        assertEquals(5000L, latest?.timestamp)
    }

    @Test
    fun `mergeRemoteData does not overwrite newer local position with older remote`() = runTest {
        insertBook("book1")
        db.readingPositionDao().insertPosition(
            ReadingPositionEntity(bookId = "book1", locatorJson = "{\"page\":20}", timestamp = 9000L)
        )

        val remoteJson = buildRemoteJson(
            positions = """[{"bookId":"book1","locatorJson":"{\"page\":5}","timestamp":1000}]""",
            sessions = "[]"
        )

        store.mergeRemoteData(remoteJson)

        val latest = db.readingPositionDao().getLatestPositionOnce("book1")
        assertEquals("{\"page\":20}", latest?.locatorJson)
        assertEquals(9000L, latest?.timestamp)
    }

    @Test
    fun `mergeRemoteData skips position for unknown book`() = runTest {
        val remoteJson = buildRemoteJson(
            positions = """[{"bookId":"ghost-book","locatorJson":"{}","timestamp":5000}]""",
            sessions = "[]"
        )

        store.mergeRemoteData(remoteJson)

        val positions = db.readingPositionDao().getLatestPositionPerBook()
        assertEquals(0, positions.size)
    }

    @Test
    fun `mergeRemoteData imports position when no local position exists`() = runTest {
        insertBook("book1")

        val remoteJson = buildRemoteJson(
            positions = """[{"bookId":"book1","locatorJson":"{\"page\":3}","timestamp":3000}]""",
            sessions = "[]"
        )

        store.mergeRemoteData(remoteJson)

        val latest = db.readingPositionDao().getLatestPositionOnce("book1")
        assertNotNull(latest)
        assertEquals("{\"page\":3}", latest?.locatorJson)
    }

    // ── mergeRemoteData – sessions ───────────────────────────────────────────

    @Test
    fun `mergeRemoteData imports new remote session`() = runTest {
        insertBook("book1")

        val remoteJson = buildRemoteJson(
            positions = "[]",
            sessions = """[{"bookId":"book1","startTime":3000,"durationSeconds":120,"rawDurationSeconds":150,"pagesTurned":5,"wordsRead":400,"isTts":false}]"""
        )

        store.mergeRemoteData(remoteJson)

        val session = db.readingSessionDao().findByBookIdAndStartTime("book1", 3000L)
        assertNotNull(session)
        assertEquals(120, session?.durationSeconds)
        assertEquals(5, session?.pagesTurned)
        assertEquals(400, session?.wordsRead)
    }

    @Test
    fun `mergeRemoteData skips duplicate remote session same bookId and startTime`() = runTest {
        insertBook("book1")
        db.readingSessionDao().insert(
            ReadingSessionEntity(bookId = "book1", startTime = 3000L, durationSeconds = 60)
        )

        val remoteJson = buildRemoteJson(
            positions = "[]",
            sessions = """[{"bookId":"book1","startTime":3000,"durationSeconds":999,"rawDurationSeconds":0,"pagesTurned":0,"wordsRead":0,"isTts":false}]"""
        )

        store.mergeRemoteData(remoteJson)

        val session = db.readingSessionDao().findByBookIdAndStartTime("book1", 3000L)
        assertEquals("Local session should not be overwritten", 60, session?.durationSeconds)
    }

    @Test
    fun `mergeRemoteData skips session for unknown book`() = runTest {
        val remoteJson = buildRemoteJson(
            positions = "[]",
            sessions = """[{"bookId":"ghost-book","startTime":1000,"durationSeconds":100,"rawDurationSeconds":0,"pagesTurned":0,"wordsRead":0,"isTts":false}]"""
        )

        store.mergeRemoteData(remoteJson)

        assertEquals(0, db.readingSessionDao().getAllSessionsOnce().size)
    }

    @Test
    fun `mergeRemoteData handles missing optional session fields with defaults`() = runTest {
        insertBook("book1")

        val remoteJson = buildRemoteJson(
            positions = "[]",
            sessions = """[{"bookId":"book1","startTime":7000}]"""
        )

        store.mergeRemoteData(remoteJson)

        val session = db.readingSessionDao().findByBookIdAndStartTime("book1", 7000L)
        assertNotNull(session)
        assertEquals(0, session?.durationSeconds)
        assertEquals(0, session?.wordsRead)
    }

    @Test
    fun `mergeRemoteData processes both positions and sessions in one call`() = runTest {
        insertBook("book1")

        val remoteJson = buildRemoteJson(
            positions = """[{"bookId":"book1","locatorJson":"{\"page\":9}","timestamp":8000}]""",
            sessions = """[{"bookId":"book1","startTime":9000,"durationSeconds":60,"rawDurationSeconds":60,"pagesTurned":2,"wordsRead":100,"isTts":false}]"""
        )

        store.mergeRemoteData(remoteJson)

        val position = db.readingPositionDao().getLatestPositionOnce("book1")
        assertNotNull(position)
        assertEquals("{\"page\":9}", position?.locatorJson)

        val session = db.readingSessionDao().findByBookIdAndStartTime("book1", 9000L)
        assertNotNull(session)
    }

    private fun buildRemoteJson(positions: String, sessions: String): JSONObject =
        JSONObject("""{"positions":$positions,"sessions":$sessions}""")
}
