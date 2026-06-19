package com.blurabbit.hdmap.capture

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves on-disk locations for trip artifacts (raw logs + sidecar media + exports). */
@Singleton
class TripStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File get() = File(context.filesDir, "trips").apply { mkdirs() }

    fun tripDir(tripId: String): File = File(root, tripId).apply { mkdirs() }

    fun exportsDir(tripId: String): File = File(tripDir(tripId), "exports").apply { mkdirs() }

    fun videoFile(tripId: String): File = File(tripDir(tripId), "front.mp4")
}
