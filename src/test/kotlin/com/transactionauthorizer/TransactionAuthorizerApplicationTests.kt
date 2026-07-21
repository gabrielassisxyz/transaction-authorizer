package com.transactionauthorizer

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import kotlin.test.assertNotNull

@SpringBootTest
class TransactionAuthorizerApplicationTests {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun contextLoads() {
        assertNotNull(context.getBean(TransactionAuthorizerApplication::class.java))
    }
}
