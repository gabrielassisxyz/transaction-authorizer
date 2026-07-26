package com.transactionauthorizer.adapter.outbound.resilience

import com.transactionauthorizer.application.port.TransactionStore
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Duration

// Wired by hand, with the core library and no starter: no annotation, no AOP proxy, and
// nothing to guess about a Spring Boot starter's version compatibility. The whole
// configuration of the breaker is these few numbers, and they are here rather than in
// application.yaml because each one is an argument, not an environment setting.
@Configuration
class TransactionStoreResilienceConfiguration {
    @Bean
    fun authorizationCircuitBreaker(meterRegistry: MeterRegistry): CircuitBreaker {
        val config =
            CircuitBreakerConfig
                .custom()
                // Only infrastructure failures count. Everything else, including the
                // DuplicateKeyException that the idempotency path raises by design, is a
                // successful call as far as the breaker is concerned.
                .recordException(CircuitBreakingTransactionStore::isInfrastructureFailure)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(WINDOW_SIZE)
                .minimumNumberOfCalls(MINIMUM_CALLS)
                .failureRateThreshold(FAILURE_RATE_PERCENT)
                .waitDurationInOpenState(OPEN_STATE_WAIT)
                .permittedNumberOfCallsInHalfOpenState(HALF_OPEN_PROBES)
                .build()
        val breaker = CircuitBreaker.of("authorization-store", config)

        // A state change is an operational event, so it is logged where the outage is
        // being read, and exposed as a gauge so an alert can fire on it.
        breaker.eventPublisher.onStateTransition {
            log.warn("authorization circuit breaker moved to {}", it.stateTransition.toState)
        }
        Gauge
            .builder("authorizations.circuit.open") { if (breaker.state == CircuitBreaker.State.OPEN) 1.0 else 0.0 }
            .description("1 while the authorization store circuit breaker is refusing calls")
            .register(meterRegistry)
        return breaker
    }

    // Primary so the application layer keeps injecting the port and gets the decorated one.
    // The delegate is asked for by bean name rather than by type: naming the concrete
    // adapter here would make this slice depend on the persistence slice, which is exactly
    // the coupling the architecture test forbids.
    @Bean
    @Primary
    fun circuitBreakingTransactionStore(
        @Qualifier("jdbcTransactionStore") delegate: TransactionStore,
        breaker: CircuitBreaker,
    ): TransactionStore = CircuitBreakingTransactionStore(delegate, breaker, OPEN_STATE_WAIT)

    companion object {
        private val log = LoggerFactory.getLogger(TransactionStoreResilienceConfiguration::class.java)

        // Sized for a service that answers thousands of requests per second: the window is
        // short so a dead dependency is recognised in the first instants of the outage, and
        // the minimum keeps a single blip on an idle instance from tripping it.
        private const val WINDOW_SIZE = 20
        private const val MINIMUM_CALLS = 10
        private const val FAILURE_RATE_PERCENT = 50.0f

        // Short enough that recovery is noticed almost immediately, long enough that the
        // probes do not become the retry storm the breaker exists to prevent. It is also
        // the Retry-After the client is given.
        private val OPEN_STATE_WAIT: Duration = Duration.ofSeconds(5)
        private const val HALF_OPEN_PROBES = 3
    }
}
