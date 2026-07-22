package com.transactionauthorizer.adapter.outbound.persistence

import com.transactionauthorizer.application.port.AuthorizationCommand
import com.transactionauthorizer.domain.Money
import com.transactionauthorizer.domain.TransactionType
import com.transactionauthorizer.support.PostgresIntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

// Pins the metric names and their outcome/reason tags: dashboards and the load campaign read
// these, so a rename or a dropped tag is a contract break the suite has to catch.
@AutoConfigureMockMvc
class AuthorizationMetricsTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var transactionStore: JdbcTransactionStore

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `an authorization whose transaction rolls back leaves the outcome counter untouched`() {
        val account = anAccount(balanceCents = 100)
        val before = authorizations("approved", "applied")

        // The store joins this surrounding transaction, so rolling it back undoes the approval
        // the guarded update made. The counter must move with the commit, never before it.
        TransactionTemplate(transactionManager).execute { status ->
            debit(account, cents = 40)
            status.setRollbackOnly()
        }

        assertThat(authorizations("approved", "applied")).isEqualTo(before)
        assertThat(balanceOf(account)).isEqualTo(100)
    }

    @Test
    fun `an applied debit increments authorizations approved,applied`() {
        val account = anAccount(balanceCents = 100)
        val before = authorizations("approved", "applied")

        debit(account, cents = 40)

        assertThat(authorizations("approved", "applied")).isEqualTo(before + 1)
    }

    @Test
    fun `a debit beyond the balance increments authorizations refused,insufficient_funds`() {
        val account = anAccount(balanceCents = 30)
        val before = authorizations("refused", "insufficient_funds")

        debit(account, cents = 100)

        assertThat(authorizations("refused", "insufficient_funds")).isEqualTo(before + 1)
    }

    @Test
    fun `a credit past the ledger bound increments authorizations refused,credit_overflow`() {
        val account = anAccount(balanceCents = Money.MAX.cents - 10)
        val before = authorizations("refused", "credit_overflow")

        credit(account, cents = 100)

        assertThat(authorizations("refused", "credit_overflow")).isEqualTo(before + 1)
    }

    @Test
    fun `an unknown account increments authorizations account_not_found`() {
        val before = authorizations("account_not_found", "account_not_found")

        debit(UUID.randomUUID(), cents = 10)

        assertThat(authorizations("account_not_found", "account_not_found")).isEqualTo(before + 1)
    }

    @Test
    fun `a replay of a stored decision increments authorizations approved,replay`() {
        val account = anAccount(balanceCents = 100)
        val transactionId = UUID.randomUUID()
        debit(account, cents = 40, transactionId = transactionId)
        val before = authorizations("approved", "replay")

        debit(account, cents = 40, transactionId = transactionId)

        assertThat(authorizations("approved", "replay")).isEqualTo(before + 1)
    }

    @Test
    fun `a replay carrying a divergent payload increments transactions_duplicate_payload`() {
        val account = anAccount(balanceCents = 100)
        val transactionId = UUID.randomUUID()
        debit(account, cents = 40, transactionId = transactionId)
        val before = meterRegistry.find("transactions.duplicate.payload").counter()?.count() ?: 0.0

        debit(account, cents = 55, transactionId = transactionId)

        val after = meterRegistry.find("transactions.duplicate.payload").counter()?.count() ?: 0.0
        assertThat(after).isEqualTo(before + 1)
    }

    @Test
    fun `the prometheus endpoint exposes the custom authorization metric`() {
        debit(anAccount(balanceCents = 100), cents = 10)

        mockMvc.get("/actuator/prometheus").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("authorizations_total")) }
        }
    }

    private fun authorizations(
        outcome: String,
        reason: String,
    ): Double =
        meterRegistry
            .find("authorizations")
            .tags("outcome", outcome, "reason", reason)
            .counter()
            ?.count() ?: 0.0

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
        jdbcClient
            .sql(
                "INSERT INTO accounts (id, owner_id, status, created_at, balance_cents) " +
                    "VALUES (:id, :owner, 'ENABLED', :createdAt, :balance)",
            ).param("id", id)
            .param("owner", UUID.randomUUID())
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
        val FIXED_TIMESTAMP: Instant = Instant.parse("2026-07-21T12:00:00Z")
    }
}
