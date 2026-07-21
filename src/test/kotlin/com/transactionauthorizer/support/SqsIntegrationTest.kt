package com.transactionauthorizer.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.localstack.LocalStackContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName

@SpringBootTest
abstract class SqsIntegrationTest protected constructor() {
    companion object {
        const val QUEUE_NAME = "conta-bancaria-criada"
        const val DEAD_LETTER_QUEUE_NAME = "$QUEUE_NAME-dlq"

        // Two receives and a one-second visibility timeout, against the five and thirty
        // seconds of the real topology: the redrive behaviour under test is the same,
        // and waiting out the production budget would make the suite unusable.
        private const val MAX_RECEIVE_COUNT = 2
        private const val VISIBILITY_TIMEOUT_SECONDS = "1"

        private val localstack =
            LocalStackContainer("localstack/localstack:3.7.2")
                .withServices("sqs")
                .apply { start() }

        val sqsClient: SqsClient =
            SqsClient
                .builder()
                .endpointOverride(localstack.endpoint)
                .region(Region.of(localstack.region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey),
                    ),
                ).build()

        val queueUrl: String by lazy { createTopology() }

        val deadLetterQueueUrl: String by lazy {
            sqsClient.getQueueUrl { it.queueName(DEAD_LETTER_QUEUE_NAME) }.queueUrl()
        }

        private fun createTopology(): String {
            val dlqUrl =
                sqsClient
                    .createQueue(CreateQueueRequest.builder().queueName(DEAD_LETTER_QUEUE_NAME).build())
                    .queueUrl()
            val dlqArn =
                sqsClient
                    .getQueueAttributes(
                        GetQueueAttributesRequest
                            .builder()
                            .queueUrl(dlqUrl)
                            .attributeNames(QueueAttributeName.QUEUE_ARN)
                            .build(),
                    ).attributes()[QueueAttributeName.QUEUE_ARN]

            return sqsClient
                .createQueue(
                    CreateQueueRequest
                        .builder()
                        .queueName(QUEUE_NAME)
                        .attributes(
                            mapOf(
                                QueueAttributeName.VISIBILITY_TIMEOUT to VISIBILITY_TIMEOUT_SECONDS,
                                QueueAttributeName.REDRIVE_POLICY to
                                    """{"deadLetterTargetArn":"$dlqArn","maxReceiveCount":"$MAX_RECEIVE_COUNT"}""",
                            ),
                        ).build(),
                ).queueUrl()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            PostgresContainer.register(registry)
            check(queueUrl.isNotBlank())
            registry.add("sqs.endpoint") { localstack.endpoint.toString() }
            registry.add("sqs.region") { localstack.region }
            registry.add("sqs.access-key") { localstack.accessKey }
            registry.add("sqs.secret-key") { localstack.secretKey }
            registry.add("sqs.queue-name") { QUEUE_NAME }
            // A single poller keeps the assertions about who consumed what unambiguous.
            registry.add("sqs.pollers") { 1 }
            registry.add("sqs.wait-time") { "1s" }
            registry.add("sqs.retry-delay") { "100ms" }
        }
    }
}
