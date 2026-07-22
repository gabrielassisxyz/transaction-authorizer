package com.transactionauthorizer.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.web.servlet.HandlerMapping

class TransactionIdMdcInterceptorTest {
    private val interceptor = TransactionIdMdcInterceptor()
    private val response = mockk<HttpServletResponse>()

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `preHandle puts the path transaction id on the mdc`() {
        val request = requestWithPathVariables(mapOf("transactionId" to "abc-123"))

        interceptor.preHandle(request, response, Any())

        assertThat(MDC.get("transactionId")).isEqualTo("abc-123")
    }

    @Test
    fun `afterCompletion clears the transaction id so a pooled thread does not leak it`() {
        MDC.put("transactionId", "abc-123")

        interceptor.afterCompletion(requestWithPathVariables(emptyMap()), response, Any(), null)

        assertThat(MDC.get("transactionId")).isNull()
    }

    @Test
    fun `preHandle leaves the mdc untouched when the path carries no transaction id`() {
        val request = requestWithPathVariables(emptyMap())

        interceptor.preHandle(request, response, Any())

        assertThat(MDC.get("transactionId")).isNull()
    }

    private fun requestWithPathVariables(variables: Map<String, String>): HttpServletRequest =
        mockk<HttpServletRequest>().apply {
            every { getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) } returns variables
        }
}
