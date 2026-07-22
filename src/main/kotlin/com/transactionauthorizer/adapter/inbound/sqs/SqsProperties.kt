package com.transactionauthorizer.adapter.inbound.sqs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// The shutdown timeout has to outlast the long poll, or a stopping poller is abandoned
// mid-batch, and stay under Spring's own per-phase timeout of 30s, or Spring cuts first.
private const val LONG_POLL_SECONDS = 20L
private const val SHUTDOWN_TIMEOUT_SECONDS = 25L
private const val BACKOFF_BASE_SECONDS = 1L
private const val BACKOFF_CAP_SECONDS = 30L

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
    // Full-jitter backoff bounds for the poll loop: the first failure waits within
    // [0, backoffBase] and doubles the ceiling per consecutive failure up to backoffCap.
    val backoffBase: Duration = Duration.ofSeconds(BACKOFF_BASE_SECONDS),
    val backoffCap: Duration = Duration.ofSeconds(BACKOFF_CAP_SECONDS),
    val shutdownTimeout: Duration = Duration.ofSeconds(SHUTDOWN_TIMEOUT_SECONDS),
)
