package com.transactionauthorizer.adapter.inbound.sqs

import com.transactionauthorizer.application.CreateAccountService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
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
        // A poller can be parked in a long poll for up to `waitTime`, so the timeout
        // has to outlast it; cutting it short would abandon messages already received
        // but not yet committed, which is exactly how a deploy manufactures duplicates.
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
            while (running.get()) {
                pollOnce()
            }
        } finally {
            stopped.countDown()
        }
    }

    // The loop is the last line of defence for the consumer: any escaping throwable
    // would silently retire this poller for the lifetime of the process.
    @Suppress("TooGenericExceptionCaught")
    private fun pollOnce() {
        try {
            receive().forEach(::handle)
        } catch (e: Exception) {
            meterRegistry.counter("sqs.messages", "outcome", "receive_failed").increment()
            log.error("failed to receive from queue {}", properties.queueName, e)
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

    @Suppress("TooGenericExceptionCaught")
    private fun handle(message: Message) {
        try {
            createAccountService.create(parser.parse(message.body()))
            delete(message)
            meterRegistry.counter("sqs.messages", "outcome", "processed").increment()
        } catch (e: MalformedAccountEventException) {
            // Poison messages are deliberately not deleted: the redrive policy is the
            // retry budget, and deleting here would destroy the evidence instead of
            // moving it to the dead-letter queue.
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

    // Deleting per message, right after its own database work commits, is what keeps a
    // failure on one message from un-acking the ones already done in the same batch.
    private fun delete(message: Message) {
        sqsClient.deleteMessage(
            DeleteMessageRequest
                .builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build(),
        )
    }

    private fun sleepBeforeRetry() {
        try {
            Thread.sleep(properties.retryDelay.toMillis())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            running.set(false)
        }
    }

    private val queueUrl: String by lazy {
        sqsClient
            .getQueueUrl(GetQueueUrlRequest.builder().queueName(properties.queueName).build())
            .queueUrl()
    }
}
