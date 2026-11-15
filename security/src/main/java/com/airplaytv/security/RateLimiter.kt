package com.airplaytv.security

/**
 * Per-address connection rate limiting with a temporary block.
 * A single AirPlay session opens a handful of TCP connections; a host opening dozens
 * per window is scanning or flooding and gets blocked for [blockMillis].
 */
class RateLimiter(
    private val maxPerWindow: Int = 24,
    private val windowMillis: Long = 10_000,
    private val blockMillis: Long = 60_000,
    private val maxTrackedHosts: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Bucket(var windowStart: Long, var count: Int, var blockedUntil: Long)

    private val buckets = LinkedHashMap<String, Bucket>(16, 0.75f, true)

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val b = buckets.getOrPut(key) { Bucket(now, 0, 0) }
        if (buckets.size > maxTrackedHosts) {
            // drop the least recently used entry to bound memory
            buckets.keys.firstOrNull()?.let { if (it != key) buckets.remove(it) }
        }
        if (now < b.blockedUntil) return false
        if (now - b.windowStart >= windowMillis) {
            b.windowStart = now
            b.count = 0
        }
        b.count++
        if (b.count > maxPerWindow) {
            b.blockedUntil = now + blockMillis
            return false
        }
        return true
    }

    @Synchronized
    fun block(key: String, millis: Long = blockMillis) {
        val now = clock()
        buckets.getOrPut(key) { Bucket(now, 0, 0) }.blockedUntil = now + millis
    }

    @Synchronized
    fun isBlocked(key: String): Boolean = (buckets[key]?.blockedUntil ?: 0) > clock()

    @Synchronized
    fun clear() = buckets.clear()
}
