package com.transactionauthorizer.application.port

import java.time.Duration

// The port's failure contract: the store could not reach a decision at all, which is a
// different outcome from deciding to refuse. It lives with the port rather than with the
// adapter that raises it so that the inbound side can translate it into a status code
// without ever depending on an outbound adapter.
//
// retryAfter is the wait the caller should observe, carried from whoever knows it (the
// breaker's own open window) instead of being guessed again at the edge.
class AuthorizationUnavailableException(
    val retryAfter: Duration,
    cause: Throwable? = null,
) : RuntimeException("the authorization store is unavailable", cause)
