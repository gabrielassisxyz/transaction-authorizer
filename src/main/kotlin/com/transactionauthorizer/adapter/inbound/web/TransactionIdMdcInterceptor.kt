package com.transactionauthorizer.adapter.inbound.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

// Puts the path's transactionId on the MDC for the whole request, so every line it produces,
// the refusal log and the ProblemDetail handler included, is greppable by that single id.
class TransactionIdMdcInterceptor : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        pathVariable(request, TRANSACTION_ID)?.let { MDC.put(TRANSACTION_ID, it) }
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        MDC.remove(TRANSACTION_ID)
    }

    private fun pathVariable(
        request: HttpServletRequest,
        name: String,
    ): String? {
        @Suppress("UNCHECKED_CAST")
        val variables =
            request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
        return variables?.get(name)
    }

    companion object {
        const val TRANSACTION_ID = "transactionId"
    }
}
