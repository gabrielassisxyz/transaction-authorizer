package com.transactionauthorizer.application

import com.transactionauthorizer.application.port.AuthorizationCommand
import com.transactionauthorizer.application.port.AuthorizationResult
import com.transactionauthorizer.application.port.TransactionStore
import com.transactionauthorizer.domain.Money
import com.transactionauthorizer.domain.TransactionType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AuthorizeTransactionServiceTest {
    private val store = mockk<TransactionStore>()
    private val now = Instant.parse("2026-07-21T12:00:00Z")
    private val service = AuthorizeTransactionService(store, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `it stamps the command from the clock and passes the request through unchanged`() {
        val captured = slot<AuthorizationCommand>()
        val stored = AuthorizationResult.Approved(Money(70), now)
        every { store.authorize(capture(captured)) } returns stored
        val transactionId = UUID.randomUUID()
        val accountId = UUID.randomUUID()

        val result = service.authorize(transactionId, accountId, TransactionType.DEBIT, Money(30))

        assertThat(result).isEqualTo(stored)
        with(captured.captured) {
            assertThat(this.transactionId).isEqualTo(transactionId)
            assertThat(this.accountId).isEqualTo(accountId)
            assertThat(type).isEqualTo(TransactionType.DEBIT)
            assertThat(amount).isEqualTo(Money(30))
            assertThat(timestamp).isEqualTo(now)
        }
    }

    @Test
    fun `the request hash is 64 lowercase hex characters and is stable for the same request`() {
        val accountId = UUID.randomUUID()

        val first = hashOf(accountId, TransactionType.DEBIT, Money(30))
        val second = hashOf(accountId, TransactionType.DEBIT, Money(30))

        assertThat(first).isEqualTo(second)
        assertThat(first).matches("[0-9a-f]{64}")
    }

    @Test
    fun `the request hash separates transactions that differ in any field`() {
        val accountId = UUID.randomUUID()
        val base = hashOf(accountId, TransactionType.DEBIT, Money(30))

        assertThat(hashOf(accountId, TransactionType.DEBIT, Money(31))).isNotEqualTo(base)
        assertThat(hashOf(accountId, TransactionType.CREDIT, Money(30))).isNotEqualTo(base)
        assertThat(hashOf(UUID.randomUUID(), TransactionType.DEBIT, Money(30))).isNotEqualTo(base)
    }

    private fun hashOf(
        accountId: UUID,
        type: TransactionType,
        amount: Money,
    ): String {
        val captured = slot<AuthorizationCommand>()
        every { store.authorize(capture(captured)) } returns AuthorizationResult.AccountNotFound
        service.authorize(UUID.randomUUID(), accountId, type, amount)
        return captured.captured.requestHash
    }
}
