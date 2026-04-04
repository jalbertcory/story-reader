package com.storyreader

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.storyreader.data.catalog.OpdsCatalogRepository
import com.storyreader.data.catalog.OpdsCredentialsManager
import com.storyreader.data.catalog.StoryManagerApiClient
import com.storyreader.data.repository.StoryManagerRepository
import com.storyreader.data.sync.WebBookUpdateScheduler
import com.storyreader.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.BookRepositoryImpl
import com.storyreader.data.repository.ReadingRepository
import com.storyreader.data.repository.ReadingRepositoryImpl
import com.storyreader.data.sync.GoogleDriveApi
import com.storyreader.data.sync.GoogleDriveAuthManager
import com.storyreader.data.sync.GoogleDriveCredentialsManager
import com.storyreader.data.sync.GoogleDriveSyncProvider
import com.storyreader.data.sync.GoogleDriveSyncRepository
import com.storyreader.data.sync.NextcloudSyncProvider
import com.storyreader.data.sync.RemoteBookRecoveryManager
import com.storyreader.data.sync.SyncCredentialsManager
import com.storyreader.data.sync.SyncManager
import com.storyreader.data.sync.SyncScheduler
import com.storyreader.data.sync.SyncSettingsStore
import com.storyreader.data.sync.SyncPayloadStore
import com.storyreader.data.sync.WebDavSyncRepository
import com.storyreader.reader.epub.EpubRepository
import kotlinx.coroutines.flow.MutableStateFlow

open class StoryReaderApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setInitializationExceptionHandler { e ->
                Log.w("StoryReaderApp", "WorkManager initialization failed", e)
            }
            .build()

    open val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    open val epubRepository: EpubRepository by lazy { EpubRepository(this) }

    open val bookRepository: BookRepository by lazy {
        BookRepositoryImpl(this, database.bookDao(), epubRepository)
    }

    open val readingRepository: ReadingRepository by lazy {
        ReadingRepositoryImpl(database.readingPositionDao(), database.readingSessionDao())
    }

    open val credentialsManager: SyncCredentialsManager by lazy {
        SyncCredentialsManager.create(this)
    }

    open val googleDriveCredentialsManager: GoogleDriveCredentialsManager by lazy {
        GoogleDriveCredentialsManager.create(this)
    }

    open val syncSettingsStore: SyncSettingsStore by lazy {
        SyncSettingsStore.create(this)
    }

    open val opdsCredentialsManager: OpdsCredentialsManager by lazy {
        OpdsCredentialsManager.create(this)
    }

    val googleDriveAuthManager: GoogleDriveAuthManager by lazy {
        GoogleDriveAuthManager(this, googleDriveCredentialsManager)
    }

    val googleDriveApi: GoogleDriveApi by lazy {
        GoogleDriveApi()
    }

    val opdsCatalogRepository: OpdsCatalogRepository by lazy {
        OpdsCatalogRepository()
    }

    val storyManagerApiClient: StoryManagerApiClient by lazy {
        StoryManagerApiClient(opdsCredentialsManager)
    }

    val storyManagerRepository: StoryManagerRepository by lazy {
        StoryManagerRepository(
            apiClient = storyManagerApiClient,
            bookDao = database.bookDao(),
            epubRepository = epubRepository,
            downloadDir = java.io.File(filesDir, "story_manager_downloads"),
            coversDir = java.io.File(filesDir, "covers")
        )
    }

    val remoteBookRecoveryManager: RemoteBookRecoveryManager by lazy {
        RemoteBookRecoveryManager(
            context = this,
            bookDao = database.bookDao(),
            bookRepository = bookRepository,
            syncCredentialsManager = credentialsManager,
            googleDriveAuthManager = googleDriveAuthManager,
            googleDriveApi = googleDriveApi,
            opdsCredentialsManager = opdsCredentialsManager
        )
    }

    val webDavSyncRepository: WebDavSyncRepository by lazy {
        WebDavSyncRepository(
            credentialsManager = credentialsManager,
            positionDao = database.readingPositionDao(),
            sessionDao = database.readingSessionDao(),
            bookDao = database.bookDao(),
            recoveryManager = remoteBookRecoveryManager
        )
    }

    val googleDriveSyncRepository: GoogleDriveSyncRepository by lazy {
        GoogleDriveSyncRepository(
            authManager = googleDriveAuthManager,
            payloadStore = SyncPayloadStore(
                positionDao = database.readingPositionDao(),
                sessionDao = database.readingSessionDao(),
                bookDao = database.bookDao()
            ),
            googleDriveApi = googleDriveApi,
            recoveryManager = remoteBookRecoveryManager
        )
    }

    open val syncManager: SyncManager by lazy {
        SyncManager(
            providers = listOf(
                NextcloudSyncProvider(syncSettingsStore, credentialsManager, webDavSyncRepository),
                GoogleDriveSyncProvider(syncSettingsStore, googleDriveCredentialsManager, googleDriveSyncRepository)
            ),
            syncSettingsStore = syncSettingsStore,
            countLocalBooks = { database.bookDao().getAllIncludingHiddenOnce().size },
            onBooksAdded = { _ ->
                if (opdsCredentialsManager.isStoryManagerBackend) {
                    WebBookUpdateScheduler.scheduleImmediateSync(this)
                }
            }
        )
    }

    // Global flag that drives dark app-chrome (top bar, nav bar, status bar tint) when
    // the reader is open with a dark or night theme. Updated by ReaderViewModel and
    // AppSettingsViewModel whenever the theme preference changes.
    val isDarkReadingTheme: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        // Restore dark-chrome flag from saved reading preferences so the app starts with
        // the correct theme even before the reader is opened.
        val savedTheme = getSharedPreferences("reader_preferences", MODE_PRIVATE)
            .getString("theme", null)
        val isNight = getSharedPreferences("reader_preferences", MODE_PRIVATE)
            .getBoolean("is_night_theme", false)
        isDarkReadingTheme.value = savedTheme == "DARK" || isNight
        // Note: night theme (custom colors) is detected differently — ReaderViewModel updates this flag

        if (syncManager.hasEnabledConfiguredProviders()) {
            SyncScheduler.schedulePeriodicSync(this)
        }

        if (opdsCredentialsManager.isStoryManagerBackend) {
            WebBookUpdateScheduler.schedulePeriodicSync(this)
        }

        // Clean up orphaned 0-duration reading sessions (from race conditions where
        // startSession inserts a row but finalize never runs).
        CoroutineScope(Dispatchers.IO).launch {
            val cutoff = System.currentTimeMillis() - 60_000L // older than 1 minute
            database.readingSessionDao().deleteOrphanedSessions(cutoff)
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (syncManager.hasEnabledConfiguredProviders()) {
                    SyncScheduler.scheduleImmediateSync(this@StoryReaderApplication)
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                if (syncManager.hasEnabledConfiguredProviders()) {
                    SyncScheduler.scheduleImmediateSync(this@StoryReaderApplication)
                }
            }
        })
    }
}
