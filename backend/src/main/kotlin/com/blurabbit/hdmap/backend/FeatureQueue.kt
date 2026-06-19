package com.blurabbit.hdmap.backend

import com.blurabbit.hdmap.domain.hdmap.HdMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Decouples ingestion from fusion/persistence. Ingest enqueues; a worker drains into the
 * [FeatureStore]. The in-memory implementation is the prototype default; in production this is a
 * Kafka / Cloud Pub/Sub topic so fusion workers scale independently of the ingestion gateway
 * (see docs/03-backend-and-cloud.md).
 */
interface FeatureQueue {
    fun enqueue(map: HdMap)
    fun poll(): HdMap?
    fun size(): Int
}

class InMemoryFeatureQueue : FeatureQueue {
    private val q = ConcurrentLinkedQueue<HdMap>()
    override fun enqueue(map: HdMap) { q.add(map) }
    override fun poll(): HdMap? = q.poll()
    override fun size(): Int = q.size
}
