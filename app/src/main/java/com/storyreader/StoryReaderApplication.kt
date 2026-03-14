package com.storyreader

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.storyreader.data.catalog.OpdsCatalogRepository
import com.storyreader.data.catalog.OpdsCredentialsManager
import com.storyreader.data.db.AppDatabase
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
import com.storyreader.data.sync.SyncCredentialsManager
import com.storyreader.data.sync.SyncManager
import com.storyreader.data.sync.SyncScheduler
import com.storyreader.data.sync.SyncSettingsStore
import com.storyreader.data.sync.SyncPayloadStore
import com.storyreader.data.sync.WebDavSyncRepository
import com.storyreader.reader.epub.EpubRepository
import kotlinx.coroutines.flow.MutableStateFlow

class StoryReaderApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val epubRepository: EpubRepository by lazy { EpubRepository(this) }

    val bookRepository: BookRepository by lazy {
        BookRepositoryImpl(this, database.bookDao(), epubRepository)
    }

    val readingRepository: ReadingRepository by lazy {
        ReadingRepositoryImpl(database.readingPositionDao(), database.readingSessionDao())
    }

    val credentialsManager: SyncCredentialsManager by lazy {
        SyncCredentialsManager.create(this)
    }

    val googleDriveCredentialsManager: GoogleDriveCredentialsManager by lazy {
        GoogleDriveCredentialsManager.create(this)
    }

    val syncSettingsStore: SyncSettingsStore by lazy {
        SyncSettingsStore.create(this)
    }

    val opdsCredentialsManager: OpdsCredentialsManager by lazy {
        OpdsCredentialsManager.create(this)
    }

    val googleDriveAuthManager: GoogleDriveAuthManager by lazy {
        GoogleDriveAuthManager(this, googleDriveCredentialsManager)
    }

    val webDavSyncRepository: WebDavSyncRepository by lazy {
        WebDavSyncRepository(credentialsManager, database.readingPositionDao(), database.readingSessionDao(), database.bookDao())
    }

    val googleDriveApi: GoogleDriveApi by lazy {
        GoogleDriveApi()
    }

    val opdsCatalogRepository: OpdsCatalogRepository by lazy {
        OpdsCatalogRepository()
    }

    val googleDriveSyncRepository: GoogleDriveSyncRepository by lazy {
        GoogleDriveSyncRepository(
            authManager = googleDriveAuthManager,
            payloadStore = SyncPayloadStore(
                positionDao = database.readingPositionDao(),
                sessionDao = database.readingSessionDao(),
                bookDao = database.bookDao()
            ),
            googleDriveApi = googleDriveApi
        )
    }

    val syncManager: SyncManager by lazy {
        SyncManager(
            listOf(
                NextcloudSyncProvider(syncSettingsStore, credentialsManager, webDavSyncRepository),
                GoogleDriveSyncProvider(syncSettingsStore, googleDriveCredentialsManager, googleDriveSyncRepository)
            )
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
