package io.github.hatake716.ohagi.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThumbnailCacheTest {
    @Test
    fun byteBudgetEvictsLeastRecentlyUsedAndDoesNotCacheOversizedResults() = withCache {
        getOrLoad("a") { "123456" }
        getOrLoad("b") { "1234" }
        get("a")
        getOrLoad("c") { "5678" }
        assertEquals("123456", get("a"))
        assertNull(get("b"))
        assertEquals("5678", get("c"))
        assertEquals(10L, cachedBytes)
        assertEquals("12345678901", getOrLoad("large") { "12345678901" })
        assertNull(get("large"))
        assertEquals(10L, cachedBytes)
    }

    @Test
    fun concurrentUsersShareFailuresButLaterVisitsRetry() = withCache {
        val result = CompletableDeferred<String?>()
        var reads = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            getOrLoad("uri") { reads++; result.await() }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            getOrLoad("uri") { error("Duplicate provider request") }
        }
        result.complete(null)
        assertNull(first.await())
        assertNull(second.await())
        assertEquals(1, reads)
        assertEquals("restored", getOrLoad("uri") { reads++; "restored" })
        assertEquals(2, reads)
    }

    @Test
    fun cancellingOneUserKeepsTheSharedRequestForAnother() = withCache {
        val result = CompletableDeferred<String?>()
        var providerFinished = false
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            getOrLoad("uri") {
                try { result.await() } finally { providerFinished = true }
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            getOrLoad("uri") { error("Duplicate provider request") }
        }
        first.cancelAndJoin()
        assertFalse(providerFinished)
        result.complete("thumbnail")
        assertEquals("thumbnail", second.await())
        assertTrue(providerFinished)
    }

    @Test
    fun cancellingLastUserStopsProviderAndNextVisitCanRetry() = withCache {
        val cancelled = CompletableDeferred<Unit>()
        val user = async(start = CoroutineStart.UNDISPATCHED) {
            getOrLoad("uri") {
                try { CompletableDeferred<String>().await() } finally { cancelled.complete(Unit) }
            }
        }
        user.cancelAndJoin()
        cancelled.await()
        assertNull(get("uri"))
        assertEquals("retry", getOrLoad("uri") { "retry" })
    }

    @Test
    fun cancellingWhileQueuedDoesNotStartAnUnusedProviderRead() = withCache {
        val firstResult = CompletableDeferred<String?>()
        var queuedReadStarted = false
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            getOrLoad("first") { firstResult.await() }
        }
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            getOrLoad("queued") { queuedReadStarted = true; "unused" }
        }
        queued.cancelAndJoin()
        firstResult.complete("one")
        first.await()
        assertFalse(queuedReadStarted)
        assertEquals("retry", getOrLoad("queued") { "retry" })
    }

    @Test
    fun failedLoaderIsRemovedAndReleasesItsConcurrencySlot() = withCache {
        assertFailsWith<IllegalStateException> {
            getOrLoad("uri") { error("Provider failed") }
        }
        assertNull(get("uri"))
        assertEquals("retry", getOrLoad("uri") { "retry" })
    }

    @Test
    fun providerConcurrencyIsBoundedAndTrimCannotBeUndoneByAnOlderRead() = withCache {
        val firstResult = CompletableDeferred<String?>()
        val secondResult = CompletableDeferred<String?>()
        var secondStarted = false
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            getOrLoad("first") { firstResult.await() }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            getOrLoad("second") { secondStarted = true; secondResult.await() }
        }
        assertFalse(secondStarted)
        trimTo(0)
        firstResult.complete("one")
        assertEquals("one", first.await())
        assertTrue(secondStarted)
        secondResult.complete("two")
        assertEquals("two", second.await())
        assertEquals(0L, cachedBytes)
        assertEquals("fresh", getOrLoad("third") { "fresh" })
        assertEquals(5L, cachedBytes)
        trimTo(0)
        assertNull(get("third"))
    }

    @Test
    fun requestedSizeSeparatesCacheKeysAndOversizedImagesKeepTheirAspectRatio() {
        assertEquals(2, setOf(ThumbnailKey("uri", 96), ThumbnailKey("uri", 192)).size)
        assertEquals(192 to 108, thumbnailDimensions(1920, 1080, 192))
        assertEquals(96 to 192, thumbnailDimensions(1000, 2000, 192))
        assertEquals(80 to 50, thumbnailDimensions(80, 50, 192))
    }

    private fun withCache(block: suspend CacheTestScope.() -> Unit) = runBlocking {
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cache = ThumbnailCache<String, String>(
            maxBytes = 10L,
            maxParallelLoads = 1,
            sizeOf = { it.length.toLong() },
            scope = workers,
        )
        try {
            CacheTestScope(this, cache).block()
        } finally {
            workers.cancel()
        }
    }

    private class CacheTestScope(
        scope: CoroutineScope,
        private val cache: ThumbnailCache<String, String>,
    ) : CoroutineScope by scope {
        fun get(key: String) = cache.get(key)
        suspend fun getOrLoad(key: String, load: suspend () -> String?) = cache.getOrLoad(key, load)
        fun trimTo(bytes: Long) = cache.trimTo(bytes)
        val cachedBytes: Long get() = cache.cachedBytes
    }
}
