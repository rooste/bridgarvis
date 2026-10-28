package com.bridgarvis.sms

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Background work for SMS on AWS: inbound texts (number → SNS → SQS), delivery events (configuration
 * set → SNS → SQS) and an hourly opt-out list sync. A queue message is deleted only after it is handled.
 */
class SmsIngestor(
    private val sqs: SqsClient,
    private val sms: SmsService,
    private val mapper: ObjectMapper,
    private val inboundQueue: String,
    private val eventsQueue: String?
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var running = true
    private val pool = Executors.newScheduledThreadPool(3) { Thread(it, "sms").apply { isDaemon = true } }

    fun start() {
        pool.execute { poll(inboundQueue, "inbound", ::inbound) }
        if (!eventsQueue.isNullOrBlank()) pool.execute { poll(eventsQueue, "events", ::event) }
        pool.scheduleWithFixedDelay({ runCatching { sms.syncOptOuts() }.onFailure { log.warn("Opt-out sync failed: {}", it.toString()) } },
            0, 1, TimeUnit.HOURS)
        log.info("SMS ingest started")
    }

    fun stop() {
        running = false
        pool.shutdownNow()
    }

    private fun inbound(body: String, sentAt: Instant) {
        val n = mapper.readTree(body)
        val from = n.path("originationNumber").asText()
        val text = n.path("messageBody").asText()
        val id = n.path("inboundMessageId").asText().ifBlank { error("no inboundMessageId") }
        sms.receive(from, text, id, sentAt)
    }

    private fun event(body: String, @Suppress("UNUSED_PARAMETER") sentAt: Instant) {
        val n = mapper.readTree(body)
        val messageId = n.path("messageId").asText().ifBlank { return }
        sms.delivery(messageId, n.path("eventType").asText(), n.path("messageStatusDescription").asText().ifBlank { null })
    }

    private fun poll(queue: String, name: String, handle: (String, Instant) -> Unit) {
        while (running) {
            try {
                val messages = sqs.receiveMessage {
                    it.queueUrl(queue).maxNumberOfMessages(10).waitTimeSeconds(20)
                        .messageSystemAttributeNames(MessageSystemAttributeName.SENT_TIMESTAMP)
                }.messages()
                for (m in messages) {
                    try {
                        val sentAt = m.attributes()[MessageSystemAttributeName.SENT_TIMESTAMP]?.toLongOrNull()
                            ?.let { Instant.ofEpochMilli(it) } ?: Instant.now()
                        handle(m.body(), sentAt)
                        sqs.deleteMessage { it.queueUrl(queue).receiptHandle(m.receiptHandle()) }
                    } catch (e: Exception) {
                        log.warn("SMS {} message {} failed, will retry: {}", name, m.messageId(), e.toString())
                    }
                }
            } catch (e: Exception) {
                if (!running) return
                log.warn("SMS {} queue poll failed: {}", name, e.toString())
                Thread.sleep(5_000)
            }
        }
    }
}
