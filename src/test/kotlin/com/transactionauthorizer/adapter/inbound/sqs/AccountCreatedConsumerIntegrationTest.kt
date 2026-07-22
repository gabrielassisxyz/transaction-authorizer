package com.transactionauthorizer.adapter.inbound.sqs

import com.transactionauthorizer.support.SqsIntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration
import java.util.UUID

class AccountCreatedConsumerIntegrationTest : SqsIntegrationTest() {
    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Test
    fun `an account creation event becomes an account with a zero balance`() {
        val accountId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()

        send(event(accountId, ownerId))

        await().atMost(TIMEOUT).untilAsserted {
            assertThat(storedOwnerOf(accountId)).isEqualTo(ownerId)
            assertThat(storedBalanceOf(accountId)).isZero()
        }
    }

    @Test
    fun `a redelivered event creates the account exactly once`() {
        val accountId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val duplicatesBefore = counter("accounts.duplicate")

        send(event(accountId, ownerId))
        send(event(accountId, ownerId))

        await().atMost(TIMEOUT).untilAsserted {
            assertThat(counter("accounts.duplicate")).isGreaterThan(duplicatesBefore)
        }
        assertThat(rowCountOf(accountId)).isEqualTo(1)
        assertThat(storedOwnerOf(accountId)).isEqualTo(ownerId)
    }

    @Test
    fun `an event contradicting the stored account keeps the first write and flags the anomaly`() {
        val accountId = UUID.randomUUID()
        val firstOwner = UUID.randomUUID()
        val conflictsBefore = counter("accounts.conflict")

        send(event(accountId, firstOwner))
        await().atMost(TIMEOUT).untilAsserted { assertThat(rowCountOf(accountId)).isEqualTo(1) }

        send(event(accountId, UUID.randomUUID(), status = "DISABLED"))

        await().atMost(TIMEOUT).untilAsserted {
            assertThat(counter("accounts.conflict")).isGreaterThan(conflictsBefore)
        }
        assertThat(storedOwnerOf(accountId)).isEqualTo(firstOwner)
        assertThat(storedStatusOf(accountId)).isEqualTo("ENABLED")
    }

    @Test
    fun `an unparseable message ends up in the dead-letter queue and counts as poison`() {
        val poisonBefore = poisonCount()
        send("""{"account":{"id":"not-a-uuid"}}""")

        await().atMost(TIMEOUT).untilAsserted {
            assertThat(deadLetterQueueDepth()).isPositive()
            assertThat(poisonCount()).isGreaterThan(poisonBefore)
        }

        val accountId = UUID.randomUUID()
        send(event(accountId, UUID.randomUUID()))
        await().atMost(TIMEOUT).untilAsserted { assertThat(rowCountOf(accountId)).isEqualTo(1) }
    }

    @Test
    fun `an event whose created_at is iso-8601 still becomes an account`() {
        val accountId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()

        send(
            """{"account":{"id":"$accountId","owner":"$ownerId",""" +
                """"created_at":"2020-01-02T03:04:05Z","status":"ENABLED"}}""",
        )

        await().atMost(TIMEOUT).untilAsserted { assertThat(rowCountOf(accountId)).isEqualTo(1) }
    }

    private fun send(body: String) {
        sqsClient.sendMessage { it.queueUrl(queueUrl).messageBody(body) }
    }

    private fun event(
        accountId: UUID,
        ownerId: UUID,
        status: String = "ENABLED",
    ) = """{"account":{"id":"$accountId","owner":"$ownerId","created_at":"1751000000","status":"$status"}}"""

    private fun deadLetterQueueDepth(): Int =
        sqsClient
            .getQueueAttributes {
                it
                    .queueUrl(deadLetterQueueUrl)
                    .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
            }.attributes()[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]
            ?.toInt() ?: 0

    private fun rowCountOf(accountId: UUID): Int =
        jdbcClient
            .sql("SELECT count(*) FROM accounts WHERE id = :id")
            .param("id", accountId)
            .query(Int::class.java)
            .single()

    private fun storedOwnerOf(accountId: UUID): UUID? =
        jdbcClient
            .sql("SELECT owner_id FROM accounts WHERE id = :id")
            .param("id", accountId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    private fun storedStatusOf(accountId: UUID): String =
        jdbcClient
            .sql("SELECT status FROM accounts WHERE id = :id")
            .param("id", accountId)
            .query(String::class.java)
            .single()

    private fun storedBalanceOf(accountId: UUID): Long =
        jdbcClient
            .sql("SELECT balance_cents FROM accounts WHERE id = :id")
            .param("id", accountId)
            .query(Long::class.java)
            .single()

    private fun counter(name: String): Double = meterRegistry.counter(name).count()

    private fun poisonCount(): Double = meterRegistry.counter("sqs.messages", "outcome", "poison").count()

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
