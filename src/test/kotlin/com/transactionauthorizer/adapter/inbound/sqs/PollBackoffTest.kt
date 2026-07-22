package com.transactionauthorizer.adapter.inbound.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Random
import java.util.random.RandomGenerator

class PollBackoffTest {
    private val base = Duration.ofMillis(100)
    private val cap = Duration.ofSeconds(10)

    @Test
    fun `the first attempt waits within the base ceiling`() {
        val backoff = PollBackoff(base, cap, Random(42))

        repeat(1_000) {
            assertThat(backoff.durationFor(0).toMillis()).isBetween(0L, base.toMillis())
        }
    }

    @Test
    fun `the ceiling doubles per attempt until it reaches the cap`() {
        // Pinned to the top of its range, durationFor returns exactly the ceiling, which
        // makes the doubling and the cap observable.
        val backoff = PollBackoff(base, cap, alwaysMax())

        assertThat(backoff.durationFor(0).toMillis()).isEqualTo(100)
        assertThat(backoff.durationFor(1).toMillis()).isEqualTo(200)
        assertThat(backoff.durationFor(2).toMillis()).isEqualTo(400)
        // 100 * 2^7 = 12800, clamped to the 10s cap.
        assertThat(backoff.durationFor(7).toMillis()).isEqualTo(10_000)
        // A high attempt count must not overflow the shift or the multiplication.
        assertThat(backoff.durationFor(50).toMillis()).isEqualTo(10_000)
    }

    @Test
    fun `no attempt ever exceeds the cap or goes negative`() {
        val backoff = PollBackoff(base, cap, Random(7))

        (0..40).forEach { attempt ->
            assertThat(backoff.durationFor(attempt).toMillis()).isBetween(0L, cap.toMillis())
        }
    }

    private fun alwaysMax(): RandomGenerator =
        object : RandomGenerator {
            override fun nextLong(): Long = Long.MAX_VALUE

            override fun nextLong(bound: Long): Long = bound - 1
        }
}
