package com.airplaytv.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RateLimiterTest {
    private var now = 0L
    private val limiter = RateLimiter(maxPerWindow = 2, windowMillis = 1_000, blockMillis = 5_000, maxTrackedHosts = 3) { now }

    @Test fun `window resets after it elapses`() {
        assertThat(limiter.tryAcquire("a")).isTrue()
        assertThat(limiter.tryAcquire("a")).isTrue()
        now = 1_000
        assertThat(limiter.tryAcquire("a")).isTrue()
    }

    @Test fun `exceeding the window blocks for the block period`() {
        repeat(2) { limiter.tryAcquire("a") }
        assertThat(limiter.tryAcquire("a")).isFalse()
        now = 4_999
        assertThat(limiter.tryAcquire("a")).isFalse()
        now = 5_001
        assertThat(limiter.tryAcquire("a")).isTrue()
    }

    @Test fun `tracked host table stays bounded`() {
        repeat(100) { limiter.tryAcquire("h$it") }
        // still functional for new keys
        assertThat(limiter.tryAcquire("new")).isTrue()
    }
}
