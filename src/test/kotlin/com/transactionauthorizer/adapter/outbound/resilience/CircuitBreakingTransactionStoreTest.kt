package com.transactionauthorizer.adapter.outbound.resilience

import com.transactionauthorizer.application.port.AuthorizationCommand
import com.transactionauthorizer.application.port.AuthorizationResult
import com.transactionauthorizer.application.port.AuthorizationUnavailableException
import com.transactionauthorizer.application.port.TransactionStore
import com.transactionauthorizer.domain.Money
import com.transactionauthorizer.domain.TransactionType
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.CannotCreateTransactionException
import java.time.Duration
import java.time.Instant
import java.util.UUID

// The point of decorating the port instead of the DataSource: the whole behaviour is
// provable against a fake, with no database and no Spring context.
class CircuitBreakingTransactionStoreTest {
    private val delegate = FakeTransactionStore()
    private val breaker =
        CircuitBreaker.of(
            "test",
            CircuitBreakerConfig
                .custom()
                .recordException(CircuitBreakingTransactionStore::isInfrastructureFailure)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(WINDOW)
                .minimumNumberOfCalls(WINDOW)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(OPEN_WAIT)
                .permittedNumberOfCallsInHalfOpenState(PROBES)
                .build(),
        )
    private val store = CircuitBreakingTransactionStore(delegate, breaker, RETRY_AFTER)

    @Test
    fun `a healthy call passes through untouched`() {
        val expected = approved()
        delegate.answer = { expected }

        assertThat(store.authorize(command())).isEqualTo(expected)
        assertThat(breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    @Test
    fun `an infrastructure failure answers unavailable instead of leaking the database exception`() {
        delegate.answer = { throw DataAccessResourceFailureException("the database is gone") }

        assertThatThrownBy { store.authorize(command()) }
            .isInstanceOf(AuthorizationUnavailableException::class.java)
            .hasCauseInstanceOf(DataAccessResourceFailureException::class.java)
    }

    @Test
    fun `a failure to open a transaction counts as an outage, not as a defect`() {
        delegate.answer = { throw CannotCreateTransactionException("no connection to begin with") }

        assertThatThrownBy { store.authorize(command()) }
            .isInstanceOf(AuthorizationUnavailableException::class.java)
    }

    @Test
    fun `after enough failures the breaker opens and the database stops being called`() {
        delegate.answer = { throw DataAccessResourceFailureException("the database is gone") }
        repeat(WINDOW) { runCatching { store.authorize(command()) } }
        val callsBeforeOpening = delegate.calls

        assertThat(breaker.state).isEqualTo(CircuitBreaker.State.OPEN)
        assertThatThrownBy { store.authorize(command()) }
            .isInstanceOf(AuthorizationUnavailableException::class.java)
            .extracting { (it as AuthorizationUnavailableException).retryAfter }
            .isEqualTo(RETRY_AFTER)
        // The whole point of opening: the request fails fast at the edge rather than
        // parking on a connection that will not come.
        assertThat(delegate.calls).isEqualTo(callsBeforeOpening)
    }

    @Test
    fun `an expected duplicate does not count as a failure and never opens the breaker`() {
        // The idempotency path raises this on every genuine race for the same transaction
        // id. A breaker that counted it would open under healthy traffic.
        delegate.answer = { throw DuplicateKeyException("the claim was already taken") }

        repeat(WINDOW * 2) {
            assertThatThrownBy { store.authorize(command()) }.isInstanceOf(DuplicateKeyException::class.java)
        }

        assertThat(breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    @Test
    fun `once the dependency recovers the breaker closes again`() {
        delegate.answer = { throw DataAccessResourceFailureException("the database is gone") }
        repeat(WINDOW) { runCatching { store.authorize(command()) } }
        assertThat(breaker.state).isEqualTo(CircuitBreaker.State.OPEN)

        Thread.sleep(OPEN_WAIT.toMillis() * 2)
        val expected = approved()
        delegate.answer = { expected }
        repeat(PROBES) { assertThat(store.authorize(command())).isEqualTo(expected) }

        assertThat(breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    private fun command() =
        AuthorizationCommand(
            transactionId = UUID.randomUUID(),
            accountId = ACCOUNT,
            type = TransactionType.DEBIT,
            amount = Money(30),
            requestHash = "hash",
            timestamp = Instant.parse("2026-07-26T12:00:00Z"),
        )

    private fun approved() =
        AuthorizationResult.Approved(
            accountId = ACCOUNT,
            type = TransactionType.DEBIT,
            amount = Money(30),
            balanceAfter = Money(70),
            timestamp = Instant.parse("2026-07-26T12:00:00Z"),
        )

    private class FakeTransactionStore : TransactionStore {
        var answer: () -> AuthorizationResult = { throw IllegalStateException("no answer configured") }
        var calls = 0

        override fun authorize(command: AuthorizationCommand): AuthorizationResult {
            calls++
            return answer()
        }
    }

    private companion object {
        val ACCOUNT: UUID = UUID.randomUUID()
        val RETRY_AFTER: Duration = Duration.ofSeconds(5)
        val OPEN_WAIT: Duration = Duration.ofMillis(100)
        const val WINDOW = 4
        const val PROBES = 2
    }
}
