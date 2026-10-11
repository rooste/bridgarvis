package com.bridgarvis.store

import com.bridgarvis.message.AttachmentContent
import com.bridgarvis.message.AttachmentInfo
import com.bridgarvis.message.Direction
import com.bridgarvis.message.JUUJARVIS
import com.bridgarvis.message.Message
import com.bridgarvis.message.MessageAvailableEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.s3.S3Client
import java.time.Instant

/**
 * DynamoDB table (one item per message and mailbox) plus S3 for long text and attachments.
 * Key: box (S) + id (S, ULID). Indexes: "pending" (pendingFor, id) — sparse; "byTime" (channel, id).
 */
@Component
@ConditionalOnProperty("bridgarvis.store", havingValue = "dynamo")
class DynamoMessageStore(
    private val dynamo: DynamoDbClient,
    private val s3: S3Client,
    private val events: ApplicationEventPublisher,
    @Value("\${bridgarvis.messages-table}") private val table: String,
    @Value("\${bridgarvis.mail.bucket}") private val bucket: String
) : MessageStore {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun save(message: Message) {
        dynamo.putItem { it.tableName(table).item(toItem(message)) }
        log.info("Saved message {} ({})", message.id, message.status)
        if (message.pendingFor == JUUJARVIS) events.publishEvent(MessageAvailableEvent(message))
    }

    override fun pending(): List<Message> =
        dynamo.queryPaginator {
            it.tableName(table).indexName("pending")
                .keyConditionExpression("pendingFor = :p")
                .expressionAttributeValues(mapOf(":p" to s(JUUJARVIS)))
        }.items().take(500).map { fromItem(it) }

    override fun get(id: String): Message? =
        dynamo.query {
            it.tableName(table).indexName("pending")
                .keyConditionExpression("pendingFor = :p AND id = :id")
                .expressionAttributeValues(mapOf(":p" to s(JUUJARVIS), ":id" to s(id)))
        }.items().firstOrNull()?.let { fromItem(it) }?.let { withFullText(it) }

    override fun ack(id: String): Boolean {
        val message = get(id) ?: return false
        return try {
            dynamo.updateItem {
                it.tableName(table)
                    .key(mapOf("box" to s(message.box), "id" to s(id)))
                    .conditionExpression("attribute_exists(pendingFor)")
                    .updateExpression("REMOVE pendingFor SET #st = :acked, ackedAt = :now")
                    .expressionAttributeNames(mapOf("#st" to "status"))
                    .expressionAttributeValues(mapOf(":acked" to s("ACKED"), ":now" to s(Instant.now().toString())))
            }
            log.info("Acked message {}", id)
            true
        } catch (e: ConditionalCheckFailedException) {
            false
        }
    }

    override fun openAttachment(id: String, index: Int): AttachmentContent? {
        val info = get(id)?.attachments?.firstOrNull { it.index == index } ?: return null
        val key = info.key ?: return null
        return AttachmentContent(info, s3.getObject { it.bucket(bucket).key(key) })
    }

    override fun outbox(): List<Message> =
        listOf("sms", "email").flatMap { channel ->
            dynamo.query {
                it.tableName(table).indexName("byTime")
                    .keyConditionExpression("channel = :c")
                    .filterExpression("direction = :d")
                    .expressionAttributeValues(mapOf(":c" to s(channel), ":d" to s(Direction.OUTBOUND.name)))
                    .scanIndexForward(false).limit(50)
            }.items().map { fromItem(it) }
        }.sortedByDescending { it.id }

    override fun recent(channel: String, limit: Int): List<Message> =
        dynamo.query {
            it.tableName(table).indexName("byTime")
                .keyConditionExpression("channel = :c")
                .expressionAttributeValues(mapOf(":c" to s(channel)))
                .scanIndexForward(false).limit(limit)
        }.items().map { fromItem(it) }

    override fun find(box: String, id: String): Message? =
        dynamo.getItem { it.tableName(table).key(mapOf("box" to s(box), "id" to s(id))) }
            .item().takeIf { it.isNotEmpty() }?.let { fromItem(it) }?.let { withFullText(it) }

    override fun openAttachment(box: String, id: String, index: Int): AttachmentContent? {
        val info = find(box, id)?.attachments?.firstOrNull { it.index == index } ?: return null
        val key = info.key ?: return null
        return AttachmentContent(info, s3.getObject { it.bucket(bucket).key(key) })
    }

    override fun delete(box: String, id: String): Boolean {
        val message = find(box, id) ?: return false
        dynamo.deleteItem { it.tableName(table).key(mapOf("box" to s(box), "id" to s(id))) }

        val otherCopies = dynamo.query {
            it.tableName(table).indexName("byTime")
                .keyConditionExpression("channel = :c AND id = :id")
                .expressionAttributeValues(mapOf(":c" to s(message.channel), ":id" to s(id)))
        }.count()
        if (otherCopies == 0) {
            val keys = message.attachments.mapNotNull { it.key } + listOfNotNull(message.textKey, message.rawKey)
            keys.forEach { key -> s3.deleteObject { it.bucket(bucket).key(key) } }
        }
        log.info("Deleted message {} ({} other copies, {} objects removed)", id, otherCopies,
            if (otherCopies == 0) message.attachments.size + listOfNotNull(message.textKey, message.rawKey).size else 0)
        return true
    }

    private fun withFullText(m: Message): Message =
        if (m.textKey != null) m.copy(text = s3.getObjectAsBytes { it.bucket(bucket).key(m.textKey) }.asUtf8String()) else m

    // ---------------------------------------------------------------- item mapping

    private fun toItem(m: Message): Map<String, AttributeValue> = buildMap {
        put("box", s(m.box)); put("id", s(m.id)); put("channel", s(m.channel))
        put("direction", s(m.direction.name)); put("from", s(m.from)); put("to", s(m.to))
        put("text", s(m.text)); put("timestamp", s(m.timestamp.toString())); put("status", s(m.status))
        m.fromName?.let { put("fromName", s(it)) }
        m.subject?.let { put("subject", s(it)) }
        m.latest?.let { put("latest", s(it)) }
        m.preview?.let { put("preview", s(it)) }
        m.messageId?.let { put("messageId", s(it)) }
        m.inReplyTo?.let { put("inReplyTo", s(it)) }
        m.references?.let { put("references", s(it)) }
        m.pendingFor?.let { put("pendingFor", s(it)) }
        m.textKey?.let { put("textKey", s(it)) }
        m.rawKey?.let { put("rawKey", s(it)) }
        m.providerId?.let { put("providerId", s(it)) }
        m.expiresAt?.let { put("expiresAt", AttributeValue.fromN(it.epochSecond.toString())) }
        if (m.auth.isNotEmpty()) put("auth", AttributeValue.fromM(m.auth.mapValues { s(it.value) }))
        if (m.attachments.isNotEmpty()) put("attachments", AttributeValue.fromL(m.attachments.map { a ->
            AttributeValue.fromM(buildMap {
                put("index", AttributeValue.fromN(a.index.toString()))
                put("name", s(a.name)); put("contentType", s(a.contentType))
                put("size", AttributeValue.fromN(a.size.toString()))
                a.key?.let { put("key", s(it)) }
            })
        }))
    }

    private fun fromItem(i: Map<String, AttributeValue>) = Message(
        id = i.str("id")!!,
        channel = i.str("channel")!!,
        direction = Direction.valueOf(i.str("direction")!!),
        box = i.str("box")!!,
        from = i.str("from").orEmpty(),
        fromName = i.str("fromName"),
        to = i.str("to").orEmpty(),
        subject = i.str("subject"),
        text = i.str("text").orEmpty(),
        latest = i.str("latest"),
        preview = i.str("preview"),
        timestamp = Instant.parse(i.str("timestamp")!!),
        status = i.str("status").orEmpty(),
        attachments = i["attachments"]?.l().orEmpty().map { v ->
            val a = v.m()
            AttachmentInfo(a.num("index").toInt(), a.str("name").orEmpty(), a.str("contentType").orEmpty(), a.num("size"), a.str("key"))
        },
        auth = i["auth"]?.m().orEmpty().mapValues { it.value.s() },
        messageId = i.str("messageId"),
        inReplyTo = i.str("inReplyTo"),
        references = i.str("references"),
        pendingFor = i.str("pendingFor"),
        textKey = i.str("textKey"),
        rawKey = i.str("rawKey"),
        providerId = i.str("providerId"),
        expiresAt = i["expiresAt"]?.n()?.let { Instant.ofEpochSecond(it.toLong()) }
    )

    private fun s(value: String) = AttributeValue.fromS(value)
    private fun Map<String, AttributeValue>.str(name: String) = get(name)?.s()
    private fun Map<String, AttributeValue>.num(name: String) = get(name)?.n()?.toLong() ?: 0L
}
