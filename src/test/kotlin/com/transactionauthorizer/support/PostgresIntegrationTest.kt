package com.transactionauthorizer.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest
abstract class PostgresIntegrationTest protected constructor() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            PostgresContainer.register(registry)
            // No queue to reach in this context: pollers would only log connection
            // failures for the whole suite.
            registry.add("sqs.pollers") { 0 }
        }
    }
}
