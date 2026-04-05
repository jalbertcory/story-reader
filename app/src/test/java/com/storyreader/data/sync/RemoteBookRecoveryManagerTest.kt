package com.storyreader.data.sync

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyreader.data.catalog.OpdsCredentialsManager
import com.storyreader.data.db.AppDatabase
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteBookRecoveryManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var fakeBookRepository: FakeBookRepository
    private lateinit var manager: RemoteBookRecoveryManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeBookRepository = FakeBookRepository()
        manager = RemoteBookRecoveryManager(
            context = context,
            bookDao = db.bookDao(),
            bookRepository = fakeBookRepository,
            syncCredentialsManager = SyncCredentialsManager(
                context.getSharedPreferences("remote_recovery_sync_creds", Context.MODE_PRIVATE)
            ),
            googleDriveAuthManager = GoogleDriveAuthManager(
                context,
                GoogleDriveCredentialsManager(
                    context.getSharedPreferences("remote_recovery_gdrive_creds", Context.MODE_PRIVATE)
                )
            ),
            googleDriveApi = GoogleDriveApi(),
            opdsCredentialsManager = OpdsCredentialsManager(
                context.getSharedPreferences("remote_recovery_opds_creds", Context.MODE_PRIVATE)
            ),
            httpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `completed remote story manager books recover as stubs without downloading`() = runTest {
        val summary = manager.recoverMissingBooks(
            remoteJson(
                remoteBookJson(
                    syncId = "sync-complete",
                    title = "Finished Story",
                    author = "Writer",
                    sourceType = "web",
                    series = "Series",
                    seriesIndex = 3f,
                    sourceKind = SyncSourceKinds.STORY_MANAGER,
                    sourceUrl = "/reader/books/42/download",
                    originalFileName = "finished-story.epub",
                    serverBookId = 42,
                    isCompleted = true,
                    furthestProgress = 1.0
                )
            )
        )

        val recovered = db.bookDao().getAllIncludingHiddenOnce().single()
        assertEquals(1, summary.attempted)
        assertEquals(1, summary.imported)
        assertEquals(0, summary.failed)
        assertEquals("recoverable://sync-complete", recovered.bookId)
        assertEquals(1f, recovered.totalProgression, 0.001f)
        assertEquals(SyncSourceKinds.STORY_MANAGER, recovered.syncSourceKind)
        assertEquals("web", recovered.sourceType)
        assertEquals(42, recovered.serverBookId)
        assertEquals(0, fakeBookRepository.importAttempts)
    }

    @Test
    fun `incomplete remote story manager books still use download recovery path`() = runTest {
        val summary = manager.recoverMissingBooks(
            remoteJson(
                remoteBookJson(
                    syncId = "sync-incomplete",
                    title = "In Progress Story",
                    author = "Writer",
                    sourceType = "web",
                    sourceKind = SyncSourceKinds.STORY_MANAGER,
                    sourceUrl = "/reader/books/99/download",
                    originalFileName = "in-progress-story.epub",
                    serverBookId = 99,
                    isCompleted = false,
                    furthestProgress = 0.4
                )
            )
        )

        assertEquals(1, summary.attempted)
        assertEquals(0, summary.imported)
        assertEquals(1, summary.failed)
        assertTrue(db.bookDao().getAllIncludingHiddenOnce().isEmpty())
        assertEquals(0, fakeBookRepository.importAttempts)
    }

    private class FakeBookRepository : BookRepository {
        var importAttempts = 0

        override fun observeAll(): Flow<List<BookEntity>> = emptyFlow()
        override fun observeAllIncludingHidden(): Flow<List<BookEntity>> = emptyFlow()
        override fun observeById(bookId: String): Flow<BookEntity?> = emptyFlow()
        override suspend fun insert(book: BookEntity) = Unit
        override suspend fun delete(book: BookEntity) = Unit
        override suspend fun hideBook(bookId: String) = Unit
        override suspend fun unhideBook(bookId: String) = Unit
        override suspend fun updateProgression(bookId: String, progression: Float) = Unit
        override suspend fun importFromUri(uri: Uri, importMetadata: BookImportMetadata?): Result<BookEntity> {
            importAttempts++
            return Result.failure(UnsupportedOperationException("download path should not reach import in this test"))
        }

        override suspend fun getWordCount(bookId: String): Int = 0
        override suspend fun updateChapterPosition(bookId: String, title: String?, progression: Float?) = Unit
        override suspend fun resetBookProgress(bookId: String, restartAt: Long) = Unit
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
        sourceType: String,
        sourceKind: String,
        sourceUrl: String,
        originalFileName: String,
        serverBookId: Int,
        isCompleted: Boolean,
        furthestProgress: Double,
        series: String? = null,
        seriesIndex: Float? = null
    ): JSONObject = JSONObject().apply {
        put("syncId", syncId)
        put("title", title)
        put("author", author)
        put("sourceType", sourceType)
        if (series != null) put("series", series)
        if (seriesIndex != null) put("seriesIndex", seriesIndex.toDouble())
        put(
            "source",
            JSONObject().apply {
                put("kind", sourceKind)
                put("url", sourceUrl)
                put("originalFileName", originalFileName)
                put("serverBookId", serverBookId)
            }
        )
        put(
            "progress",
            JSONObject().apply {
                put("isCompleted", isCompleted)
                put("furthestProgress", furthestProgress)
            }
        )
    }
}
