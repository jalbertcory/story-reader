package com.storyreader.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.storyreader.StoryReaderApplication

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as StoryReaderApplication
        val credentialsManager = SyncCredentialsManager.create(applicationContext)

        if (!credentialsManager.hasCredentials) {
            return Result.failure()
        }

        val syncRepo = WebDavSyncRepository(
            credentialsManager = credentialsManager,
            positionDao = app.database.readingPositionDao(),
            sessionDao = app.database.readingSessionDao()
        )

        return syncRepo.uploadSyncData().fold(
            onSuccess = { Result.success() },
            onFailure = { e ->
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        )
    }
}
