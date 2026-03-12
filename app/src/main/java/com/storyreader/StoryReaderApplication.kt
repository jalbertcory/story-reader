package com.storyreader

import android.app.Application
import com.storyreader.data.db.AppDatabase
import com.storyreader.data.repository.BookRepository
import com.storyreader.data.repository.BookRepositoryImpl
import com.storyreader.data.repository.ReadingRepository
import com.storyreader.data.repository.ReadingRepositoryImpl
import com.storyreader.data.sync.SyncCredentialsManager
import com.storyreader.data.sync.SyncScheduler
import com.storyreader.reader.epub.EpubRepository

class StoryReaderApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val epubRepository: EpubRepository by lazy { EpubRepository(this) }

    val bookRepository: BookRepository by lazy {
        BookRepositoryImpl(database.bookDao(), epubRepository)
    }

    val readingRepository: ReadingRepository by lazy {
        ReadingRepositoryImpl(database.readingPositionDao(), database.readingSessionDao())
    }

    override fun onCreate() {
        super.onCreate()
        val credentialsManager = SyncCredentialsManager.create(this)
        if (credentialsManager.hasCredentials) {
            SyncScheduler.schedulePeriodicSync(this)
        }
    }
}
