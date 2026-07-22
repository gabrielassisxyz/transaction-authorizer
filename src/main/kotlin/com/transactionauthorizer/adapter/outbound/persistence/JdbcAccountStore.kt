package com.transactionauthorizer.adapter.outbound.persistence

import com.transactionauthorizer.application.port.AccountCreationOutcome
import com.transactionauthorizer.application.port.AccountStore
import com.transactionauthorizer.domain.Account
import com.transactionauthorizer.domain.AccountStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Repository
class JdbcAccountStore(
    private val jdbcClient: JdbcClient,
) : AccountStore {
    override fun createIfAbsent(account: Account): AccountCreationOutcome {
        val inserted =
            jdbcClient
                .sql(INSERT_IF_ABSENT)
                .param("id", account.id)
                .param("ownerId", account.ownerId)
                .param("status", account.status.name)
                .param("createdAt", Timestamp.from(account.createdAt.truncatedTo(ChronoUnit.MICROS)))
                .param("balanceCents", account.balance.cents)
                .update()

        if (inserted == 1) {
            return AccountCreationOutcome.Created
        }
        return classifyExisting(account)
    }

    private fun classifyExisting(account: Account): AccountCreationOutcome {
        val stored =
            jdbcClient
                .sql("SELECT owner_id, status, created_at FROM accounts WHERE id = :id")
                .param("id", account.id)
                .query { rs, _ ->
                    StoredIdentity(
                        rs.getObject("owner_id", UUID::class.java),
                        AccountStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant(),
                    )
                }.single()

        // TIMESTAMPTZ holds microseconds and Postgres *rounds* anything finer, so a
        // nanosecond instant would read as divergent from itself on every redelivery.
        val matches =
            stored.ownerId == account.ownerId &&
                stored.status == account.status &&
                stored.createdAt == account.createdAt.truncatedTo(ChronoUnit.MICROS)
        return if (matches) {
            AccountCreationOutcome.AlreadyExists
        } else {
            AccountCreationOutcome.Diverged(stored.ownerId, stored.status, stored.createdAt)
        }
    }

    private data class StoredIdentity(
        val ownerId: UUID,
        val status: AccountStatus,
        val createdAt: Instant,
    )

    private companion object {
        // ON CONFLICT DO NOTHING is what makes at-least-once delivery harmless: the
        // duplicate loses on the primary key instead of raising.
        const val INSERT_IF_ABSENT = """
            INSERT INTO accounts (id, owner_id, status, created_at, balance_cents)
            VALUES (:id, :ownerId, :status, :createdAt, :balanceCents)
            ON CONFLICT (id) DO NOTHING
        """
    }
}
