package com.transactionauthorizer.adapter.inbound.web

import com.transactionauthorizer.support.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

// The three health semantics have to stay distinct: liveness with no dependency, readiness
// tracking the database only, and SQS as its own component that never rides in readiness.
@AutoConfigureMockMvc
class HealthGroupsIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `liveness carries no dependency`() {
        mockMvc.get("/actuator/health/liveness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.components.db") { doesNotExist() }
            jsonPath("$.components.sqs") { doesNotExist() }
        }
    }

    @Test
    fun `readiness tracks the database and leaves sqs out`() {
        mockMvc.get("/actuator/health/readiness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.components.db") { exists() }
            jsonPath("$.components.sqs") { doesNotExist() }
        }
    }

    @Test
    fun `sqs is a component of its own, separate from the readiness the load balancer follows`() {
        mockMvc.get("/actuator/health").andExpect {
            jsonPath("$.components.sqs") { exists() }
            jsonPath("$.components.db") { exists() }
        }
    }
}
