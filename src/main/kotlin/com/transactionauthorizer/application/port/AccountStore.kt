package com.transactionauthorizer.application.port

import com.transactionauthorizer.domain.Account
import com.transactionauthorizer.domain.AccountStatus
import java.util.UUID

interface AccountStore {
    fun createIfAbsent(account: Account): AccountCreationOutcome
}

sealed class AccountCreationOutcome {
    data object Created : AccountCreationOutcome()

    data object AlreadyExists : AccountCreationOutcome()

    // Same account id, different owner or status: the first write wins, but the
    // divergence is evidence of an upstream defect and must not be swallowed.
    data class Diverged(
        val storedOwnerId: UUID,
        val storedStatus: AccountStatus,
    ) : AccountCreationOutcome()
}
