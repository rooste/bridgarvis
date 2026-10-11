package com.bridgarvis.mail

import com.bridgarvis.message.AttachmentInfo
import com.bridgarvis.message.Direction
import com.bridgarvis.message.JUUJARVIS
import com.bridgarvis.message.Message
import com.bridgarvis.message.Purpose
import com.bridgarvis.message.Ulid
import com.bridgarvis.store.MessageStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sqs.SqsClient
import java.time.Duration
import java.time.Instant

/**
 * Consumes SES "Received" notifications (SES → S3 + SNS → SQS), parses the raw mail from S3 and stores
 * one item per recipient mailbox. Mail for the Juujarvis address that passes [MailPolicy] is marked
 * pending (and announced); otherwise it is quarantined. A notification is deleted from the queue only
 * after everything is stored, so failures retry and end up in the dead-letter queue.
 */
@Component
@ConditionalOnProperty("bridgarvis.mail.ingest-enabled", havingValue = "true")
class MailIngestor(
    private val sqs: SqsClient,
    private val s3: S3Client,
    private val store: MessageStore,
    private val mapper: ObjectMapper,
    @Value("\${bridgarvis.mail.queue-url}") private val queueUrl: String,
    @Value("\${bridgarvis.mail.bucket}") private val bucket: String,
    @Value("\${bridgarvis.mail.juujarvis-address}") juujarvisAddresses: String,
    @Value("\${bridgarvis.mail.notify-addresses:}") notifyAddresses: String,
    @Value("\${bridgarvis.mail.retention-days:365}") private val retentionDays: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val parser = MailParser()
    private val assistantList = addressList(juujarvisAddresses)
    private val notifyList = addressList(notifyAddresses)

    private fun addressList(value: String) = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    @Volatile private var running = true
    private val worker = Thread({ loop() }, "mail-ingest").apply { isDaemon = true }

    companion object {
        const val INLINE_TEXT_CHARS = 40_000
        const val INLINE_LATEST_CHARS = 20_000
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        log.info("Mail ingest started")
        worker.start()
    }

    @PreDestroy
    fun stop() {
        running = false
        worker.interrupt()
    }

    private fun loop() {
        while (running) {
            try {
                val messages = sqs.receiveMessage {
                    it.queueUrl(queueUrl).maxNumberOfMessages(5).waitTimeSeconds(20)
                }.messages()
                for (m in messages) {
                    try {
                        process(m.body())
                        sqs.deleteMessage { it.queueUrl(queueUrl).receiptHandle(m.receiptHandle()) }
                    } catch (e: Exception) {
                        log.warn("Mail notification {} failed, will retry: {}", m.messageId(), e.toString())
                    }
                }
            } catch (e: Exception) {
                if (!running) return
                log.warn("Mail queue poll failed: {}", e.toString())
                Thread.sleep(5_000)
            }
        }
    }

    fun process(body: String) {
        val n = mapper.readTree(body)
        if (n.path("notificationType").asText() != "Received") {
            log.info("Ignoring notification type {}", n.path("notificationType").asText())
            return
        }
        val mail = n.path("mail")
        val receipt = n.path("receipt")
        val sesId = mail.path("messageId").asText()
        val objectKey = receipt.path("action").path("objectKey").asText()
        val receivedAt = runCatching { Instant.parse(mail.path("timestamp").asText()) }.getOrElse { Instant.now() }
        val recipients = receipt.path("recipients").map { it.asText() }.filter { it.isNotBlank() }
        val verdicts = mapOf(
            "spf" to receipt.verdict("spfVerdict"),
            "dkim" to receipt.verdict("dkimVerdict"),
            "dmarc" to receipt.verdict("dmarcVerdict"),
            "spam" to receipt.verdict("spamVerdict"),
            "virus" to receipt.verdict("virusVerdict"),
        )
        val id = Ulid.deterministic(receivedAt, sesId)
        val trusted = MailPolicy.isTrusted(verdicts)

        val parsed = try {
            parser.parse(s3.getObjectAsBytes { it.bucket(bucket).key(objectKey) }.asByteArray())
        } catch (e: Exception) {
            log.warn("Mail {} could not be parsed: {}", id, e.toString())
            null
        }

        val attachments = parsed?.attachments.orEmpty().map { a ->
            val key = "parsed/$id/att/${a.index}"
            s3.putObject({ it.bucket(bucket).key(key).contentType(a.contentType) }, RequestBody.fromBytes(a.bytes))
            AttachmentInfo(a.index, a.name, a.contentType, a.bytes.size.toLong(), key)
        }
        val text = parsed?.text.orEmpty()
        val textKey = if (text.length > INLINE_TEXT_CHARS) "parsed/$id/text.txt".also { key ->
            s3.putObject({ it.bucket(bucket).key(key).contentType("text/plain; charset=utf-8") }, RequestBody.fromString(text))
        } else null

        for (recipient in recipients) {
            val purpose = when {
                assistantList.any { recipient.equals(it, ignoreCase = true) } -> Purpose.ASSISTANT
                notifyList.any { recipient.equals(it, ignoreCase = true) } -> Purpose.NOTIFY
                else -> null
            }
            // The assistant only gets authenticated mail; notify-only mailboxes just skip spam/viruses
            val accepted = when (purpose) {
                Purpose.ASSISTANT -> trusted
                Purpose.NOTIFY -> MailPolicy.isClean(verdicts)
                else -> true
            }
            val status = when {
                parsed == null -> "UNPARSEABLE"
                purpose != null && !accepted -> "QUARANTINED"
                else -> "RECEIVED"
            }
            store.save(Message(
                id = id,
                channel = "email",
                direction = Direction.INBOUND,
                box = "email#$recipient",
                from = parsed?.from?.address ?: mail.path("source").asText(),
                fromName = parsed?.from?.name,
                to = recipient,
                subject = parsed?.subject,
                text = if (textKey != null) text.take(INLINE_TEXT_CHARS) else text,
                latest = parsed?.latest?.take(INLINE_LATEST_CHARS),
                preview = parsed?.let { TextCleaner.preview(it.latest) },
                timestamp = receivedAt,
                status = status,
                attachments = attachments,
                auth = verdicts,
                messageId = parsed?.messageId,
                inReplyTo = parsed?.inReplyTo,
                references = parsed?.references,
                purpose = purpose,
                pendingFor = if (purpose != null && status == "RECEIVED") JUUJARVIS else null,
                textKey = textKey,
                rawKey = objectKey,
                providerId = sesId,
                expiresAt = receivedAt.plus(Duration.ofDays(retentionDays))
            ))
        }
        log.info("Mail {} stored for {} mailbox(es); trusted={}, verdicts={}", id, recipients.size, trusted, verdicts)
    }

    private fun JsonNode.verdict(name: String) = path(name).path("status").asText("MISSING")
}
