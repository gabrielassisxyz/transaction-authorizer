package com.transactionauthorizer.adapter.outbound.resilience

import com.transactionauthorizer.application.port.AuthorizationCommand
import com.transactionauthorizer.application.port.AuthorizationResult
import com.transactionauthorizer.application.port.AuthorizationUnavailableException
import com.transactionauthorizer.application.port.TransactionStore
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionException
import java.time.Duration

// A decorator over the outbound port, not an annotation on a service. The seam already
// exists, so the breaker costs no new abstraction, it is unit-testable against a fake with
// no database, and the resilience library stops at the adapter: the application layer sees
// only the port and its failure contract.
//
// The DataSource is deliberately NOT the thing being decorated. Wrapping it would put the
// breaker in front of Flyway and the health check too, so an open breaker would blind the
// very probe that is supposed to report the outage.
class CircuitBreakingTransactionStore(
    private val delegate: TransactionStore,
    private val breaker: CircuitBreaker,
    // The same wait the breaker stays open for, handed to the client as Retry-After. It is
    // passed in rather than read back from the breaker so there is one number, configured
    // in one place, and a test can shorten it without reaching into the library's config.
    private val retryAfter: Duration,
) : TransactionStore {
    override fun authorize(command: AuthorizationCommand): AuthorizationResult =
        try {
            breaker.executeSupplier { delegate.authorize(command) }
        } catch (e: CallNotPermittedException) {
            // The breaker is open: the call never reached the database, so there is no
            // decision to report and the caller is told when to come back.
            throw unavailable(e)
        } catch (e: DataAccessException) {
            throw translate(e)
        } catch (e: TransactionException) {
            throw translate(e)
        }

    // The breaker has already counted this call by the time we get here, so translating
    // afterwards keeps the counting rule (recordExceptions) and the HTTP mapping in
    // agreement without either one having to know about the other.
    private fun translate(e: RuntimeException): RuntimeException = if (isInfrastructureFailure(e)) unavailable(e) else e

    private fun unavailable(cause: Throwable) = AuthorizationUnavailableException(retryAfter, cause)

    companion object {
        // Infrastructure failures only, and the list is exhaustive on purpose. A breaker
        // that counts expected exceptions opens under healthy traffic: the idempotency path
        // raises DuplicateKeyException whenever two requests race on the same transaction
        // id, which is the design working, not the database failing.
        //
        // CannotCreateTransactionException is what surfaces when the connection cannot be
        // acquired to OPEN a transaction, and it is not a DataAccessException; leaving it
        // out would keep the breaker closed through the exact outage it exists for.
        // CannotGetJdbcConnectionException, raised outside a transaction, arrives as a
        // subclass of DataAccessResourceFailureException.
        private val INFRASTRUCTURE_FAILURES: List<Class<out Throwable>> =
            listOf(
                DataAccessResourceFailureException::class.java,
                QueryTimeoutException::class.java,
                CannotCreateTransactionException::class.java,
            )

        // The breaker's counting rule and this decorator's translation rule are the same
        // predicate on purpose: an exception that opens the circuit is exactly one that
        // answers 503, and they cannot drift apart into two lists.
        fun isInfrastructureFailure(e: Throwable): Boolean = INFRASTRUCTURE_FAILURES.any { it.isInstance(e) }
    }
}
