package com.bridgarvis.store

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sqs.SqsClient

/** AWS clients use the default provider chain: on EC2 that is the instance role (no stored keys). */
@Configuration
@ConditionalOnProperty("bridgarvis.store", havingValue = "dynamo")
class AwsConfig {
    @Bean(destroyMethod = "close") fun dynamoDbClient(): DynamoDbClient = DynamoDbClient.create()
    @Bean(destroyMethod = "close") fun s3Client(): S3Client = S3Client.create()
    @Bean(destroyMethod = "close") fun sqsClient(): SqsClient = SqsClient.create()
}
