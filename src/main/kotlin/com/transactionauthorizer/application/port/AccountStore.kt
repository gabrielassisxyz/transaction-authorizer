package com.transactionauthorizer.application.port

import com.transactionauthorizer.domain.Account
import com.transactionauthorizer.domain.AccountStatus
import java.time.Instant
import java.util.UUID

interface AccountStore {
    fun createIfAbsent(account: Account): AccountCreationOutcome
}

sealed class AccountCreationOutcome {
    data object Created : AccountCreationOutcome()

    data object AlreadyExists : AccountCreationOutcome()

    // The first write wins, but a same-id-different-payload event is evidence of an
    // upstream defect, so all three stored fields travel out instead of being swallowed.
    data class Diverged(
        val storedOwnerId: UUID,
        val storedStatus: AccountStatus,
        val storedCreatedAt: Instant,
    ) : AccountCreationOutcome()
}
