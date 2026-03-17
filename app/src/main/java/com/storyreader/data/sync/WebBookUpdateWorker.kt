package com.storyreader.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.storyreader.StoryReaderApplication

class WebBookUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as StoryReaderApplication
        return app.storyManagerRepository.checkForUpdates().fold(
            onSuccess = { Result.success() },
            onFailure = {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        )
    }
}
