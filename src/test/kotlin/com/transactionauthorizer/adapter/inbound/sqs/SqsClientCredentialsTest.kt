package com.transactionauthorizer.adapter.inbound.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.sqs.SqsClient

// The failure this guards against is silent: a deployment with no endpoint override
// would carry the local emulator's keys and simply consume nothing.
class SqsClientCredentialsTest {
    private val configuration = SqsClientConfiguration()

    @Test
    fun `an endpoint override uses the configured keys`() {
        val client = configuration.sqsClient(localProperties())

        assertThat(credentialsProviderOf(client)).isInstanceOf(StaticCredentialsProvider::class.java)
    }

    @Test
    fun `no endpoint override falls back to the default credential chain`() {
        val client = configuration.sqsClient(localProperties().copy(endpoint = null))

        assertThat(credentialsProviderOf(client)).isInstanceOf(DefaultCredentialsProvider::class.java)
    }

    @Test
    fun `an empty endpoint is the same as none`() {
        val client = configuration.sqsClient(localProperties().copy(endpoint = ""))

        assertThat(credentialsProviderOf(client)).isInstanceOf(DefaultCredentialsProvider::class.java)
    }

    private fun localProperties() =
        SqsProperties(
            endpoint = "http://localhost:4566",
            accessKey = "test",
            secretKey = "test",
        )

    private fun credentialsProviderOf(client: SqsClient): Any? =
        client
            .serviceClientConfiguration()
            .credentialsProvider()
}
