package com.transactionauthorizer.adapter.inbound.web

import com.transactionauthorizer.application.AuthorizeTransactionService
import com.transactionauthorizer.application.port.AuthorizationResult
import com.transactionauthorizer.domain.Money
import com.transactionauthorizer.domain.TransactionType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@RestController
class AuthorizeTransactionController(
    private val service: AuthorizeTransactionService,
) {
    @PostMapping("/transactions/{transactionId}")
    fun authorize(
        @PathVariable transactionId: UUID,
        @RequestBody request: AuthorizeTransactionRequest,
    ): AuthorizeTransactionResponse {
        // BigDecimal is validated and converted to integer cents here, at the edge, so it
        // never reaches the domain. A refusal is a decision, not an error, so both outcomes
        // return 200; only a missing account, malformed input or an unsupported currency
        // leave through the ProblemDetail handler.
        val amount = amountFrom(request.amount)
        // The decision, never the incoming request, is the only source for the response
        // below: on a replay this is the originally claimed transaction, which can differ
        // from the request that reused the id.
        val decision =
            when (val result = service.authorize(transactionId, request.accountId, request.type, amount)) {
                is AuthorizationResult.Approved -> decisionOf(TransactionStatus.SUCCEEDED, result)
                is AuthorizationResult.Refused -> decisionOf(TransactionStatus.FAILED, result)
                AuthorizationResult.AccountNotFound ->
                    throw AccountNotFoundException(request.accountId)
            }
        return response(transactionId, decision)
    }

    private fun decisionOf(
        status: TransactionStatus,
        result: AuthorizationResult.Approved,
    ) = Decision(status, result.accountId, result.type, result.amount, result.balanceAfter, result.timestamp)

    private fun decisionOf(
        status: TransactionStatus,
        result: AuthorizationResult.Refused,
    ) = Decision(status, result.accountId, result.type, result.amount, result.balanceAfter, result.timestamp)

    private fun amountFrom(payload: MoneyPayload): Money {
        if (payload.currency != Money.CURRENCY) throw UnsupportedCurrencyException(payload.currency)
        if (payload.value.signum() <= 0) throw InvalidAmountException("amount must be greater than zero")
        return toCents(payload.value)
    }

    private fun toCents(value: BigDecimal): Money =
        try {
            Money.ofDecimal(value)
        } catch (e: ArithmeticException) {
            throw AmountOutOfRangeException(value, e)
        } catch (e: IllegalArgumentException) {
            throw InvalidAmountException("amount has more than ${Money.SCALE} decimal places", e)
        }

    private fun response(
        transactionId: UUID,
        decision: Decision,
    ) = AuthorizeTransactionResponse(
        transaction =
            TransactionView(
                id = transactionId,
                type = decision.type,
                amount = MoneyPayload(decision.amount.toBigDecimal(), Money.CURRENCY),
                status = decision.status,
                timestamp = decision.timestamp,
            ),
        account =
            AccountView(
                id = decision.accountId,
                balance = MoneyPayload(decision.balanceAfter.toBigDecimal(), Money.CURRENCY),
            ),
    )

    private data class Decision(
        val status: TransactionStatus,
        val accountId: UUID,
        val type: TransactionType,
        val amount: Money,
        val balanceAfter: Money,
        val timestamp: Instant,
    )
}
