package com.transactionauthorizer

import com.transactionauthorizer.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

class TransactionAuthorizerApplicationTests : PostgresIntegrationTest() {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `application context starts`() {
        assertThat(context.getBean(TransactionAuthorizerApplication::class.java)).isNotNull()
    }
}
