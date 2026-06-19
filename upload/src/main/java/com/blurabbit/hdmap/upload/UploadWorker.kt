package com.blurabbit.hdmap.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Drains a trip's artifacts to the backend ingestion endpoint. When no endpoint is configured
 * (the default in this build) it succeeds as a no-op so the queue mechanics are exercised end-to-end
 * without a server. Wiring a real [HdMapUploader] turns this into live S3 / API uploads with
 * WorkManager's exponential backoff and network constraints already in place.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploader: HdMapUploader,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tripId = inputData.getString(KEY_TRIP_ID) ?: return Result.failure()
        return when (uploader.upload(tripId)) {
            UploadOutcome.SUCCESS -> Result.success()
            UploadOutcome.RETRY -> Result.retry()
            UploadOutcome.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val KEY_TRIP_ID = "trip_id"
    }
}
