package com.transactionauthorizer.domain

import java.time.Instant
import java.util.UUID

data class Account(
    val id: UUID,
    val ownerId: UUID,
    val status: AccountStatus,
    val createdAt: Instant,
    val balance: Money = Money.ZERO,
)
