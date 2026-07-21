package com.transactionauthorizer.adapter.inbound.sqs

import com.transactionauthorizer.domain.Account
import com.transactionauthorizer.domain.AccountStatus
import com.transactionauthorizer.domain.Money
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class MalformedAccountEventException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Component
class AccountCreatedEventParser(
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun parse(body: String): Account {
        val account = readAccountNode(body)
        return Account(
            id = account.uuid("id"),
            ownerId = account.uuid("owner"),
            status = account.status(),
            createdAt = account.createdAt(),
            balance = Money.ZERO,
        )
    }

    private fun readAccountNode(body: String): JsonNode {
        val root =
            try {
                objectMapper.readTree(body)
            } catch (e: tools.jackson.core.JacksonException) {
                throw MalformedAccountEventException("body is not valid JSON", e)
            }
        // A body of `null` or `[]` parses fine and is still not an event. Without this
        // check the failure would surface as a NullPointerException further down and be
        // classified as a transient fault instead of poison.
        val account = root?.get("account")
        if (account == null || !account.isObject) {
            throw MalformedAccountEventException("body has no `account` object")
        }
        return account
    }

    private fun JsonNode.text(field: String): String =
        get(field)?.takeIf { it.isString }?.stringValue()
            ?: throw MalformedAccountEventException("field `$field` is missing or is not a string")

    private fun JsonNode.uuid(field: String): UUID =
        try {
            UUID.fromString(text(field))
        } catch (e: IllegalArgumentException) {
            throw MalformedAccountEventException("field `$field` is not a UUID", e)
        }

    private fun JsonNode.status(): AccountStatus =
        try {
            AccountStatus.valueOf(text("status"))
        } catch (e: IllegalArgumentException) {
            throw MalformedAccountEventException("field `status` is not a known account status", e)
        }

    // The producer sends epoch seconds as a JSON string while the written contract
    // describes ISO-8601. Accepting both, plus the bare JSON number a third producer
    // would send, is cheaper than betting on which one arrives.
    private fun JsonNode.createdAt(): Instant {
        val node =
            get("created_at")
                ?: throw MalformedAccountEventException("field `created_at` is missing")
        val parsed =
            if (node.isIntegralNumber) {
                ofEpochSeconds(node.longValue())
            } else {
                val raw = text("created_at")
                raw.toLongOrNull()?.let(::ofEpochSeconds) ?: parseAsTimestamp(raw)
            }
        return parsed.also(::rejectFutureInstant)
    }

    // An account-opening event describes something that already happened, so a creation
    // instant in the future is not a plausible reading of any of the three accepted
    // formats: it is epoch milliseconds arriving where epoch seconds were promised, and
    // a thousandfold error would otherwise be stored as a date in the year 57488 without
    // a word. The tolerance is there because two machines never agree on the second, and
    // a rejection under normal clock drift would dead-letter valid events.
    private fun rejectFutureInstant(instant: Instant) {
        val horizon = clock.instant().plus(CLOCK_SKEW_TOLERANCE)
        if (instant.isAfter(horizon)) {
            throw MalformedAccountEventException(
                "field `created_at` is in the future ($instant); epoch milliseconds where seconds are expected?",
            )
        }
    }

    private fun parseAsTimestamp(raw: String): Instant =
        try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (e: java.time.format.DateTimeParseException) {
            parseAsInstant(raw, e)
        }

    private fun ofEpochSeconds(seconds: Long): Instant =
        try {
            Instant.ofEpochSecond(seconds)
        } catch (e: java.time.DateTimeException) {
            throw MalformedAccountEventException("field `created_at` is out of range", e)
        }

    private fun parseAsInstant(
        raw: String,
        offsetFailure: Exception,
    ): Instant =
        try {
            Instant.parse(raw)
        } catch (e: java.time.format.DateTimeParseException) {
            e.addSuppressed(offsetFailure)
            throw MalformedAccountEventException("field `created_at` is neither epoch seconds nor ISO-8601", e)
        }

    private companion object {
        val CLOCK_SKEW_TOLERANCE: Duration = Duration.ofMinutes(5)
    }
}
