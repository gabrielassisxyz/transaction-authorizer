package com.transactionauthorizer.adapter.outbound.persistence

import com.transactionauthorizer.application.port.AuthorizationCommand
import com.transactionauthorizer.application.port.AuthorizationResult
import com.transactionauthorizer.domain.Money
import com.transactionauthorizer.domain.TransactionType
import com.transactionauthorizer.support.PostgresIntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

// Regression tests that pin persistence paths the S3 corner-case suite left uncovered. Each
// one fails if the guarded update, the FOR UPDATE slow path, or the claim rollback regresses.
// The slow-path and rollback cases drive a real interleaving deterministically: a held
// transaction owns the row lock, the authorization blocks on it, and the test releases it at
// the exact point the branch under test needs.
class JdbcTransactionStoreRegressionTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var transactionStore: JdbcTransactionStore

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `concurrent replay with divergent payloads keeps one decision and moves the balance once`() {
        val account = anAccount(balanceCents = 500)
        val transactionId = UUID.randomUUID()
        val duplicatesBefore = duplicatePayloadCount()
        // Distinct amounts, so every caller carries a different request hash: this is the
        // divergent-payload path, not the identical-payload one the concurrency suite fires.
        val amounts = listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L)

        val outcomes =
            inParallel(amounts.size) { i -> debit(account, cents = amounts[i], transactionId = transactionId) }

        assertThat(transactionRowsFor(transactionId)).isEqualTo(1)
        assertThat(outcomes.toSet()).hasSize(1)
        val decided = outcomes.first() as AuthorizationResult.Approved
        assertThat(balanceOf(account)).isEqualTo(decided.balanceAfter.cents)
        assertThat(500 - decided.balanceAfter.cents).isIn(amounts)
        assertThat(duplicatePayloadCount()).isGreaterThan(duplicatesBefore)
    }

    @Test
    fun `a concurrent credit under the lock saves a debit that missed the guarded update`() {
        val account = anAccount(balanceCents = 50)
        val holder = dataSource.connection
        val worker = Executors.newSingleThreadExecutor()
        try {
            holder.autoCommit = false
            raiseBalanceHoldingLock(holder, account, toCents = 150)

            val debit = worker.submit<AuthorizationResult> { debit(account, cents = 100) }
            // The debit missed the guarded update (balance 50 < 100) and now waits on the row
            // lock this held transaction owns; committing it lets the debit re-read 150.
            await().atMost(TIMEOUT).until { lockWaiters() >= 1 }
            holder.commit()

            assertThat(debit.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isEqualTo(AuthorizationResult.Approved(Money(50), FIXED_TIMESTAMP))
        } finally {
            holder.close()
            worker.shutdownNow()
        }
        assertThat(balanceOf(account)).isEqualTo(50)
    }

    @Test
    fun `a credit that would pass the ledger ceiling is refused with the balance untouched`() {
        val account = anAccount(balanceCents = Money.MAX.cents - 50)

        val result = credit(account, cents = 100)

        assertThat(result).isInstanceOf(AuthorizationResult.Refused::class.java)
        assertThat(balanceOf(account)).isEqualTo(Money.MAX.cents - 50)
    }

    @Test
    fun `an account gone at the lock rolls back the claim and leaves the id reclaimable`() {
        val account = anAccount(balanceCents = 50)
        val transactionId = UUID.randomUUID()
        val holder = dataSource.connection
        val worker = Executors.newSingleThreadExecutor()
        try {
            holder.autoCommit = false
            deleteHoldingLock(holder, account)

            val debit =
                worker.submit<AuthorizationResult> { debit(account, cents = 100, transactionId = transactionId) }
            // Present at the pre-check, the account vanishes under the lock: the debit blocks
            // on the delete's row lock, then finds no row once the delete commits.
            await().atMost(TIMEOUT).until { lockWaiters() >= 1 }
            holder.commit()

            assertThat(debit.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(AuthorizationResult.AccountNotFound)
        } finally {
            holder.close()
            worker.shutdownNow()
        }

        assertThat(claimRowsFor(transactionId)).isZero()
        insertAccount(account, balanceCents = 200)
        assertThat(debit(account, cents = 100, transactionId = transactionId))
            .isEqualTo(AuthorizationResult.Approved(Money(100), FIXED_TIMESTAMP))
    }

    private fun raiseBalanceHoldingLock(
        connection: java.sql.Connection,
        accountId: UUID,
        toCents: Long,
    ) = connection.prepareStatement("UPDATE accounts SET balance_cents = ? WHERE id = ?").use { statement ->
        statement.setLong(1, toCents)
        statement.setObject(2, accountId)
        statement.executeUpdate()
    }

    private fun deleteHoldingLock(
        connection: java.sql.Connection,
        accountId: UUID,
    ) = connection.prepareStatement("DELETE FROM accounts WHERE id = ?").use { statement ->
        statement.setObject(1, accountId)
        statement.executeUpdate()
    }

    // Any transaction waiting on a lock: in an isolated container mid-test that is the
    // authorization blocked on the row the held transaction owns.
    private fun lockWaiters(): Int =
        jdbcClient
            .sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")
            .query(Int::class.java)
            .single()

    private fun inParallel(
        threads: Int,
        authorize: (Int) -> AuthorizationResult,
    ): List<AuthorizationResult> {
        val barrier = CyclicBarrier(threads)
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            (0 until threads)
                .map { index ->
                    executor.submit<AuthorizationResult> {
                        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        authorize(index)
                    }
                }.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
    }

    private fun debit(
        accountId: UUID,
        cents: Long,
        transactionId: UUID = UUID.randomUUID(),
    ) = authorize(accountId, TransactionType.DEBIT, cents, transactionId)

    private fun credit(
        accountId: UUID,
        cents: Long,
        transactionId: UUID = UUID.randomUUID(),
    ) = authorize(accountId, TransactionType.CREDIT, cents, transactionId)

    private fun authorize(
        accountId: UUID,
        type: TransactionType,
        cents: Long,
        transactionId: UUID,
    ) = AuthorizationCommand(
        transactionId = transactionId,
        accountId = accountId,
        type = type,
        amount = Money(cents),
        requestHash = hashOf(accountId, type, cents),
        timestamp = FIXED_TIMESTAMP,
    ).let(transactionStore::authorize)

    private fun anAccount(balanceCents: Long): UUID {
        val id = UUID.randomUUID()
        insertAccount(id, balanceCents)
        return id
    }

    private fun insertAccount(
        id: UUID,
        balanceCents: Long,
    ) {
        jdbcClient
            .sql(
                "INSERT INTO accounts (id, owner_id, status, created_at, balance_cents) " +
                    "VALUES (:id, :owner, 'ENABLED', :createdAt, :balance)",
            ).param("id", id)
            .param("owner", UUID.randomUUID())
            .param("createdAt", Timestamp.from(Instant.parse("2026-07-21T00:00:00Z")))
            .param("balance", balanceCents)
            .update()
    }

    private fun balanceOf(accountId: UUID): Long =
        jdbcClient
            .sql("SELECT balance_cents FROM accounts WHERE id = :id")
            .param("id", accountId)
            .query(Long::class.java)
            .single()

    private fun transactionRowsFor(transactionId: UUID): Int =
        jdbcClient
            .sql("SELECT count(*) FROM transactions WHERE id = :id")
            .param("id", transactionId)
            .query(Int::class.java)
            .single()

    private fun claimRowsFor(transactionId: UUID): Int =
        jdbcClient
            .sql("SELECT count(*) FROM transaction_claims WHERE id = :id")
            .param("id", transactionId)
            .query(Int::class.java)
            .single()

    private fun duplicatePayloadCount(): Double = meterRegistry.counter("transactions.duplicate.payload").count()

    private fun hashOf(
        accountId: UUID,
        type: TransactionType,
        cents: Long,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$accountId|$type|$cents|BRL".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
        val TIMEOUT: Duration = Duration.ofSeconds(TIMEOUT_SECONDS)
        val FIXED_TIMESTAMP: Instant = Instant.parse("2026-07-21T12:00:00Z")
    }
}
