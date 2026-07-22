package com.transactionauthorizer.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer

// One container for the whole JVM, started eagerly and reaped by Ryuk. The JUnit
// `@Container` lifecycle would restart Postgres for every test class instead.
object PostgresContainer {
    private val container = PostgreSQLContainer("postgres:17.5").apply { start() }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url", container::getJdbcUrl)
        registry.add("spring.datasource.username", container::getUsername)
        registry.add("spring.datasource.password", container::getPassword)
    }
}
