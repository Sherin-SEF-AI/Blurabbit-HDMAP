package com.blurabbit.hdmap.upload

/** Supplies the serialized HD-map JSON for a trip, so the uploader stays decoupled from storage. */
fun interface TripMapJsonProvider {
    suspend fun mapJson(tripId: String): String?
}
