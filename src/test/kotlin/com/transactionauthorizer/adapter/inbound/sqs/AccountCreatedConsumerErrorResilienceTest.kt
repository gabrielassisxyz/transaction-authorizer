package com.transactionauthorizer.adapter.inbound.sqs

import com.transactionauthorizer.application.CreateAccountService
import com.transactionauthorizer.domain.Account
import com.transactionauthorizer.domain.AccountStatus
import com.transactionauthorizer.domain.Money
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// An Error raised while handling a message must not retire the poller: the executor never
// replaces a thread that ended, so the loop has to absorb it, back off and keep receiving.
class AccountCreatedConsumerErrorResilienceTest {
    private val sqsClient = mockk<SqsClient>()
    private val parser = mockk<AccountCreatedEventParser>()
    private val createAccountService = mockk<CreateAccountService>()
    private val meterRegistry = SimpleMeterRegistry()
    private val properties =
        SqsProperties(
            pollers = 1,
            waitTime = Duration.ofMillis(10),
            backoffBase = Duration.ofMillis(1),
            backoffCap = Duration.ofMillis(2),
        )

    private val consumer =
        AccountCreatedConsumer(sqsClient, properties, parser, createAccountService, meterRegistry)

    @Test
    fun `an Error while handling keeps the poller alive and does not ack the message`() {
        val receiveCalls = AtomicInteger(0)
        val keptPolling = CountDownLatch(3)
        every { sqsClient.getQueueUrl(any<GetQueueUrlRequest>()) } returns
            GetQueueUrlResponse.builder().queueUrl("http://queue").build()
        every { sqsClient.receiveMessage(any<ReceiveMessageRequest>()) } answers {
            receiveCalls.incrementAndGet()
            keptPolling.countDown()
            oneMessage()
        }
        every { parser.parse(any()) } returns anAccount()
        every { createAccountService.create(any()) } throws NoClassDefFoundError("boom")

        consumer.start()
        val survived = keptPolling.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        consumer.stop()

        assertThat(survived).isTrue()
        assertThat(receiveCalls.get()).isGreaterThanOrEqualTo(3)
        verify(exactly = 0) { sqsClient.deleteMessage(any<DeleteMessageRequest>()) }
        assertThat(meterRegistry.counter("sqs.messages", "outcome", "handle_failed").count())
            .isGreaterThanOrEqualTo(1.0)
    }

    private fun oneMessage(): ReceiveMessageResponse =
        ReceiveMessageResponse
            .builder()
            .messages(
                Message
                    .builder()
                    .messageId("m-1")
                    .receiptHandle("r-1")
                    .body("{}")
                    .build(),
            ).build()

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
