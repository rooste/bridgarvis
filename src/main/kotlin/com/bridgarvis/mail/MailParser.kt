package com.bridgarvis.mail

import org.apache.james.mime4j.codec.DecodeMonitor
import org.apache.james.mime4j.dom.BinaryBody
import org.apache.james.mime4j.dom.Entity
import org.apache.james.mime4j.dom.Message
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.dom.address.Mailbox
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.message.DefaultMessageWriter
import org.apache.james.mime4j.stream.MimeConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

data class MailAddress(val address: String, val name: String?)

data class ParsedAttachment(val index: Int, val name: String, val contentType: String, val bytes: ByteArray)

data class ParsedMail(
    val from: MailAddress?,
    val to: List<MailAddress>,
    val cc: List<MailAddress>,
    val subject: String?,
    val date: Instant?,
    val messageId: String?,
    val inReplyTo: String?,
    val references: String?,
    /** Full cleaned text (plain part preferred, otherwise text from HTML). */
    val text: String,
    /** The new part of a reply, without the quoted history. */
    val latest: String,
    val attachments: List<ParsedAttachment>,
    /** Parts that were skipped because a limit was reached. */
    val truncated: Boolean
)

class MailTooLargeException(message: String) : RuntimeException(message)

/**
 * Parses raw mail (untrusted bytes from the internet) with hard limits so a hostile message can only
 * fail, not exhaust the process: total size, header size/count, number of parts, nesting depth,
 * attachment count and attachment bytes.
 */
class MailParser(
    private val maxBytes: Int = 20 * 1024 * 1024,
    private val maxParts: Int = 100,
    private val maxDepth: Int = 10,
    private val maxAttachments: Int = 20,
    private val maxTextChars: Int = 1_000_000
) {
    fun parse(raw: ByteArray): ParsedMail {
        if (raw.size > maxBytes) throw MailTooLargeException("mail is ${raw.size} bytes (limit $maxBytes)")

        val config = MimeConfig.Builder()
            .setMaxLineLen(100_000)
            .setMaxHeaderCount(500)
            .setMaxHeaderLen(100_000)
            .setMaxContentLen(maxBytes.toLong())
            .build()
        val builder = DefaultMessageBuilder().apply {
            setMimeEntityConfig(config)
            setDecodeMonitor(DecodeMonitor.SILENT)
        }
        val message = builder.parseMessage(ByteArrayInputStream(raw))

        val walk = Walk()
        walk.visit(message, 0)

        val text = TextCleaner.normalize(
            (walk.plain ?: walk.html?.let { TextCleaner.htmlToText(it) } ?: "").take(maxTextChars)
        )
        return ParsedMail(
            from = message.from?.firstOrNull()?.let { it.toAddress() },
            to = message.to?.flatten()?.map { it.toAddress() }.orEmpty(),
            cc = message.cc?.flatten()?.map { it.toAddress() }.orEmpty(),
            subject = message.subject?.let { TextCleaner.normalize(it).take(500) },
            date = message.date?.toInstant(),
            messageId = message.messageId?.take(500),
            inReplyTo = message.header.getField("In-Reply-To")?.body?.trim()?.take(1000),
            references = message.header.getField("References")?.body?.trim()?.take(4000),
            text = text,
            latest = TextCleaner.stripQuoted(text),
            attachments = walk.attachments,
            truncated = walk.truncated
        )
    }

    private fun Mailbox.toAddress() = MailAddress(
        address = address,
        name = name?.let { TextCleaner.normalize(it).take(200) }?.ifBlank { null }
    )

    private inner class Walk {
        var plain: String? = null
        var html: String? = null
        val attachments = mutableListOf<ParsedAttachment>()
        var parts = 0
        var truncated = false

        fun visit(entity: Entity, depth: Int) {
            if (++parts > maxParts || depth > maxDepth) {
                truncated = true
                return
            }
            val body = entity.body
            when {
                body is Multipart -> body.bodyParts.forEach { visit(it, depth + 1) }
                body is Message -> attach(entity, "forwarded-message.eml", "message/rfc822") {
                    ByteArrayOutputStream().also { DefaultMessageWriter().writeMessage(body, it) }.toByteArray()
                }
                body is TextBody && !isAttachment(entity) && entity.mimeType.equals("text/plain", true) && plain == null ->
                    plain = body.reader.use { it.readText() }
                body is TextBody && !isAttachment(entity) && entity.mimeType.equals("text/html", true) && html == null ->
                    html = body.reader.use { it.readText() }
                body is TextBody -> attach(entity, defaultName(), entity.mimeType) {
                    body.inputStream.use { it.readBytes() }
                }
                body is BinaryBody -> attach(entity, defaultName(), entity.mimeType) {
                    body.inputStream.use { it.readBytes() }
                }
            }
        }

        private fun isAttachment(entity: Entity) =
            entity.dispositionType.equals("attachment", true) || entity.filename != null

        private fun defaultName() = "attachment-${attachments.size + 1}"

        private fun attach(entity: Entity, fallbackName: String, mimeType: String, bytes: () -> ByteArray) {
            if (attachments.size >= maxAttachments) {
                truncated = true
                return
            }
            val name = entity.filename?.let { TextCleaner.normalize(it) }
                ?.replace(Regex("[/\\\\]"), "_")?.take(200)?.ifBlank { null } ?: fallbackName
            val type = mimeType.lowercase().takeIf { Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$").matches(it) }
                ?: "application/octet-stream"
            attachments += ParsedAttachment(attachments.size, name, type, bytes())
        }
    }
}
