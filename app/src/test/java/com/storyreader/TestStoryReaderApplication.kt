package com.storyreader

import android.net.Uri
import androidx.room.Room
import com.storyreader.data.catalog.OpdsCredentialsManager
import java.util.concurrent.Executors
import com.storyreader.data.db.AppDatabase
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.ReadingRepository
import com.storyreader.data.repository.ReadingRepositoryImpl
import com.storyreader.data.sync.SyncCredentialsManager
import com.storyreader.data.sync.SyncManager
import com.storyreader.data.sync.SyncSettingsStore
import kotlinx.coroutines.flow.Flow

/**
 * Test Application for Robolectric unit tests. Extends [StoryReaderApplication] so that
 * ViewModels can safely cast `application as StoryReaderApplication`. Provides:
 *  - In-memory Room database
 *  - Plain SharedPreferences (no Android Keystore / EncryptedSharedPreferences)
 *  - No WorkManager scheduling in onCreate
 */
class TestStoryReaderApplication : StoryReaderApplication() {

    override val database: AppDatabase by lazy {
        val executor = Executors.newSingleThreadExecutor()
        Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(executor)
            .setTransactionExecutor(executor)
            .build()
    }

    override val bookRepository: BookRepository by lazy {
        TestBookRepository(database.bookDao())
    }

    override val readingRepository: ReadingRepository by lazy {
        ReadingRepositoryImpl(database.readingPositionDao(), database.readingSessionDao())
    }

    override val credentialsManager: SyncCredentialsManager by lazy {
        SyncCredentialsManager(getSharedPreferences("test_sync_creds", MODE_PRIVATE))
    }

    override val opdsCredentialsManager: OpdsCredentialsManager by lazy {
        OpdsCredentialsManager(getSharedPreferences("test_opds_creds", MODE_PRIVATE))
    }

    override val syncSettingsStore: SyncSettingsStore by lazy {
        SyncSettingsStore.create(this)
    }

    override val syncManager: SyncManager by lazy {
        SyncManager(emptyList(), syncSettingsStore)
    }

    override fun onCreate() {
        // Skip super.onCreate() to avoid WorkManager scheduling and ProcessLifecycleOwner
        // which are unavailable in the Robolectric environment.
    }
}

/**
 * Minimal [BookRepository] backed by a real [BookDao] for DAO-level operations.
 * Stubs out [importFromUri] which requires EPUB I/O.
 */
private class TestBookRepository(
    private val dao: com.storyreader.data.db.dao.BookDao
) : BookRepository {
    override fun observeAll(): Flow<List<BookEntity>> = dao.getAll()
    override fun observeAllIncludingHidden(): Flow<List<BookEntity>> = dao.getAllIncludingHidden()
    override fun observeById(bookId: String): Flow<BookEntity?> = dao.getById(bookId)
    override suspend fun insert(book: BookEntity) = dao.insert(book)
    override suspend fun delete(book: BookEntity) = dao.delete(book)
    override suspend fun hideBook(bookId: String) = dao.setHidden(bookId, true)
    override suspend fun unhideBook(bookId: String) = dao.setHidden(bookId, false)
    override suspend fun updateProgression(bookId: String, progression: Float) =
        dao.updateProgression(bookId, progression)
    override suspend fun importFromUri(uri: Uri): Result<BookEntity> =
        Result.failure(UnsupportedOperationException("not supported in tests"))
    override suspend fun getWordCount(bookId: String): Int = dao.getWordCountById(bookId) ?: 0
    override suspend fun updateChapterPosition(bookId: String, title: String?, progression: Float?) =
        dao.updateChapterPosition(bookId, title, progression)
}
