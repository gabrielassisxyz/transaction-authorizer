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
//
// accountId/type/amount carry the DECIDED transaction, not the caller's: on a fresh
// authorization that is the command's own fields, but on a replay it is whatever was
// claimed first, which can differ from the request that reused the id. A response built
// from these fields can never mix a stored decision with a replaying request.
sealed class AuthorizationResult {
    data class Approved(
        val accountId: UUID,
        val type: TransactionType,
        val amount: Money,
        val balanceAfter: Money,
        val timestamp: Instant,
    ) : AuthorizationResult()

    data class Refused(
        val accountId: UUID,
        val type: TransactionType,
        val amount: Money,
        val balanceAfter: Money,
        val timestamp: Instant,
    ) : AuthorizationResult()

    data object AccountNotFound : AuthorizationResult()
}
