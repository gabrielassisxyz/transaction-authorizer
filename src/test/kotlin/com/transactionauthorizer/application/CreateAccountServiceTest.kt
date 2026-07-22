package com.transactionauthorizer.application

import com.transactionauthorizer.application.port.AccountCreationOutcome
import com.transactionauthorizer.application.port.AccountStore
import com.transactionauthorizer.domain.Account
import com.transactionauthorizer.domain.AccountStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CreateAccountServiceTest {
    private val accountStore = mockk<AccountStore>()
    private val meterRegistry = SimpleMeterRegistry()
    private val service = CreateAccountService(accountStore, meterRegistry)

    private val account =
        Account(
            id = UUID.randomUUID(),
            ownerId = UUID.randomUUID(),
            status = AccountStatus.ENABLED,
            createdAt = Instant.parse("2026-07-21T04:53:20Z"),
        )

    @Test
    fun `counts an account that did not exist yet`() {
        every { accountStore.createIfAbsent(account) } returns AccountCreationOutcome.Created

        assertThat(service.create(account)).isEqualTo(AccountCreationOutcome.Created)
        assertThat(counter("accounts.created")).isEqualTo(1.0)
    }

    @Test
    fun `a redelivered event is a non-event`() {
        every { accountStore.createIfAbsent(account) } returns AccountCreationOutcome.AlreadyExists

        assertThat(service.create(account)).isEqualTo(AccountCreationOutcome.AlreadyExists)
        assertThat(counter("accounts.duplicate")).isEqualTo(1.0)
        assertThat(counter("accounts.conflict")).isZero()
    }

    @Test
    fun `an event that contradicts the stored account is an anomaly, not a duplicate`() {
        val storedOwner = UUID.randomUUID()
        val storedCreatedAt = Instant.parse("2020-01-01T00:00:00Z")
        every { accountStore.createIfAbsent(account) } returns
            AccountCreationOutcome.Diverged(storedOwner, AccountStatus.DISABLED, storedCreatedAt)

        val outcome = service.create(account)

        assertThat(outcome)
            .isEqualTo(AccountCreationOutcome.Diverged(storedOwner, AccountStatus.DISABLED, storedCreatedAt))
        assertThat(counter("accounts.conflict")).isEqualTo(1.0)
        assertThat(counter("accounts.duplicate")).isZero()
    }

    private fun counter(name: String): Double = meterRegistry.counter(name).count()
}
