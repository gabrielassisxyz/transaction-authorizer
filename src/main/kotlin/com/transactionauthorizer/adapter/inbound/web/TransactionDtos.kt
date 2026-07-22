package com.transactionauthorizer.adapter.inbound.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.transactionauthorizer.domain.TransactionType
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// Transport shapes only, kept apart from domain and from the JPA-less persistence rows. A
// missing or wrongly typed field fails deserialization and surfaces as 400, never as a null
// reaching the service.
data class AuthorizeTransactionRequest(
    @param:JsonProperty("account_id")
    val accountId: UUID,
    val type: TransactionType,
    val amount: MoneyPayload,
)

data class MoneyPayload(
    val value: BigDecimal,
    val currency: String,
)

data class AuthorizeTransactionResponse(
    val transaction: TransactionView,
    val account: AccountView,
)

data class TransactionView(
    val id: UUID,
    val type: TransactionType,
    val amount: MoneyPayload,
    val status: TransactionStatus,
    val timestamp: Instant,
)

data class AccountView(
    val id: UUID,
    val balance: MoneyPayload,
)

enum class TransactionStatus {
    SUCCEEDED,
    FAILED,
}
