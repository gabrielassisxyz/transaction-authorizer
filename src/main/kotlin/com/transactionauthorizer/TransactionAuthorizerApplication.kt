package com.transactionauthorizer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@ConfigurationPropertiesScan
class TransactionAuthorizerApplication {
    // Injected rather than called statically so a test can decide what "now" is. Every
    // timestamp the service produces or validates goes through it.
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<TransactionAuthorizerApplication>(*args)
}
