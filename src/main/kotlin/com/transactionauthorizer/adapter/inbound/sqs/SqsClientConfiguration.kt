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
            .apply { properties.endpoint?.let { endpointOverride(URI.create(it)) } }
            .build()

    // Static keys exist for localstack, which has no credential chain to consult.
    // Leaving them unset is what makes a deployed instance fall back to the instance
    // role instead of silently carrying a hardcoded identity.
    private fun credentialsProvider(properties: SqsProperties): AwsCredentialsProvider {
        val accessKey = properties.accessKey
        val secretKey = properties.secretKey
        if (accessKey.isNullOrBlank() || secretKey.isNullOrBlank()) {
            return DefaultCredentialsProvider.builder().build()
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
    }
}
