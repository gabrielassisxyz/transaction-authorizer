package com.transactionauthorizer.application

import com.transactionauthorizer.application.port.AuthorizationCommand
import com.transactionauthorizer.application.port.AuthorizationResult
import com.transactionauthorizer.application.port.TransactionStore
import com.transactionauthorizer.domain.Money
import com.transactionauthorizer.domain.TransactionType
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

@Service
class AuthorizeTransactionService(
    private val transactionStore: TransactionStore,
    private val clock: Clock,
) {
    fun authorize(
        transactionId: UUID,
        accountId: UUID,
        type: TransactionType,
        amount: Money,
    ): AuthorizationResult =
        transactionStore.authorize(
            AuthorizationCommand(
                transactionId = transactionId,
                accountId = accountId,
                type = type,
                amount = amount,
                requestHash = requestHash(accountId, type, amount),
                timestamp = clock.instant(),
            ),
        )

    // Currency is part of the identity even though only BRL is accepted today, so the hash
    // does not silently collide the day a second currency is allowed.
    private fun requestHash(
        accountId: UUID,
        type: TransactionType,
        amount: Money,
    ): String {
        val canonical = "$accountId|$type|${amount.cents}|${Money.CURRENCY}"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
