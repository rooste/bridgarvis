package com.bridgarvis.sms

import com.bridgarvis.store.MessageStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client
import software.amazon.awssdk.services.sqs.SqsClient
import java.time.Duration

/**
 * On AWS (origination identity configured): real sends, consent in DynamoDB, queue consumers.
 * Otherwise (local runs, tests): consent in memory and a mock sender.
 */
@Configuration
class SmsConfig(
    @Value("\${bridgarvis.sms.number:+15550000000}") private val number: String,
    @Value("\${bridgarvis.sms.origination-identity:}") private val originationIdentity: String,
    @Value("\${bridgarvis.sms.configuration-set:}") private val configurationSet: String,
    @Value("\${bridgarvis.sms.consent-table:}") private val consentTable: String,
    @Value("\${bridgarvis.sms.inbound-queue-url:}") private val inboundQueue: String,
    @Value("\${bridgarvis.sms.events-queue-url:}") private val eventsQueue: String,
    @Value("\${bridgarvis.sms.retention-days:365}") private val retentionDays: Long
) {
    @Bean
    fun smsService(store: MessageStore, db: ObjectProvider<DynamoDbClient>): SmsService {
        val dynamo = db.ifAvailable
        val consent = if (consentTable.isNotBlank() && dynamo != null) DynamoSmsConsentStore(dynamo, consentTable) else MemorySmsConsentStore()
        val sender = if (originationIdentity.isNotBlank()) {
            AwsSmsSender(PinpointSmsVoiceV2Client.create(), originationIdentity, configurationSet.ifBlank { null })
        } else MockSmsSender()
        return SmsService(store, consent, sender, number, Duration.ofDays(retentionDays))
    }

    @Bean
    fun smsIngestorStarter(sms: SmsService, sqs: ObjectProvider<SqsClient>, mapper: ObjectMapper): ApplicationListener<ApplicationReadyEvent> {
        val client = sqs.ifAvailable
        if (inboundQueue.isBlank() || client == null) return ApplicationListener { }
        val ingestor = SmsIngestor(client, sms, mapper, inboundQueue, eventsQueue.ifBlank { null })
        Runtime.getRuntime().addShutdownHook(Thread { ingestor.stop() })
        return ApplicationListener { ingestor.start() }
    }
}
