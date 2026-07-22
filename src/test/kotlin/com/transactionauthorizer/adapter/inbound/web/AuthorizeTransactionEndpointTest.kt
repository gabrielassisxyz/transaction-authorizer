package com.transactionauthorizer.adapter.inbound.web

import com.transactionauthorizer.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
class AuthorizeTransactionEndpointTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Test
    fun `an affordable debit is approved and moves the balance`() {
        val account = account(balanceCents = 500)

        authorize(UUID.randomUUID(), account, "DEBIT", "2.00").andExpect {
            status { isOk() }
            jsonPath("$.transaction.status") { value("SUCCEEDED") }
            jsonPath("$.account.balance.currency") { value("BRL") }
        }

        assertThat(balanceOf(account)).isEqualTo(300)
    }

    @Test
    fun `a credit is approved and raises the balance`() {
        val account = account(balanceCents = 500)

        authorize(UUID.randomUUID(), account, "CREDIT", "2.50").andExpect {
            status { isOk() }
            jsonPath("$.transaction.status") { value("SUCCEEDED") }
        }

        assertThat(balanceOf(account)).isEqualTo(750)
    }

    @Test
    fun `a debit beyond the balance is refused and leaves the balance untouched`() {
        val account = account(balanceCents = 100)

        authorize(UUID.randomUUID(), account, "DEBIT", "2.00").andExpect {
            status { isOk() }
            jsonPath("$.transaction.status") { value("FAILED") }
        }

        assertThat(balanceOf(account)).isEqualTo(100)
    }

    @Test
    fun `a debit on a disabled account is refused`() {
        val account = account(balanceCents = 500, status = "DISABLED")

        authorize(UUID.randomUUID(), account, "DEBIT", "1.00").andExpect {
            status { isOk() }
            jsonPath("$.transaction.status") { value("FAILED") }
        }

        assertThat(balanceOf(account)).isEqualTo(500)
    }

    @Test
    fun `a transaction against an unknown account is not found`() {
        authorize(UUID.randomUUID(), UUID.randomUUID(), "DEBIT", "1.00").andExpect {
            status { isNotFound() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `a replayed transaction id returns the first decision and moves the balance once`() {
        val account = account(balanceCents = 100)
        val transactionId = UUID.randomUUID()

        authorize(transactionId, account, "DEBIT", "0.30").andExpect {
            status { isOk() }
            jsonPath("$.transaction.status") { value("SUCCEEDED") }
        }
        authorize(transactionId, account, "DEBIT", "0.30").andExpect {
            status { isOk() }
            jsonPath("$.transaction.status") { value("SUCCEEDED") }
        }

        assertThat(balanceOf(account)).isEqualTo(70)
    }

    @Test
    fun `a malformed body is rejected`() {
        mockMvc
            .post("/transactions/${UUID.randomUUID()}") {
                contentType = MediaType.APPLICATION_JSON
                content = "{ not json"
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a body missing a field is rejected`() {
        mockMvc
            .post("/transactions/${UUID.randomUUID()}") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"DEBIT","amount":{"value":1.00,"currency":"BRL"}}"""
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a non-uuid transaction id is rejected`() {
        mockMvc
            .post("/transactions/not-a-uuid") {
                contentType = MediaType.APPLICATION_JSON
                content = body(UUID.randomUUID(), "DEBIT", "1.00")
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a currency other than BRL is unprocessable`() {
        val account = account(balanceCents = 500)

        mockMvc
            .post("/transactions/${UUID.randomUUID()}") {
                contentType = MediaType.APPLICATION_JSON
                content = body(account, "DEBIT", "1.00", currency = "USD")
            }.andExpect { status { isUnprocessableContent() } }
    }

    @Test
    fun `an amount with sub-cent precision is rejected`() {
        val account = account(balanceCents = 500)

        mockMvc
            .post("/transactions/${UUID.randomUUID()}") {
                contentType = MediaType.APPLICATION_JSON
                content = body(account, "DEBIT", "1.501")
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a non-positive amount is rejected`() {
        val account = account(balanceCents = 500)

        mockMvc
            .post("/transactions/${UUID.randomUUID()}") {
                contentType = MediaType.APPLICATION_JSON
                content = body(account, "DEBIT", "0.00")
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `an amount beyond the representable range is unprocessable`() {
        val account = account(balanceCents = 500)

        mockMvc
            .post("/transactions/${UUID.randomUUID()}") {
                contentType = MediaType.APPLICATION_JSON
                content = body(account, "CREDIT", "999999999999999999999")
            }.andExpect { status { isUnprocessableContent() } }
    }

    private fun authorize(
        transactionId: UUID,
        accountId: UUID,
        type: String,
        value: String,
    ) = mockMvc.post("/transactions/$transactionId") {
        contentType = MediaType.APPLICATION_JSON
        content = body(accountId, type, value)
    }

    private fun body(
        accountId: UUID,
        type: String,
        value: String,
        currency: String = "BRL",
    ) = """{"account_id":"$accountId","type":"$type","amount":{"value":$value,"currency":"$currency"}}"""

    private fun account(
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
}
