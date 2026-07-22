package com.transactionauthorizer.adapter.inbound.sqs

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.URI

@Configuration
class SqsClientConfiguration {
    @Bean
    fun sqsClient(properties: SqsProperties): SqsClient =
        SqsClient
            .builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentialsProvider(properties))
            // Blank and absent must behave alike: a real deployment sets SQS_ENDPOINT to
            // an empty value, and `URI.create("")` has no scheme, so the builder throws.
            .apply {
                properties.endpoint?.takeIf { it.isNotBlank() }?.let { endpointOverride(URI.create(it)) }
            }.build()

    // Static keys are tied to the endpoint override because that is what they mean: an
    // emulator demanding credentials it ignores. The local default would otherwise leak.
    private fun credentialsProvider(properties: SqsProperties): AwsCredentialsProvider {
        val accessKey = properties.accessKey
        val secretKey = properties.secretKey
        if (properties.endpoint.isNullOrBlank() || accessKey.isNullOrBlank() || secretKey.isNullOrBlank()) {
            return DefaultCredentialsProvider.builder().build()
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
    }
}
