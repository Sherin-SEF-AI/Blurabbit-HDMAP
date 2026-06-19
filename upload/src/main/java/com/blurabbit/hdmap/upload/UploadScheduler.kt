package com.blurabbit.hdmap.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules background upload of a trip's artifacts (raw logs + HD-map exports) to the backend. */
interface UploadScheduler {
    fun enqueue(tripId: String)
}

@Singleton
class WorkManagerUploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : UploadScheduler {

    override fun enqueue(tripId: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInputData(Data.Builder().putString(UploadWorker.KEY_TRIP_ID, tripId).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("upload_$tripId", ExistingWorkPolicy.REPLACE, request)
    }
}
