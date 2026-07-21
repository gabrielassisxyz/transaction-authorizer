package com.transactionauthorizer.adapter.outbound.persistence

import com.transactionauthorizer.application.port.AccountCreationOutcome
import com.transactionauthorizer.domain.Account
import com.transactionauthorizer.domain.AccountStatus
import com.transactionauthorizer.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JdbcAccountStoreTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var accountStore: JdbcAccountStore

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Test
    fun `concurrent creations of the same account produce exactly one row`() {
        val account = anAccount()
        val barrier = CyclicBarrier(CONCURRENT_ATTEMPTS)

        val outcomes =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val futures =
                    (1..CONCURRENT_ATTEMPTS).map {
                        executor.submit<AccountCreationOutcome> {
                            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            accountStore.createIfAbsent(account)
                        }
                    }
                futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }

        assertThat(outcomes.count { it is AccountCreationOutcome.Created }).isEqualTo(1)
        assertThat(outcomes.count { it is AccountCreationOutcome.AlreadyExists })
            .isEqualTo(CONCURRENT_ATTEMPTS - 1)
        assertThat(rowCountOf(account.id)).isEqualTo(1)
    }

    @Test
    fun `an identical redelivery is a duplicate, not a conflict`() {
        val account = anAccount()

        assertThat(accountStore.createIfAbsent(account)).isEqualTo(AccountCreationOutcome.Created)
        assertThat(accountStore.createIfAbsent(account)).isEqualTo(AccountCreationOutcome.AlreadyExists)
    }

    @Test
    fun `a redelivery whose timestamp moved is a conflict`() {
        val account = anAccount()
        accountStore.createIfAbsent(account)

        val outcome = accountStore.createIfAbsent(account.copy(createdAt = account.createdAt.plusSeconds(1)))

        assertThat(outcome)
            .isEqualTo(AccountCreationOutcome.Diverged(account.ownerId, AccountStatus.ENABLED))
    }

    @Test
    fun `a redelivery whose owner moved is a conflict`() {
        val account = anAccount()
        accountStore.createIfAbsent(account)

        val outcome = accountStore.createIfAbsent(account.copy(ownerId = UUID.randomUUID()))

        assertThat(outcome)
            .isEqualTo(AccountCreationOutcome.Diverged(account.ownerId, AccountStatus.ENABLED))
    }

    // Sub-microsecond precision cannot survive a TIMESTAMPTZ column, so an event whose
    // timestamp carries nanoseconds must still read back as the same account.
    @Test
    fun `a timestamp with nanosecond precision still matches once stored`() {
        val account = anAccount(createdAt = Instant.parse("2026-07-21T04:53:20.123456789Z"))

        assertThat(accountStore.createIfAbsent(account)).isEqualTo(AccountCreationOutcome.Created)
        assertThat(accountStore.createIfAbsent(account)).isEqualTo(AccountCreationOutcome.AlreadyExists)
    }

    private fun anAccount(createdAt: Instant = Instant.parse("2026-07-21T04:53:20Z")) =
        Account(
            id = UUID.randomUUID(),
            ownerId = UUID.randomUUID(),
            status = AccountStatus.ENABLED,
            createdAt = createdAt,
        )

    private fun rowCountOf(accountId: UUID): Int =
        jdbcClient
            .sql("SELECT count(*) FROM accounts WHERE id = :id")
            .param("id", accountId)
            .query(Int::class.java)
            .single()

    private companion object {
        const val CONCURRENT_ATTEMPTS = 16
        const val TIMEOUT_SECONDS = 20L
    }
}
