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
        if (!app.syncManager.hasEnabledConfiguredProviders()) {
            return Result.failure()
        }

        return app.syncManager.syncEnabledProviders().fold(
            onSuccess = { Result.success() },
            onFailure = {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        )
    }
}
