package com.transactionauthorizer.adapter.outbound.persistence

import com.transactionauthorizer.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class InitialSchemaMigrationTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `flyway creates every table of the initial schema`() {
        val tables =
            jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String::class.java,
            )

        assertThat(tables).contains("accounts", "transaction_claims", "transactions")
    }

    @Test
    fun `a negative balance is rejected by the database itself`() {
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO accounts (id, owner_id, status, created_at, balance_cents) VALUES (?, ?, ?, now(), ?)",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ENABLED",
                -1L,
            )
        }.hasMessageContaining("balance_cents")
    }

    @Test
    fun `an unknown account status is rejected by the database itself`() {
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO accounts (id, owner_id, status, created_at) VALUES (?, ?, ?, now())",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "FROZEN",
            )
        }.hasMessageContaining("status")
    }
}
