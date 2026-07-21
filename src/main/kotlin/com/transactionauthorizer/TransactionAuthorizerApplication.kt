package com.transactionauthorizer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class TransactionAuthorizerApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<TransactionAuthorizerApplication>(*args)
}
