package com.transactionauthorizer

import com.transactionauthorizer.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

// Pins the observability contract: with logstash structured logging active, every console
// line is JSON and the MDC correlation keys of both paths ride along as top-level fields.
@ExtendWith(OutputCaptureExtension::class)
class StructuredLoggingTest : PostgresIntegrationTest() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    @Test
    fun `each console line is json carrying the correlation keys of both paths`(output: CapturedOutput) {
        val marker = "structured-logging-probe-${UUID.randomUUID()}"
        MDC.put("transactionId", "tx-probe")
        MDC.put("messageId", "msg-probe")
        try {
            log.info(marker)
        } finally {
            MDC.clear()
        }

        val line = output.all.lineSequence().first { it.contains(marker) }
        val parsed = json.readTree(line)
        assertThat(parsed.path("message").asString()).isEqualTo(marker)
        assertThat(parsed.path("transactionId").asString()).isEqualTo("tx-probe")
        assertThat(parsed.path("messageId").asString()).isEqualTo("msg-probe")
    }
}
