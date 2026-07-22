package com.transactionauthorizer.adapter.inbound.sqs

import com.transactionauthorizer.application.CreateAccountService
import com.transactionauthorizer.application.port.AccountCreationOutcome
import com.transactionauthorizer.domain.Account
import com.transactionauthorizer.domain.AccountStatus
import com.transactionauthorizer.domain.Money
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

// The graceful-shutdown contract, proved by controlling the poll/commit boundary instead of
// sleeping: with a message in flight, stop() must block until that message finishes and is
// acked, delete exactly once and never process it twice. Deterministic by construction: the
// in-flight create is pinned on a latch the test releases, so nothing here races on timing.
class AccountCreatedConsumerShutdownTest {
    private val processingStarted = CountDownLatch(1)
    private val proceed = CountDownLatch(1)
    private val delivered = AtomicBoolean(false)

    private val sqsClient = mockk<SqsClient>()
    private val parser = mockk<AccountCreatedEventParser>()
    private val createAccountService = mockk<CreateAccountService>()
    private val properties = SqsProperties(pollers = 1, waitTime = Duration.ofMillis(50))

    private val consumer =
        AccountCreatedConsumer(sqsClient, properties, parser, createAccountService, SimpleMeterRegistry())

    @Test
    fun `stop drains the in-flight message before returning and acks it exactly once`() {
        every { sqsClient.getQueueUrl(any<GetQueueUrlRequest>()) } returns
            GetQueueUrlResponse.builder().queueUrl("http://queue").build()
        every { sqsClient.receiveMessage(any<ReceiveMessageRequest>()) } answers { nextBatch() }
        every { sqsClient.deleteMessage(any<DeleteMessageRequest>()) } returns DeleteMessageResponse.builder().build()
        every { parser.parse(any()) } returns anAccount()
        every { createAccountService.create(any()) } answers {
            processingStarted.countDown()
            proceed.await()
            AccountCreationOutcome.Created
        }

        consumer.start()
        assertThat(processingStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()

        val stopping = Executors.newSingleThreadExecutor()
        val stopped = stopping.submit { consumer.stop() }

        // While the in-flight message is pinned, stop() cannot return: it is draining.
        assertThatThrownBy { stopped.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS) }
            .isInstanceOf(TimeoutException::class.java)

        proceed.countDown()
        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        stopping.shutdownNow()

        verify(exactly = 1) { createAccountService.create(any()) }
        verify(exactly = 1) { sqsClient.deleteMessage(any<DeleteMessageRequest>()) }
    }

    // One message on the first receive, empty afterwards, so the poller cannot pick up a
    // second copy and the exactly-once assertions stay meaningful.
    private fun nextBatch(): ReceiveMessageResponse {
        val messages =
            if (delivered.compareAndSet(false, true)) {
                listOf(
                    Message
                        .builder()
                        .messageId("m-1")
                        .receiptHandle("r-1")
                        .body("{}")
                        .build(),
                )
            } else {
                emptyList()
            }
        return ReceiveMessageResponse.builder().messages(messages).build()
    }

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
        const val BLOCKED_MILLIS = 300L
    }
}
