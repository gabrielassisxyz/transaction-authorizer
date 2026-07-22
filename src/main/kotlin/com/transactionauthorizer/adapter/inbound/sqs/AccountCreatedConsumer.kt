package com.transactionauthorizer.adapter.inbound.sqs

import com.transactionauthorizer.application.CreateAccountService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Component
class AccountCreatedConsumer(
    private val sqsClient: SqsClient,
    private val properties: SqsProperties,
    private val parser: AccountCreatedEventParser,
    private val createAccountService: CreateAccountService,
    private val meterRegistry: MeterRegistry,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private var stopped = CountDownLatch(0)

    // Built per start, never once per bean: a stopped executor rejects every task, so a
    // context that is stopped and started again would come back with silent pollers.
    private var executor: ExecutorService? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        stopped = CountDownLatch(properties.pollers)
        executor =
            Executors.newVirtualThreadPerTaskExecutor().also { pool ->
                repeat(properties.pollers) { pool.execute(::poll) }
            }
        log.info("started {} sqs pollers on queue {}", properties.pollers, properties.queueName)
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        // A poller sits in a long poll for up to `waitTime`, so the timeout has to
        // outlast it: cutting it short abandons received-but-uncommitted messages.
        val drained = stopped.await(properties.shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!drained) {
            log.warn("sqs pollers did not finish within {}", properties.shutdownTimeout)
        }
        executor?.shutdownNow()
        executor = null
    }

    override fun isRunning(): Boolean = running.get()

    private fun poll() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                pollOnce()
            }
        } finally {
            stopped.countDown()
        }
    }

    // Throwable, not Exception: the executor never replaces a thread that ended, so one
    // `NoClassDefFoundError` would retire this poller while `isRunning` still says true.
    @Suppress("TooGenericExceptionCaught")
    private fun pollOnce() {
        try {
            receive().forEach(::handle)
        } catch (t: Throwable) {
            meterRegistry.counter("sqs.messages", "outcome", "receive_failed").increment()
            log.error("failed to receive from queue {}", properties.queueName, t)
            sleepBeforeRetry()
        }
    }

    private fun receive(): List<Message> {
        val request =
            ReceiveMessageRequest
                .builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(properties.batchSize)
                .waitTimeSeconds(properties.waitTime.toSeconds().toInt())
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .build()
        return sqsClient.receiveMessage(request).messages()
    }

    private fun handle(message: Message) {
        MDC.put(MESSAGE_ID, message.messageId())
        message
            .attributes()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT]
            ?.let { MDC.put(RECEIVE_COUNT, it) }
        try {
            process(message)
        } finally {
            MDC.remove(MESSAGE_ID)
            MDC.remove(RECEIVE_COUNT)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun process(message: Message) {
        try {
            createAccountService.create(parser.parse(message.body()))
            delete(message)
            meterRegistry.counter("sqs.messages", "outcome", "processed").increment()
        } catch (e: MalformedAccountEventException) {
            // Not deleted on purpose: the redrive policy is the retry budget, so the
            // message has to reach the dead-letter queue instead of dying here.
            meterRegistry.counter("sqs.messages", "outcome", "poison").increment()
            log.warn(
                "discarding unparseable message {} to redrive after {} receives: {}",
                message.messageId(),
                message.attributes()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT],
                e.message,
            )
        } catch (e: Exception) {
            meterRegistry.counter("sqs.messages", "outcome", "failed").increment()
            log.error("failed to process message {}", message.messageId(), e)
            sleepBeforeRetry()
        }
    }

    private fun delete(message: Message) {
        sqsClient.deleteMessage(
            DeleteMessageRequest
                .builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build(),
        )
    }

    // Must not clear `running`, which is shared: restoring the interrupt flag stops
    // this poller alone, through its own loop condition.
    private fun sleepBeforeRetry() {
        try {
            Thread.sleep(properties.retryDelay.toMillis())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private val queueUrl: String by lazy {
        sqsClient
            .getQueueUrl(GetQueueUrlRequest.builder().queueName(properties.queueName).build())
            .queueUrl()
    }

    private companion object {
        const val MESSAGE_ID = "messageId"
        const val RECEIVE_COUNT = "receiveCount"
    }
}
