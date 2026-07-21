package com.transactionauthorizer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TransactionAuthorizerApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<TransactionAuthorizerApplication>(*args)
}
