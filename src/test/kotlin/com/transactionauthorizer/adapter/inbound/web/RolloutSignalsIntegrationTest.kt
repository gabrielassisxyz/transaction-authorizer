package com.transactionauthorizer.adapter.inbound.web

import com.transactionauthorizer.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

// The rollout gate in docs/deploy.md reverts a canary step on p99 latency and on the
// non-5xx ratio, and attributes what it sees to a revision. All three are read from what the
// instance exports, so a gate that names a series the scrape does not carry would only be
// discovered during a rollback. These assertions are what keeps the proposal honest.
@AutoConfigureMockMvc
class RolloutSignalsIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private fun scrapeAfterOneRequest(): String {
        mockMvc.get("/actuator/health").andReturn()
        return mockMvc
            .get("/actuator/prometheus")
            .andReturn()
            .response
            .contentAsString
    }

    @Test
    fun `the scrape carries latency buckets, not just a count and a sum`() {
        val scrape = scrapeAfterOneRequest()

        // Buckets are what a percentile can be derived from. Without them the series still
        // exists and still looks healthy, and every query over it silently answers a mean.
        assertThat(scrape)
            .`as`("the HTTP timer exports no histogram bucket, so no percentile can be computed from it")
            .contains("http_server_requests_seconds_bucket")
            .contains("le=\"+Inf\"")
    }

    @Test
    fun `the scrape tags requests with their status, so the non-5xx ratio is computable`() {
        val scrape = scrapeAfterOneRequest()

        assertThat(scrape)
            .`as`("the HTTP timer is not tagged by status, so availability cannot be derived from it")
            .contains("status=\"200\"")
    }

    @Test
    fun `the instance answers which revision it is`() {
        mockMvc.get("/actuator/info").andExpect {
            status { isOk() }
            jsonPath("$.build.version") { exists() }
            jsonPath("$.build.time") { exists() }
        }
    }
}
