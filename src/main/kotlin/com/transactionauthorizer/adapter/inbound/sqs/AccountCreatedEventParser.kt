package com.transactionauthorizer.adapter.inbound.sqs

import com.transactionauthorizer.domain.Account
import com.transactionauthorizer.domain.AccountStatus
import com.transactionauthorizer.domain.Money
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
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
        return root.get("account")
            ?: throw MalformedAccountEventException("body has no `account` object")
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
    // describes ISO-8601; accepting both is cheaper than betting on which one arrives.
    private fun JsonNode.createdAt(): Instant {
        val raw = text("created_at")
        raw.toLongOrNull()?.let { return Instant.ofEpochSecond(it) }
        return try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (e: java.time.format.DateTimeParseException) {
            parseAsInstant(raw, e)
        }
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
}
