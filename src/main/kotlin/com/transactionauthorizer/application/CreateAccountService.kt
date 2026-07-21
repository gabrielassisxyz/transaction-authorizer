package com.transactionauthorizer.application

import com.transactionauthorizer.application.port.AccountCreationOutcome
import com.transactionauthorizer.application.port.AccountStore
import com.transactionauthorizer.domain.Account
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class CreateAccountService(
    private val accountStore: AccountStore,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(account: Account): AccountCreationOutcome {
        val outcome = accountStore.createIfAbsent(account)
        when (outcome) {
            is AccountCreationOutcome.Created ->
                meterRegistry.counter("accounts.created").increment()

            is AccountCreationOutcome.AlreadyExists ->
                meterRegistry.counter("accounts.duplicate").increment()

            is AccountCreationOutcome.Diverged -> {
                meterRegistry.counter("accounts.conflict").increment()
                log.warn(
                    "account {} already exists with owner {} and status {}, incoming event carried owner {} " +
                        "and status {}; keeping the stored account",
                    account.id,
                    outcome.storedOwnerId,
                    outcome.storedStatus,
                    account.ownerId,
                    account.status,
                )
            }
        }
        return outcome
    }
}
