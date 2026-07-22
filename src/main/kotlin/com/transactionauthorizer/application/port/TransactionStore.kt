package com.transactionauthorizer.application.port

import com.transactionauthorizer.domain.Money
import com.transactionauthorizer.domain.TransactionType
import java.time.Instant
import java.util.UUID

interface TransactionStore {
    fun authorize(command: AuthorizationCommand): AuthorizationResult
}

data class AuthorizationCommand(
    val transactionId: UUID,
    val accountId: UUID,
    val type: TransactionType,
    val amount: Money,
    // Distinguishes a genuine retry of this transaction from an id reused for a different
    // request; computed once by the service, stored with the claim, compared on replay.
    val requestHash: String,
    val timestamp: Instant,
)

// The refusal reason is deliberately absent: the HTTP contract answers SUCCEEDED or FAILED
// with no reason field, and it is not persisted, so a replayed refusal could not carry one.
sealed class AuthorizationResult {
    data class Approved(
        val balanceAfter: Money,
        val timestamp: Instant,
    ) : AuthorizationResult()

    data class Refused(
        val balanceAfter: Money,
        val timestamp: Instant,
    ) : AuthorizationResult()

    data object AccountNotFound : AuthorizationResult()
}
