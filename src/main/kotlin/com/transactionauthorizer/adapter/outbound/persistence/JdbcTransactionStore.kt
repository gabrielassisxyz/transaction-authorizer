package com.transactionauthorizer.adapter.outbound.persistence

import com.transactionauthorizer.application.port.AuthorizationCommand
import com.transactionauthorizer.application.port.AuthorizationResult
import com.transactionauthorizer.application.port.TransactionStore
import com.transactionauthorizer.domain.Money
import com.transactionauthorizer.domain.TransactionType
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// The never-negative-balance invariant lives in a single guarded UPDATE, not in Kotlin: no
// balance is read, checked and written back across a gap another thread could slip into.
// The claim insert serializes duplicates before any balance work, and the FOR UPDATE slow
// path runs only on refusal or anomaly, where a consistent balance is worth a lock. Lock
// order is always claims then accounts, so the flow cannot deadlock. Works at READ
// COMMITTED. Design rationale in docs/adr/002, 004 and 006.
@Repository
@Suppress("TooManyFunctions")
class JdbcTransactionStore(
    private val jdbcClient: JdbcClient,
    private val meterRegistry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
) : TransactionStore {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    @Suppress("ReturnCount")
    override fun authorize(command: AuthorizationCommand): AuthorizationResult {
        // Fast paths, no transaction: an id already decided replays its stored result, and a
        // missing account answers without opening a write transaction or claiming the id.
        findStored(command.transactionId)?.let { return reconcile(it, command) }
        if (!accountExists(command.accountId)) {
            recordOutcome("account_not_found", "account_not_found")
            return AuthorizationResult.AccountNotFound
        }

        return try {
            transaction.execute { status -> authorizeGuarded(command, status) }
        } catch (e: DuplicateKeyException) {
            // A concurrent request held the claim; Postgres blocked this insert until that
            // request committed, so its transaction row is readable now. No retry loop.
            findStored(command.transactionId)?.let { reconcile(it, command) }
                ?: throw IllegalStateException("claim conflict on ${command.transactionId} without a stored result", e)
        }
    }

    private fun authorizeGuarded(
        command: AuthorizationCommand,
        status: TransactionStatus,
    ): AuthorizationResult {
        jdbcClient
            .sql(INSERT_CLAIM)
            .param("id", command.transactionId)
            .param("hash", command.requestHash)
            .update()
        val ts = command.timestamp.truncatedTo(ChronoUnit.MICROS)

        val balanceAfter = guardedUpdate(command)
        if (balanceAfter != null) {
            recordOnCommit("approved", "applied")
            return AuthorizationResult.Approved(record(command, "SUCCEEDED", balanceAfter, ts), ts)
        }
        return resolveUnderLock(command, status, ts)
    }

    // Reached only when the guarded update touched no row: the account is disabled, the
    // balance is short, or the credit would overflow. The row is locked so the balance the
    // decision reports cannot drift before it is recorded.
    @Suppress("ReturnCount")
    private fun resolveUnderLock(
        command: AuthorizationCommand,
        status: TransactionStatus,
        ts: Instant,
    ): AuthorizationResult {
        val locked = lockAccount(command.accountId)
        if (locked == null) {
            // The pre-check saw the account, so it can only be gone if it was deleted mid
            // flight, which never happens here. Roll back so the claim leaves no orphan and
            // a later retry can still succeed.
            status.setRollbackOnly()
            recordOutcome("account_not_found", "account_not_found")
            return AuthorizationResult.AccountNotFound
        }
        if (locked.status != ENABLED) {
            return refuse(
                command,
                locked.balanceCents,
                ts,
                "account_disabled",
                "account ${command.accountId} is ${locked.status}",
            )
        }
        val amount = command.amount.cents
        val fits =
            when (command.type) {
                TransactionType.DEBIT -> locked.balanceCents >= amount
                TransactionType.CREDIT -> locked.balanceCents <= Money.MAX.cents - amount
            }
        if (!fits) {
            val (reasonCode, reason) =
                if (command.type == TransactionType.DEBIT) {
                    "insufficient_funds" to "insufficient funds on account ${command.accountId}"
                } else {
                    "credit_overflow" to "credit would overflow the balance of account ${command.accountId}"
                }
            return refuse(command, locked.balanceCents, ts, reasonCode, reason)
        }
        // A concurrent movement freed room between the guarded update and this lock, so the
        // debit or credit is applied under the held lock.
        val delta = if (command.type == TransactionType.DEBIT) -amount else amount
        val balanceAfter =
            jdbcClient
                .sql(APPLY_DELTA)
                .param("delta", delta)
                .param("id", command.accountId)
                .query(Long::class.java)
                .single()
        recordOnCommit("approved", "applied")
        return AuthorizationResult.Approved(record(command, "SUCCEEDED", balanceAfter, ts), ts)
    }

    private fun refuse(
        command: AuthorizationCommand,
        balanceAfter: Long,
        ts: Instant,
        reasonCode: String,
        reason: String,
    ): AuthorizationResult.Refused {
        log.info("transaction {} refused: {}", command.transactionId, reason)
        recordOnCommit("refused", reasonCode)
        return AuthorizationResult.Refused(record(command, "FAILED", balanceAfter, ts), ts)
    }

    private fun reconcile(
        stored: StoredTransaction,
        command: AuthorizationCommand,
    ): AuthorizationResult {
        if (stored.requestHash != command.requestHash) {
            // The first decision stands, but a reused id carrying a different request is
            // evidence of a caller defect, so it does not pass unremarked.
            meterRegistry.counter("transactions.duplicate.payload").increment()
            log.warn(
                "transaction {} replayed with a different payload; keeping the first decision",
                command.transactionId,
            )
        }
        val balance = Money(stored.balanceAfter)
        return if (stored.result == "SUCCEEDED") {
            recordOutcome("approved", "replay")
            AuthorizationResult.Approved(balance, stored.timestamp)
        } else {
            recordOutcome("refused", "replay")
            AuthorizationResult.Refused(balance, stored.timestamp)
        }
    }

    private fun recordOutcome(
        outcome: String,
        reason: String,
    ) = meterRegistry.counter("authorizations", "outcome", outcome, "reason", reason).increment()

    // Outcome counters raised inside the write transaction ride an after-commit hook, so a
    // transaction that rolls back after the guarded update leaves no phantom approval or refusal
    // in the metric. The not-found and replay outcomes count directly, having no commit to await.
    private fun recordOnCommit(
        outcome: String,
        reason: String,
    ) = TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCommit() = recordOutcome(outcome, reason)
        },
    )

    private fun guardedUpdate(command: AuthorizationCommand): Long? =
        when (command.type) {
            TransactionType.DEBIT ->
                jdbcClient
                    .sql(DEBIT_GUARDED)
                    .param("id", command.accountId)
                    .param("amount", command.amount.cents)
            TransactionType.CREDIT ->
                jdbcClient
                    .sql(CREDIT_GUARDED)
                    .param("id", command.accountId)
                    .param("amount", command.amount.cents)
                    .param("maxCents", Money.MAX.cents)
        }.query(Long::class.java).optional().orElse(null)

    private fun lockAccount(accountId: UUID): LockedAccount? =
        jdbcClient
            .sql(LOCK_ACCOUNT)
            .param("id", accountId)
            .query { rs, _ -> LockedAccount(rs.getString("status"), rs.getLong("balance_cents")) }
            .optional()
            .orElse(null)

    private fun record(
        command: AuthorizationCommand,
        result: String,
        balanceAfter: Long,
        ts: Instant,
    ): Money {
        jdbcClient
            .sql(INSERT_TRANSACTION)
            .param("id", command.transactionId)
            .param("accountId", command.accountId)
            .param("type", command.type.name)
            .param("amount", command.amount.cents)
            .param("currency", Money.CURRENCY)
            .param("result", result)
            .param("balanceAfter", balanceAfter)
            .param("createdAt", Timestamp.from(ts))
            .update()
        return Money(balanceAfter)
    }

    private fun accountExists(accountId: UUID): Boolean =
        jdbcClient
            .sql("SELECT 1 FROM accounts WHERE id = :id")
            .param("id", accountId)
            .query(Int::class.java)
            .optional()
            .isPresent

    private fun findStored(transactionId: UUID): StoredTransaction? =
        jdbcClient
            .sql(SELECT_STORED)
            .param("id", transactionId)
            .query { rs, _ ->
                StoredTransaction(
                    rs.getString("result"),
                    rs.getLong("balance_after"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("request_hash"),
                )
            }.optional()
            .orElse(null)

    private data class LockedAccount(
        val status: String,
        val balanceCents: Long,
    )

    private data class StoredTransaction(
        val result: String,
        val balanceAfter: Long,
        val timestamp: Instant,
        val requestHash: String,
    )

    private companion object {
        const val ENABLED = "ENABLED"

        const val INSERT_CLAIM = "INSERT INTO transaction_claims (id, request_hash) VALUES (:id, :hash)"

        const val DEBIT_GUARDED = """
            UPDATE accounts SET balance_cents = balance_cents - :amount
            WHERE id = :id AND status = 'ENABLED' AND balance_cents >= :amount
            RETURNING balance_cents
        """

        const val CREDIT_GUARDED = """
            UPDATE accounts SET balance_cents = balance_cents + :amount
            WHERE id = :id AND status = 'ENABLED' AND balance_cents <= :maxCents - :amount
            RETURNING balance_cents
        """

        const val APPLY_DELTA =
            "UPDATE accounts SET balance_cents = balance_cents + :delta WHERE id = :id RETURNING balance_cents"

        const val LOCK_ACCOUNT = "SELECT status, balance_cents FROM accounts WHERE id = :id FOR UPDATE"

        const val INSERT_TRANSACTION = """
            INSERT INTO transactions (id, account_id, type, amount_cents, currency, result, balance_after, created_at)
            VALUES (:id, :accountId, :type, :amount, :currency, :result, :balanceAfter, :createdAt)
        """

        const val SELECT_STORED = """
            SELECT t.result, t.balance_after, t.created_at, c.request_hash
            FROM transactions t JOIN transaction_claims c ON c.id = t.id
            WHERE t.id = :id
        """
    }
}
