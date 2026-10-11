package com.bridgarvis.store

import com.bridgarvis.message.AttachmentContent
import com.bridgarvis.message.Direction
import com.bridgarvis.message.JUUJARVIS
import com.bridgarvis.message.Message
import com.bridgarvis.message.MessageAvailableEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** In-memory store for local runs and tests. Contents are lost on restart. */
@Component
@ConditionalOnProperty("bridgarvis.store", havingValue = "memory", matchIfMissing = true)
class MemoryMessageStore(private val events: ApplicationEventPublisher) : MessageStore {
    private val log = LoggerFactory.getLogger(javaClass)
    private val items = ConcurrentHashMap<String, Message>()

    override fun save(message: Message) {
        items[message.box + "#" + message.id] = message
        log.info("Saved message {} ({})", message.id, message.status)
        if (message.pendingFor == JUUJARVIS) events.publishEvent(MessageAvailableEvent(message))
    }

    override fun pending() = items.values.filter { it.pendingFor == JUUJARVIS }.sortedBy { it.id }

    override fun get(id: String) = items.values.firstOrNull { it.id == id && it.pendingFor == JUUJARVIS }

    override fun ack(id: String): Boolean {
        val entry = items.entries.firstOrNull { it.value.id == id && it.value.pendingFor == JUUJARVIS } ?: return false
        items[entry.key] = entry.value.copy(pendingFor = null, status = "ACKED")
        log.info("Acked message {}", id)
        return true
    }

    override fun openAttachment(id: String, index: Int): AttachmentContent? = null

    override fun outbox() = items.values.filter { it.direction == Direction.OUTBOUND }.sortedByDescending { it.id }

    override fun recent(channel: String, limit: Int) =
        items.values.filter { it.channel == channel }.sortedByDescending { it.id }.take(limit)

    override fun find(box: String, id: String) = items[box + "#" + id]

    override fun openAttachment(box: String, id: String, index: Int): AttachmentContent? = null

    override fun findByProvider(box: String, providerId: String) =
        items.values.firstOrNull { it.box == box && it.providerId == providerId }

    override fun updateDelivery(box: String, id: String, status: String, detail: String?) {
        items.computeIfPresent(box + "#" + id) { _, m -> m.copy(status = status, deliveryDetail = detail) }
    }

    override fun delete(box: String, id: String) = (items.remove(box + "#" + id) != null)
        .also { if (it) log.info("Deleted message {}", id) }
}
