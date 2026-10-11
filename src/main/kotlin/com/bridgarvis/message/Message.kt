package com.bridgarvis.message

import com.fasterxml.jackson.annotation.JsonIgnore
import java.io.InputStream
import java.time.Instant

/** Marks items waiting for Juujarvis (the sparse "pending" index). */
const val JUUJARVIS = "juujarvis"

/** Why a message is handed to Juujarvis. */
object Purpose {
    /** Mail to the assistant's own address: Juujarvis reads and answers it (as untrusted content). */
    const val ASSISTANT = "assistant"
    /** Mail to a monitored address (e.g. info@): Juujarvis only notifies the owner, never interprets it. */
    const val NOTIFY = "notify"
}

enum class Direction { INBOUND, OUTBOUND }

data class AttachmentInfo(
    val index: Int,
    val name: String,
    val contentType: String,
    val size: Long,
    @get:JsonIgnore val key: String? = null
)

/**
 * One message in one mailbox. [box] is "<channel>#<address>" with the address in its exact original
 * case, so Alex@ and alex@ are different mailboxes.
 */
data class Message(
    val id: String,
    val channel: String,
    val direction: Direction,
    @get:JsonIgnore val box: String,
    val from: String,
    val fromName: String? = null,
    val to: String,
    val subject: String? = null,
    val text: String,
    val latest: String? = null,
    val preview: String? = null,
    val timestamp: Instant,
    val status: String,
    val attachments: List<AttachmentInfo> = emptyList(),
    val auth: Map<String, String> = emptyMap(),
    val messageId: String? = null,
    val inReplyTo: String? = null,
    val references: String? = null,
    val purpose: String? = null,
    @get:JsonIgnore val pendingFor: String? = null,
    @get:JsonIgnore val textKey: String? = null,
    @get:JsonIgnore val rawKey: String? = null,
    @get:JsonIgnore val providerId: String? = null,
    @get:JsonIgnore val expiresAt: Instant? = null
)

class AttachmentContent(val info: AttachmentInfo, val stream: InputStream)

/** Published when a message starts waiting for Juujarvis, so connected clients are told immediately. */
data class MessageAvailableEvent(val message: Message)
