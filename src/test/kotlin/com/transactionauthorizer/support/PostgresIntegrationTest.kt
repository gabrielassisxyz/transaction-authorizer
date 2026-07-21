package com.transactionauthorizer.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer

// One container for the whole JVM, started eagerly and reaped by Ryuk. The JUnit
// `@Container` lifecycle would restart Postgres for every test class instead.
@SpringBootTest
abstract class PostgresIntegrationTest protected constructor() {
    companion object {
        private val postgres = PostgreSQLContainer("postgres:17.5").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
