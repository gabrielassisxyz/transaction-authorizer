package com.transactionauthorizer.adapter.inbound.sqs

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import java.util.concurrent.atomic.AtomicInteger

// SQS reachability as its own health component, deliberately outside HTTP readiness: a queue
// outage has to alert without pulling the authorizer from the load balancer, and it must not
// hide inside a green readiness either. The gauge carries the same signal to metrics-based
// alerting, tracking the last probe.
@Component
class SqsHealthIndicator(
    private val sqsClient: SqsClient,
    private val properties: SqsProperties,
    meterRegistry: MeterRegistry,
) : HealthIndicator {
    private val reachable = AtomicInteger(0)

    init {
        meterRegistry.gauge("sqs.connectivity", reachable) { it.get().toDouble() }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun health(): Health =
        try {
            sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(properties.queueName).build())
            reachable.set(1)
            Health.up().withDetail("queue", properties.queueName).build()
        } catch (e: Exception) {
            reachable.set(0)
            Health.down(e).withDetail("queue", properties.queueName).build()
        }
}
