package com.blurabbit.hdmap.upload

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class UploadOutcome { SUCCESS, RETRY, FAILURE }

/** Optional backend configuration. With a null [endpoint] the uploader is a no-op (offline build). */
data class UploadConfig(
    val endpoint: String? = null,
    val bearerToken: String? = null,
)

/**
 * Pushes a trip's generated HD map to the backend ingestion API (`POST {endpoint}/v1/trips/{id}`),
 * reusing the same serialized [com.blurabbit.hdmap.domain.hdmap.HdMap] JSON the backend's fusion
 * engine consumes. With no endpoint configured it succeeds as a no-op so the queue mechanics run
 * offline; set [UploadConfig.endpoint] (e.g. via `adb reverse` to a local backend) to upload live.
 */
@Singleton
class HdMapUploader @Inject constructor(
    private val config: UploadConfig,
    private val mapJsonProvider: TripMapJsonProvider,
) {
    private val client = OkHttpClient()

    suspend fun upload(tripId: String): UploadOutcome = withContext(Dispatchers.IO) {
        val endpoint = config.endpoint ?: return@withContext UploadOutcome.SUCCESS // no-op when unconfigured
        val body = mapJsonProvider.mapJson(tripId) ?: return@withContext UploadOutcome.SUCCESS
        val request = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/trips/$tripId")
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply {
                config.bearerToken?.let { header("Authorization", "Bearer $it") }
                header("X-Blurabbit-Trip", tripId)
            }
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> UploadOutcome.SUCCESS
                    resp.code >= 500 -> UploadOutcome.RETRY
                    else -> UploadOutcome.FAILURE
                }
            }
        } catch (_: IOException) {
            UploadOutcome.RETRY
        }
    }
}
