package com.transactionauthorizer.adapter.inbound.sqs

import com.transactionauthorizer.domain.AccountStatus
import com.transactionauthorizer.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class AccountCreatedEventParserTest {
    private val parser = AccountCreatedEventParser(JsonMapper.builder().build())

    private val accountId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()

    @Test
    fun `reads the event the producer actually sends`() {
        val account = parser.parse(event(createdAt = "1751000000"))

        assertThat(account.id).isEqualTo(accountId)
        assertThat(account.ownerId).isEqualTo(ownerId)
        assertThat(account.status).isEqualTo(AccountStatus.ENABLED)
        assertThat(account.createdAt).isEqualTo(Instant.ofEpochSecond(1751000000))
        assertThat(account.balance).isEqualTo(Money.ZERO)
    }

    @ParameterizedTest
    @ValueSource(strings = ["2026-07-21T04:53:20Z", "2026-07-21T01:53:20-03:00"])
    fun `also reads the iso-8601 timestamp the written contract describes`(createdAt: String) {
        assertThat(parser.parse(event(createdAt = createdAt)).createdAt)
            .isEqualTo(Instant.parse("2026-07-21T04:53:20Z"))
    }

    @Test
    fun `an account always arrives with a zero balance`() {
        assertThat(parser.parse(event(createdAt = "1751000000")).balance).isEqualTo(Money.ZERO)
    }

    @Test
    fun `also reads a timestamp sent as a bare json number`() {
        val body = """{"account":{"id":"$accountId","owner":"$ownerId","created_at":1751000000,"status":"ENABLED"}}"""

        assertThat(parser.parse(body).createdAt).isEqualTo(Instant.ofEpochSecond(1751000000))
    }

    @Test
    fun `rejects a body that is not json`() {
        assertThatThrownBy { parser.parse("not json at all") }
            .isInstanceOf(MalformedAccountEventException::class.java)
            .hasMessageContaining("valid JSON")
    }

    // These parse into perfectly valid JSON that is still not an event. Left unchecked
    // they surface as a NullPointerException and get misread as a transient fault.
    @ParameterizedTest
    @ValueSource(strings = ["""{"transaction":{}}""", "null", "[]", """{"account":null}""", """{"account":7}"""])
    fun `rejects a body without the account object`(body: String) {
        assertThatThrownBy { parser.parse(body) }
            .isInstanceOf(MalformedAccountEventException::class.java)
            .hasMessageContaining("`account`")
    }

    @Test
    fun `rejects a timestamp outside the representable range`() {
        assertThatThrownBy { parser.parse(event(createdAt = "9223372036854775807")) }
            .isInstanceOf(MalformedAccountEventException::class.java)
            .hasMessageContaining("`created_at`")
    }

    @Test
    fun `rejects a missing field`() {
        assertThatThrownBy { parser.parse("""{"account":{"id":"$accountId"}}""") }
            .isInstanceOf(MalformedAccountEventException::class.java)
            .hasMessageContaining("`owner`")
    }

    @Test
    fun `rejects an identifier that is not a uuid`() {
        assertThatThrownBy { parser.parse(event(id = "42", createdAt = "1751000000")) }
            .isInstanceOf(MalformedAccountEventException::class.java)
            .hasMessageContaining("`id`")
    }

    @Test
    fun `rejects an unknown status`() {
        assertThatThrownBy { parser.parse(event(status = "FROZEN", createdAt = "1751000000")) }
            .isInstanceOf(MalformedAccountEventException::class.java)
            .hasMessageContaining("`status`")
    }

    @Test
    fun `rejects a timestamp in neither accepted shape`() {
        assertThatThrownBy { parser.parse(event(createdAt = "yesterday")) }
            .isInstanceOf(MalformedAccountEventException::class.java)
            .hasMessageContaining("`created_at`")
    }

    @Test
    fun `rejects a field that is not a string`() {
        assertThatThrownBy { parser.parse("""{"account":{"id":$accountId}}""") }
            .isInstanceOf(MalformedAccountEventException::class.java)
    }

    private fun event(
        id: String = accountId.toString(),
        owner: String = ownerId.toString(),
        status: String = "ENABLED",
        createdAt: String,
    ) = """{"account":{"id":"$id","owner":"$owner","created_at":"$createdAt","status":"$status"}}"""
}
