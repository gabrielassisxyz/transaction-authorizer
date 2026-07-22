package com.transactionauthorizer.adapter.inbound.web

import com.transactionauthorizer.application.AuthorizeTransactionService
import com.transactionauthorizer.application.port.AuthorizationResult
import com.transactionauthorizer.domain.Money
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
        val decision =
            when (val result = service.authorize(transactionId, request.accountId, request.type, amount)) {
                is AuthorizationResult.Approved ->
                    Decision(TransactionStatus.SUCCEEDED, result.balanceAfter, result.timestamp)
                is AuthorizationResult.Refused ->
                    Decision(TransactionStatus.FAILED, result.balanceAfter, result.timestamp)
                AuthorizationResult.AccountNotFound ->
                    throw AccountNotFoundException(request.accountId)
            }
        return response(transactionId, request, amount, decision)
    }

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
        request: AuthorizeTransactionRequest,
        amount: Money,
        decision: Decision,
    ) = AuthorizeTransactionResponse(
        transaction =
            TransactionView(
                id = transactionId,
                type = request.type,
                amount = MoneyPayload(amount.toBigDecimal(), Money.CURRENCY),
                status = decision.status,
                timestamp = decision.timestamp,
            ),
        account =
            AccountView(
                id = request.accountId,
                balance = MoneyPayload(decision.balanceAfter.toBigDecimal(), Money.CURRENCY),
            ),
    )

    private data class Decision(
        val status: TransactionStatus,
        val balanceAfter: Money,
        val timestamp: Instant,
    )
}
