package com.transactionauthorizer.adapter.inbound.sqs

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException

class SqsHealthIndicatorTest {
    private val sqsClient = mockk<SqsClient>()
    private val properties = SqsProperties(queueName = "q")
    private val meterRegistry = SimpleMeterRegistry()
    private val indicator = SqsHealthIndicator(sqsClient, properties, meterRegistry)

    @Test
    fun `reports up and drives the gauge to one when the queue is reachable`() {
        every { sqsClient.getQueueUrl(any<GetQueueUrlRequest>()) } returns
            GetQueueUrlResponse.builder().queueUrl("http://q").build()

        val health = indicator.health()

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(connectivityGauge()).isEqualTo(1.0)
    }

    @Test
    fun `reports down and drives the gauge to zero when the queue is unreachable`() {
        every { sqsClient.getQueueUrl(any<GetQueueUrlRequest>()) } throws
            QueueDoesNotExistException.builder().message("nope").build()

        val health = indicator.health()

        assertThat(health.status).isEqualTo(Status.DOWN)
        assertThat(connectivityGauge()).isEqualTo(0.0)
    }

    private fun connectivityGauge(): Double = meterRegistry.get("sqs.connectivity").gauge().value()
}
