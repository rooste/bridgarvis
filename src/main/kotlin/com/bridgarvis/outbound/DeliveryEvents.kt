package com.bridgarvis.outbound

import com.bridgarvis.store.MessageStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient

/** Maps an SES event to an outbound status. Higher rank wins, so e.g. a late DELAYED never hides DELIVERED. */
object DeliveryStatus {
    private val rank = mapOf("SENT" to 0, "DELAYED" to 1, "DELIVERED" to 2, "BOUNCED_TRANSIENT" to 3,
        "BOUNCED" to 4, "REJECTED" to 4, "RENDERING_FAILED" to 4, "COMPLAINT" to 5)

    fun shouldReplace(current: String, next: String) = (rank[next] ?: 0) >= (rank[current] ?: 0)

    /** (status, detail) for an SES event, or null for events we don't track. */
    fun fromEvent(event: JsonNode): Pair<String, String?>? = when (event.path("eventType").asText()) {
        "Delivery" -> "DELIVERED" to null
        "Bounce" -> {
            val bounce = event.path("bounce")
            val permanent = bounce.path("bounceType").asText() == "Permanent"
            val who = bounce.path("bouncedRecipients").joinToString(", ") {
                it.path("emailAddress").asText() + it.path("diagnosticCode").asText().takeIf { d -> d.isNotBlank() }?.let { d -> " ($d)" }.orEmpty()
            }
            (if (permanent) "BOUNCED" else "BOUNCED_TRANSIENT") to "${bounce.path("bounceType").asText()} bounce: $who".take(1000)
        }
        "Complaint" -> "COMPLAINT" to "Complaint from " + event.path("complaint").path("complainedRecipients").joinToString(", ") { it.path("emailAddress").asText() }
        "Reject" -> "REJECTED" to event.path("reject").path("reason").asText()
        "DeliveryDelay" -> "DELAYED" to event.path("deliveryDelay").path("delayType").asText()
        "Rendering Failure" -> "RENDERING_FAILED" to event.path("failure").path("errorMessage").asText().take(500)
        else -> null
    }
}

/**
 * Consumes SES delivery events (configuration set → SNS → SQS) and updates the stored outbound mail.
 * Bounces and complaints also land on the SES account suppression list, so SES stops sending there.
 */
@Component
@ConditionalOnExpression("!'\${bridgarvis.mail.events-queue-url:}'.isEmpty()")
class DeliveryEventIngestor(
    private val sqs: SqsClient,
    private val store: MessageStore,
    private val mapper: ObjectMapper,
    @Value("\${bridgarvis.mail.events-queue-url}") private val queueUrl: String,
    @Value("\${bridgarvis.mail.from-address}") fromAddress: String
) {
    // The gateway sends from exactly one address, so all outbound mail lives in this mailbox.
    // (The event's mail.source can carry a display name, so it is not used as the key.)
    private val sentBox = "email#$fromAddress"
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var running = true
    private val worker = Thread({ loop() }, "mail-events").apply { isDaemon = true }

    @EventListener(ApplicationReadyEvent::class)
    fun start() { worker.start() }

    @PreDestroy
    fun stop() { running = false; worker.interrupt() }

    private fun loop() {
        while (running) {
            try {
                for (m in sqs.receiveMessage { it.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(20) }.messages()) {
                    try {
                        process(m.body())
                        sqs.deleteMessage { it.queueUrl(queueUrl).receiptHandle(m.receiptHandle()) }
                    } catch (e: Exception) {
                        log.warn("Delivery event {} failed, will retry: {}", m.messageId(), e.toString())
                    }
                }
            } catch (e: Exception) {
                if (!running) return
                log.warn("Delivery event poll failed: {}", e.toString())
                Thread.sleep(5_000)
            }
        }
    }

    fun process(body: String) {
        val event = mapper.readTree(body)
        val (status, detail) = DeliveryStatus.fromEvent(event) ?: return
        val providerId = event.path("mail").path("messageId").asText()
        val box = sentBox
        val message = store.findByProvider(box, providerId)
        if (message == null) {
            log.info("Delivery event {} for unknown message {} (source {})", status, providerId, event.path("mail").path("source").asText())
            return
        }
        if (DeliveryStatus.shouldReplace(message.status, status)) {
            store.updateDelivery(box, message.id, status, detail)
            log.info("Email {}: {}", message.id, status)
        }
    }
}
