package com.transactionauthorizer.adapter.outbound.persistence

import com.transactionauthorizer.application.port.AuthorizationCommand
import com.transactionauthorizer.application.port.AuthorizationResult
import com.transactionauthorizer.domain.Money
import com.transactionauthorizer.domain.TransactionType
import com.transactionauthorizer.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The whole invariant of the service, that two concurrent debits never drive a balance
// negative, is provable only against a real database with real threads. This suite is
// written to be able to fail: the assertions are exact (final balance, success count), and
// the thread count is high enough to force interleaving.
class JdbcTransactionStoreConcurrencyTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var transactionStore: JdbcTransactionStore

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Test
    fun `two debits racing for a balance that covers one leave exactly one winner`() {
        val account = anAccount(balanceCents = 100)

        val outcomes = inParallel(2) { debit(account, cents = 100) }

        assertThat(outcomes.filterIsInstance<AuthorizationResult.Approved>()).hasSize(1)
        assertThat(outcomes.filterIsInstance<AuthorizationResult.Refused>()).hasSize(1)
        assertThat(balanceOf(account)).isEqualTo(0)
    }

    @Test
    fun `fifty debits on a balance that covers twenty approve exactly twenty`() {
        val affordable = 20
        val debitCents = 10L
        val account = anAccount(balanceCents = affordable * debitCents)

        val outcomes = inParallel(50) { debit(account, cents = debitCents) }

        assertThat(outcomes.filterIsInstance<AuthorizationResult.Approved>()).hasSize(affordable)
        assertThat(balanceOf(account)).isEqualTo(0)
    }

    @Test
    fun `the same transaction id fired concurrently moves the balance exactly once`() {
        val account = anAccount(balanceCents = 100)
        val transactionId = UUID.randomUUID()

        val outcomes = inParallel(16) { debit(account, cents = 30, transactionId = transactionId) }

        assertThat(transactionRowsFor(transactionId)).isEqualTo(1)
        assertThat(balanceOf(account)).isEqualTo(70)
        // Every caller, winner and duplicate alike, receives the identical stored decision.
        assertThat(outcomes.toSet()).hasSize(1)
        assertThat(outcomes).allMatch { it == AuthorizationResult.Approved(Money(70), FIXED_TIMESTAMP) }
    }

    private fun inParallel(
        threads: Int,
        authorize: () -> AuthorizationResult,
    ): List<AuthorizationResult> {
        val barrier = CyclicBarrier(threads)
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            (1..threads)
                .map {
                    executor.submit<AuthorizationResult> {
                        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        authorize()
                    }
                }.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
    }

    private fun debit(
        accountId: UUID,
        cents: Long,
        transactionId: UUID = UUID.randomUUID(),
    ) = AuthorizationCommand(
        transactionId = transactionId,
        accountId = accountId,
        type = TransactionType.DEBIT,
        amount = Money(cents),
        requestHash = hashOf(accountId, TransactionType.DEBIT, cents),
        timestamp = FIXED_TIMESTAMP,
    ).let(transactionStore::authorize)

    private fun anAccount(
        balanceCents: Long,
        status: String = "ENABLED",
    ): UUID {
        val id = UUID.randomUUID()
        jdbcClient
            .sql(
                "INSERT INTO accounts (id, owner_id, status, created_at, balance_cents) " +
                    "VALUES (:id, :owner, :status, :createdAt, :balance)",
            ).param("id", id)
            .param("owner", UUID.randomUUID())
            .param("status", status)
            .param("createdAt", Timestamp.from(Instant.parse("2026-07-21T00:00:00Z")))
            .param("balance", balanceCents)
            .update()
        return id
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
        val FIXED_TIMESTAMP: Instant = Instant.parse("2026-07-21T12:00:00Z")
    }
}
