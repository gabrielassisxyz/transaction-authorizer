package com.transactionauthorizer.adapter.inbound.web

import com.transactionauthorizer.application.port.AuthorizationUnavailableException
import com.transactionauthorizer.domain.Money
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.math.BigDecimal
import java.util.UUID

class AccountNotFoundException(
    accountId: UUID,
) : RuntimeException("account $accountId does not exist")

class UnsupportedCurrencyException(
    currency: String,
) : RuntimeException("currency '$currency' is not supported; only ${Money.CURRENCY} is accepted")

class AmountOutOfRangeException(
    value: BigDecimal,
    cause: Throwable,
) : RuntimeException("amount ${value.toPlainString()} is outside the representable range", cause)

class InvalidAmountException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@RestControllerAdvice
class AuthorizationProblemHandler {
    @ExceptionHandler(AccountNotFoundException::class)
    fun onAccountNotFound(e: AccountNotFoundException): ProblemDetail = problem(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(InvalidAmountException::class)
    fun onInvalidAmount(e: InvalidAmountException): ProblemDetail = problem(HttpStatus.BAD_REQUEST, e.message)

    // Well-formed but unprocessable: the value parses, yet the currency is not one this
    // service accepts or the amount does not fit the ledger.
    @ExceptionHandler(UnsupportedCurrencyException::class, AmountOutOfRangeException::class)
    fun onUnprocessable(e: RuntimeException): ProblemDetail = problem(HttpStatus.UNPROCESSABLE_ENTITY, e.message)

    // The one outcome that is not a decision and not a bad request: the service could not
    // reach its store, so it says so instead of inventing a refusal. Retry-After carries the
    // breaker's own open window, so the client is told how long the answer will stay this way.
    @ExceptionHandler(AuthorizationUnavailableException::class)
    fun onStoreUnavailable(e: AuthorizationUnavailableException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, e.retryAfter.toSeconds().toString())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(
                problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "the authorization store is unavailable; no decision was taken for this transaction",
                ),
            )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onMalformedBody(): ProblemDetail = problem(HttpStatus.BAD_REQUEST, "request body is missing or malformed")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onMalformedPath(e: MethodArgumentTypeMismatchException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "path variable '${e.name}' is not a valid ${e.requiredType?.simpleName}")

    private fun problem(
        status: HttpStatus,
        detail: String?,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase)
}
