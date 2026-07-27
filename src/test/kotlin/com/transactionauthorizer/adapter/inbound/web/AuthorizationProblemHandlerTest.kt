package com.transactionauthorizer.adapter.inbound.web

import com.transactionauthorizer.application.port.AuthorizationUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.time.Duration

class AuthorizationProblemHandlerTest {
    private val handler = AuthorizationProblemHandler()

    @Test
    fun `an unavailable store answers 503 and tells the client when to come back`() {
        val response = handler.onStoreUnavailable(AuthorizationUnavailableException(Duration.ofSeconds(5)))

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5")
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        // Never a refusal: a refusal is a persisted decision carrying a transaction id and a
        // resulting balance, and neither exists when the store was never reached.
        assertThat(response.body?.detail).contains("no decision was taken")
    }
}
