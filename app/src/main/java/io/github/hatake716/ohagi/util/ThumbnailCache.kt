package io.github.hatake716.ohagi.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToInt

/** The same document at a larger display size needs its own provider thumbnail. */
internal data class ThumbnailKey(val uri: String, val sizePx: Int)

/** Limit oversized provider results without upscaling or changing their aspect ratio. */
internal fun thumbnailDimensions(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxEdge > 0)
    val largest = maxOf(width, height)
    if (largest <= maxEdge) return width to height
    val ratio = maxEdge.toDouble() / largest
    return (width * ratio).roundToInt().coerceAtLeast(1) to
        (height * ratio).roundToInt().coerceAtLeast(1)
}

/**
 * Byte-bounded thumbnail LRU with shared, cancellable provider requests.
 * Failed requests are never cached, so a remounted document provider can be retried.
 */
internal class ThumbnailCache<K : Any, V : Any>(
    private val maxBytes: Long,
    maxParallelLoads: Int,
    private val sizeOf: (V) -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private val cached = LinkedHashMap<K, V>(16, 0.75f, true)
    private val pending = mutableMapOf<K, Request<V>>()
    private val loadSlots = Semaphore(maxParallelLoads)
    private var bytes = 0L
    private var generation = 0L

    init {
        require(maxBytes > 0)
    }

    fun get(key: K): V? = synchronized(lock) { cached[key] }

    suspend fun getOrLoad(key: K, load: suspend () -> V?): V? {
        val request = synchronized(lock) {
            cached[key]?.let { return it }
            pending[key]?.also { it.users++ } ?: Request<V>().also { created ->
                val startedGeneration = generation
                created.task = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        loadSlots.withPermit {
                            currentCoroutineContext().ensureActive()
                            load()
                        }?.also { value ->
                            currentCoroutineContext().ensureActive()
                            synchronized(lock) {
                                // A memory trim must not immediately be undone by an older request.
                                if (generation == startedGeneration) put(key, value)
                            }
                        }
                    } finally {
                        synchronized(lock) {
                            if (pending[key] === created) pending.remove(key)
                        }
                    }
                }
                pending[key] = created
            }
        }
        return try {
            request.task.await()
        } finally {
            val cancelUnused = synchronized(lock) {
                request.users--
                if (request.users == 0 && !request.task.isCompleted) {
                    if (pending[key] === request) pending.remove(key)
                    true
                } else false
            }
            if (cancelUnused) request.task.cancel()
        }
    }

    /** Drop cache references only; an image still drawn by Compose must not be recycled. */
    fun trimTo(targetBytes: Long) = synchronized(lock) {
        generation++
        evictTo(targetBytes.coerceIn(0L, maxBytes))
    }

    internal val cachedBytes: Long
        get() = synchronized(lock) { bytes }

    private fun put(key: K, value: V) {
        val weight = sizeOf(value).coerceAtLeast(1L)
        // An unusual provider may return a bitmap larger than the entire cache.
        // Display it at its original quality without evicting all other thumbnails.
        if (weight > maxBytes) return
        cached.put(key, value)?.let { bytes -= sizeOf(it).coerceAtLeast(1L) }
        bytes += weight
        evictTo(maxBytes)
    }

    private fun evictTo(targetBytes: Long) {
        val iterator = cached.entries.iterator()
        while (bytes > targetBytes && iterator.hasNext()) {
            bytes -= sizeOf(iterator.next().value).coerceAtLeast(1L)
            iterator.remove()
        }
    }

    private class Request<V> {
        lateinit var task: Deferred<V?>
        var users = 1
    }
}
