package com.bridgarvis.store

import com.bridgarvis.message.AttachmentContent
import com.bridgarvis.message.Message

interface MessageStore {
    /** Saves (or overwrites) a message; announces it when it is pending for Juujarvis. */
    fun save(message: Message)

    /** Messages waiting for Juujarvis, oldest first. */
    fun pending(): List<Message>

    /** A message waiting for Juujarvis, with its full text. */
    fun get(id: String): Message?

    /** Marks a pending message as accepted by Juujarvis. False if it is not pending. */
    fun ack(id: String): Boolean

    fun openAttachment(id: String, index: Int): AttachmentContent?

    /** Recent outbound messages, newest first. */
    fun outbox(): List<Message>

    // ---- web UI (any message, not only pending ones)

    /** Newest messages of a channel, newest first. */
    fun recent(channel: String, limit: Int): List<Message>

    /** One message with its full text. */
    fun find(box: String, id: String): Message?

    fun openAttachment(box: String, id: String, index: Int): AttachmentContent?

    /**
     * Deletes one mailbox's copy of a message. Stored objects (raw mail, long text, attachments) are
     * removed once no other mailbox holds a copy of the same message.
     */
    fun delete(box: String, id: String): Boolean
}
