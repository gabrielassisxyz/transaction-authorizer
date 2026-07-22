package com.transactionauthorizer.adapter.inbound.sqs

import com.transactionauthorizer.application.CreateAccountService
import com.transactionauthorizer.application.port.AccountCreationOutcome
import com.transactionauthorizer.domain.Account
import com.transactionauthorizer.domain.AccountStatus
import com.transactionauthorizer.domain.Money
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// The poll cycle stops at the first message that fails transiently, which is safe only because a
// poison message never counts as a transient failure: it is dealt with and the batch goes on. A
// poison message in the middle of a batch must not stop the messages after it from being acked.
class AccountCreatedConsumerPoisonBatchTest {
    private val sqsClient = mockk<SqsClient>()
    private val parser = mockk<AccountCreatedEventParser>()
    private val createAccountService = mockk<CreateAccountService>()
    private val meterRegistry = SimpleMeterRegistry()
    private val properties = SqsProperties(pollers = 1, waitTime = Duration.ofMillis(10))

    private val consumer =
        AccountCreatedConsumer(sqsClient, properties, parser, createAccountService, meterRegistry)

    @Test
    fun `a poison message in the middle of a batch does not strand the messages behind it`() {
        val bothGoodProcessed = CountDownLatch(2)
        val acked = java.util.Collections.synchronizedList(mutableListOf<String>())
        every { sqsClient.getQueueUrl(any<GetQueueUrlRequest>()) } returns
            GetQueueUrlResponse.builder().queueUrl("http://queue").build()
        every { sqsClient.receiveMessage(any<ReceiveMessageRequest>()) } answers { nextBatch() }
        every { sqsClient.deleteMessage(any<DeleteMessageRequest>()) } answers {
            acked += firstArg<DeleteMessageRequest>().receiptHandle()
            bothGoodProcessed.countDown()
            DeleteMessageResponse.builder().build()
        }
        every { parser.parse("poison") } throws MalformedAccountEventException("unparseable")
        every { parser.parse(match { it != "poison" }) } returns anAccount()
        every { createAccountService.create(any()) } returns AccountCreationOutcome.Created

        consumer.start()
        val processed = bothGoodProcessed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        consumer.stop()

        assertThat(processed).isTrue()
        assertThat(acked).containsExactlyInAnyOrder("r-good-1", "r-good-2").doesNotContain("r-poison")
        assertThat(meterRegistry.counter("sqs.messages", "outcome", "poison").count()).isEqualTo(1.0)
    }

    private val delivered = AtomicBoolean(false)

    private fun nextBatch(): ReceiveMessageResponse {
        val messages =
            if (delivered.compareAndSet(false, true)) {
                listOf(
                    message("good-1"),
                    message("poison"),
                    message("good-2"),
                )
            } else {
                emptyList()
            }
        return ReceiveMessageResponse.builder().messages(messages).build()
    }

    private fun message(name: String): Message =
        Message
            .builder()
            .messageId("m-$name")
            .receiptHandle("r-$name")
            .body(name)
            .build()

    private fun anAccount() =
        Account(
            id = java.util.UUID.randomUUID(),
            ownerId = java.util.UUID.randomUUID(),
            status = AccountStatus.ENABLED,
            createdAt = Instant.parse("2020-01-01T00:00:00Z"),
            balance = Money.ZERO,
        )

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
