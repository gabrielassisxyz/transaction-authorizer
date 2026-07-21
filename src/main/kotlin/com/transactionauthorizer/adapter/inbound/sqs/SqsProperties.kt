package com.transactionauthorizer.adapter.inbound.sqs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// The long-poll wait is the SQS maximum: fewer empty receives, fewer API calls, and a
// message in flight is still picked up immediately. The shutdown timeout has to outlast
// it, or a stopping poller is abandoned mid-batch.
private const val LONG_POLL_SECONDS = 20L
private const val SHUTDOWN_TIMEOUT_SECONDS = 30L

@ConfigurationProperties(prefix = "sqs")
data class SqsProperties(
    val region: String = "sa-east-1",
    val endpoint: String? = null,
    val accessKey: String? = null,
    val secretKey: String? = null,
    val queueName: String = "conta-bancaria-criada",
    val pollers: Int = 2,
    val batchSize: Int = 10,
    val waitTime: Duration = Duration.ofSeconds(LONG_POLL_SECONDS),
    val retryDelay: Duration = Duration.ofSeconds(2),
    val shutdownTimeout: Duration = Duration.ofSeconds(SHUTDOWN_TIMEOUT_SECONDS),
)
