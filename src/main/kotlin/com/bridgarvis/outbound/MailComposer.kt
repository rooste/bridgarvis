package com.bridgarvis.outbound

import org.apache.james.mime4j.codec.EncoderUtil
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

data class OutgoingEmail(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String,
    val text: String,
    /** Message-ID of the mail being answered, e.g. "<abc@example.com>" (threads the reply). */
    val inReplyTo: String? = null,
    /** Previous References plus the answered Message-ID, space-separated. */
    val references: String? = null
)

class InvalidEmailException(message: String) : RuntimeException(message)

/**
 * Builds a plain-text MIME message. Everything that ends up in a header is validated or encoded so
 * that no input can add headers or recipients (no CR/LF anywhere, strict address syntax).
 */
object MailComposer {
    private val address = Regex("^[A-Za-z0-9.!#\$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")
    private val messageIdToken = Regex("^<[^<>\\s]{1,250}>$")
    const val MAX_RECIPIENTS = 10

    fun validate(mail: OutgoingEmail) {
        val all = mail.to + mail.cc
        if (mail.to.isEmpty()) throw InvalidEmailException("at least one 'to' address is required")
        if (all.size > MAX_RECIPIENTS) throw InvalidEmailException("at most $MAX_RECIPIENTS recipients")
        all.forEach { if (!address.matches(it)) throw InvalidEmailException("invalid address: ${it.take(100)}") }
        if (mail.subject.isBlank() || mail.subject.length > 300 || mail.subject.any { it == '\r' || it == '\n' })
            throw InvalidEmailException("subject must be 1-300 characters on one line")
        if (mail.text.isBlank() || mail.text.length > 100_000) throw InvalidEmailException("text must be 1-100000 characters")
        mail.inReplyTo?.let { if (!messageIdToken.matches(it)) throw InvalidEmailException("invalid inReplyTo") }
        mail.references?.split(' ')?.filter { it.isNotBlank() }?.let { refs ->
            if (refs.size > 50 || refs.any { !messageIdToken.matches(it) }) throw InvalidEmailException("invalid references")
        }
    }

    fun compose(fromAddress: String, fromName: String, messageId: String, mail: OutgoingEmail, now: ZonedDateTime = ZonedDateTime.now()): ByteArray {
        validate(mail)
        require(address.matches(fromAddress)) { "invalid from address" }
        val headers = buildList {
            add("From: ${EncoderUtil.encodeIfNecessary(fromName, EncoderUtil.Usage.WORD_ENTITY, 6)} <$fromAddress>")
            add("To: ${mail.to.joinToString(", ")}")
            if (mail.cc.isNotEmpty()) add("Cc: ${mail.cc.joinToString(", ")}")
            add("Subject: ${EncoderUtil.encodeIfNecessary(mail.subject, EncoderUtil.Usage.TEXT_TOKEN, 9)}")
            add("Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(now)}")
            add("Message-ID: $messageId")
            mail.inReplyTo?.let { add("In-Reply-To: $it") }
            mail.references?.trim()?.ifBlank { null }?.let { add("References: $it") }
            add("MIME-Version: 1.0")
            add("Content-Type: text/plain; charset=UTF-8")
            add("Content-Transfer-Encoding: base64")
        }
        val body = Base64.getMimeEncoder(76, "\r\n".toByteArray())
            .encodeToString(mail.text.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_8))
        return (headers.joinToString("\r\n") + "\r\n\r\n" + body + "\r\n").toByteArray(StandardCharsets.US_ASCII)
    }
}
