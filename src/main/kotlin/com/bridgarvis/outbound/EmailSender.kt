package com.bridgarvis.outbound

import com.bridgarvis.message.Direction
import com.bridgarvis.message.Message
import com.bridgarvis.message.Ulid
import com.bridgarvis.store.MessageStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.sesv2.SesV2Client
import java.time.Duration
import java.time.Instant

interface EmailSender {
    /**
     * Sends the mail and stores the outbound copy. From the configured address by default; [from] may only
     * be one of the auto-responder mailboxes (the only other addresses SES lets the gateway send from).
     */
    fun send(mail: OutgoingEmail, from: String? = null, fromName: String = "Juujarvis"): Message
}

abstract class StoringEmailSender(
    private val store: MessageStore,
    private val defaultFrom: String,
    private val retentionDays: Long,
    otherFrom: String = ""
) : EmailSender {
    protected val log = LoggerFactory.getLogger(javaClass)
    private val allowedFrom = otherFrom.split(",").map { it.trim() }.filter { it.isNotEmpty() } + defaultFrom

    protected abstract fun deliver(raw: ByteArray, mail: OutgoingEmail, from: String): Pair<String, String>  // providerId, status

    override fun send(mail: OutgoingEmail, from: String?, fromName: String): Message {
        MailComposer.validate(mail)
        // Responder mailboxes may be prefix patterns (customer*@domain); SES's IAM condition matches lowercase
        val fromAddress = from?.let { f -> if (allowedFrom.any { com.bridgarvis.mail.MailPolicy.matchesMailbox(f, it) }) f.lowercase() else null }
            ?: if (from == null) defaultFrom else throw InvalidEmailException("not an allowed sender address")
        val id = Ulid.random()
        val messageId = "<${id.lowercase()}@${fromAddress.substringAfter('@')}>"
        val raw = MailComposer.compose(fromAddress, fromName, messageId, mail)
        val (providerId, status) = deliver(raw, mail, fromAddress)
        val now = Instant.now()
        val message = Message(
            id = id,
            channel = "email",
            direction = Direction.OUTBOUND,
            box = "email#$fromAddress",
            from = fromAddress,
            fromName = fromName,
            to = (mail.to + mail.cc).joinToString(", "),
            subject = mail.subject,
            text = mail.text,
            preview = mail.text.replace(Regex("\\s+"), " ").take(200),
            timestamp = now,
            status = status,
            messageId = messageId,
            inReplyTo = mail.inReplyTo,
            references = mail.references,
            providerId = providerId,
            expiresAt = now.plus(Duration.ofDays(retentionDays))
        )
        store.save(message)
        log.info("Email {} sent to {} recipient(s) ({})", id, mail.to.size + mail.cc.size, status)
        return message
    }
}

/** Sends through SES with the configuration set, so delivery events come back on the events queue. */
@Component
@ConditionalOnProperty("bridgarvis.store", havingValue = "dynamo")
class SesEmailSender(
    private val ses: SesV2Client,
    store: MessageStore,
    @Value("\${bridgarvis.mail.from-address}") fromAddress: String,
    @Value("\${bridgarvis.mail.configuration-set}") private val configurationSet: String,
    @Value("\${bridgarvis.mail.retention-days:365}") retentionDays: Long,
    @Value("\${bridgarvis.mail.responder.addresses:}") responderAddresses: String
) : StoringEmailSender(store, fromAddress, retentionDays, responderAddresses) {
    override fun deliver(raw: ByteArray, mail: OutgoingEmail, from: String): Pair<String, String> {
        val response = ses.sendEmail {
            it.content { c -> c.raw { r -> r.data(SdkBytes.fromByteArray(raw)) } }
                .destination { d -> d.toAddresses(mail.to).ccAddresses(mail.cc) }
                .configurationSetName(configurationSet)
        }
        return response.messageId() to "SENT"
    }
}

/** Local runs and tests: records the mail without sending anything. */
@Component
@ConditionalOnProperty("bridgarvis.store", havingValue = "memory", matchIfMissing = true)
class MockEmailSender(
    store: MessageStore,
    @Value("\${bridgarvis.mail.from-address:assistant@example.com}") fromAddress: String,
    @Value("\${bridgarvis.mail.responder.addresses:}") responderAddresses: String
) : StoringEmailSender(store, fromAddress, 365, responderAddresses) {
    override fun deliver(raw: ByteArray, mail: OutgoingEmail, from: String) = "mock-${Ulid.random()}" to "MOCK_SENT"
}
