package com.transactionauthorizer.adapter.inbound.sqs

import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.random.RandomGenerator
import kotlin.math.min

// Full-jitter backoff: sleep = rand(0, min(cap, base * 2^attempt)). Full jitter, not the
// equal-jitter or decorrelated variants, so pollers that failed together do not wake
// together and stampede a dependency that is just coming back.
//
// This backoff does NOT reset the SQS receive count. maxReceiveCount (set in the queue
// topology) is the real budget that absorbs a bounded outage without dead-lettering valid
// messages; the in-app backoff only spaces out the attempts that spend it.
class PollBackoff(
    private val base: Duration,
    private val cap: Duration,
    private val random: RandomGenerator = ThreadLocalRandom.current(),
) {
    fun durationFor(attempt: Int): Duration {
        val ceiling = min(cap.toMillis(), exponential(attempt))
        return Duration.ofMillis(random.nextLong(ceiling + 1))
    }

    // base * 2^attempt, clamped so a high attempt count cannot overflow the shift or the
    // multiplication; the cap makes anything past that irrelevant anyway.
    private fun exponential(attempt: Int): Long {
        val factor = 1L shl attempt.coerceIn(0, MAX_SHIFT)
        val baseMillis = base.toMillis()
        return if (baseMillis > Long.MAX_VALUE / factor) Long.MAX_VALUE else baseMillis * factor
    }

    private companion object {
        const val MAX_SHIFT = 30
    }
}
