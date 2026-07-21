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

    // Same account id, different owner, status or creation instant: the first write
    // wins, but the divergence is evidence of an upstream defect and must not be
    // swallowed. All three stored fields travel with it, because the one that diverged
    // is exactly the one the operator needs to see.
    data class Diverged(
        val storedOwnerId: UUID,
        val storedStatus: AccountStatus,
        val storedCreatedAt: Instant,
    ) : AccountCreationOutcome()
}
