package com.blurabbit.hdmap.domain.util

import java.util.UUID

/** Stable, prefixed unique identifiers for HD-map entities and trips. */
object Ids {
    fun trip(): String = "trip_${UUID.randomUUID()}"
    fun feature(prefix: String): String = "${prefix}_${UUID.randomUUID()}"
}
