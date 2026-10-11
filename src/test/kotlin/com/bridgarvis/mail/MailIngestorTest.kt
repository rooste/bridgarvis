package com.bridgarvis.mail

import com.bridgarvis.message.AttachmentContent
import com.bridgarvis.message.JUUJARVIS
import com.bridgarvis.message.Message
import com.bridgarvis.store.MessageStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.sqs.SqsClient
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailIngestorTest {

    private val raw = """
        From: "Mom" <mom@example.com>
        To: Juujarvis@in.example.com, Alex@in.example.com
        Subject: Groceries
        Content-Type: multipart/mixed; boundary="B"

        --B
        Content-Type: text/plain

        Please add milk
        --B
        Content-Type: image/png
        Content-Disposition: attachment; filename="list.png"
        Content-Transfer-Encoding: base64

        iVBORw0KGgo=
        --B--
    """.trimIndent().replace("\n", "\r\n").toByteArray()

    private val puts = mutableListOf<String>()

    /** Minimal S3 fake: returns [raw] for any get and records put keys. */
    private val s3 = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(S3Client::class.java)) { _, method, _ ->
        when (method.name) {
            "getObjectAsBytes" -> ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), raw)
            "putObject" -> { puts += "put"; null }
            "close" -> null
            else -> throw UnsupportedOperationException(method.name)
        }
    } as S3Client

    private val sqs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SqsClient::class.java)) { _, m, _ ->
        throw UnsupportedOperationException(m.name)
    } as SqsClient

    private class RecordingStore : MessageStore {
        val saved = mutableListOf<Message>()
        override fun save(message: Message) { saved += message }
        override fun pending() = saved.filter { it.pendingFor == JUUJARVIS }
        override fun get(id: String) = null
        override fun ack(id: String) = false
        override fun openAttachment(id: String, index: Int): AttachmentContent? = null
        override fun outbox() = emptyList<Message>()
        override fun recent(channel: String, limit: Int) = saved.filter { it.channel == channel }
        override fun find(box: String, id: String) = saved.firstOrNull { it.box == box && it.id == id }
        override fun openAttachment(box: String, id: String, index: Int): AttachmentContent? = null
        override fun delete(box: String, id: String) = saved.removeIf { it.box == box && it.id == id }
        override fun findByProvider(box: String, providerId: String) = saved.firstOrNull { it.box == box && it.providerId == providerId }
        override fun updateDelivery(box: String, id: String, status: String, detail: String?) {}
    }

    private fun notification(dmarc: String, recipients: List<String>) = jacksonObjectMapper().writeValueAsString(mapOf(
        "notificationType" to "Received",
        "mail" to mapOf("messageId" to "ses-123", "timestamp" to "2026-10-11T18:00:00.000Z", "source" to "mom@example.com"),
        "receipt" to mapOf(
            "recipients" to recipients,
            "action" to mapOf("type" to "S3", "bucketName" to "b", "objectKey" to "inbound/ses-123"),
            "spfVerdict" to mapOf("status" to "PASS"),
            "dkimVerdict" to mapOf("status" to "PASS"),
            "dmarcVerdict" to mapOf("status" to dmarc),
            "spamVerdict" to mapOf("status" to "PASS"),
            "virusVerdict" to mapOf("status" to "PASS"),
        )
    ))

    private fun ingestor(store: MessageStore) =
        MailIngestor(sqs, s3, store, jacksonObjectMapper(), "q", "b", "juujarvis@in.example.com", "info@in.example.com",
            "school@in.example.com", "example.com", 365)

    @Test
    fun `trusted mail is stored per mailbox in exact case and only the juujarvis copy is pending`() {
        val store = RecordingStore()
        ingestor(store).process(notification("GRAY", listOf("Juujarvis@in.example.com", "Alex@in.example.com")))

        assertEquals(listOf("email#Juujarvis@in.example.com", "email#Alex@in.example.com"), store.saved.map { it.box })
        val j = store.saved[0]
        assertEquals(JUUJARVIS, j.pendingFor)
        assertEquals("RECEIVED", j.status)
        assertEquals("Please add milk", j.text)
        assertEquals("mom@example.com", j.from)
        assertEquals("Mom", j.fromName)
        assertEquals("list.png", j.attachments.single().name)
        assertEquals("inbound/ses-123", j.rawKey)
        assertNull(store.saved[1].pendingFor)
        assertEquals("assistant", j.purpose)
        assertEquals(1, puts.size)  // attachment uploaded once, shared by both items
        assertEquals(store.saved[0].id, store.saved[1].id)
    }

    @Test
    fun `unauthenticated mail to juujarvis is quarantined and not pending`() {
        val store = RecordingStore()
        ingestor(store).process(notification("FAIL", listOf("juujarvis@in.example.com")))
        assertEquals("QUARANTINED", store.saved.single().status)
        assertNull(store.saved.single().pendingFor)
    }

    @Test
    fun `info mailbox is notify-only and accepts unauthenticated but clean mail`() {
        val store = RecordingStore()
        ingestor(store).process(notification("FAIL", listOf("Info@in.example.com", "random@in.example.com")))
        val info = store.saved.first { it.box == "email#Info@in.example.com" }
        assertEquals("notify", info.purpose)
        assertEquals(JUUJARVIS, info.pendingFor)
        assertEquals("RECEIVED", info.status)
        val other = store.saved.first { it.box == "email#random@in.example.com" }
        assertNull(other.purpose)
        assertNull(other.pendingFor)
    }

    @Test
    fun `school mailbox processes authenticated allowlisted senders and only notifies about others`() {
        val trusted = RecordingStore().also { ingestor(it).process(notification("PASS", listOf("school@in.example.com"))) }
        assertEquals("school", trusted.saved.single().purpose)             // mom@example.com is on the allowlist
        assertEquals(JUUJARVIS, trusted.saved.single().pendingFor)

        val forged = RecordingStore().also { ingestor(it).process(notification("FAIL", listOf("school@in.example.com"))) }
        assertEquals("notify", forged.saved.single().purpose)              // unauthenticated: owner is told, model is not
        assertEquals(JUUJARVIS, forged.saved.single().pendingFor)

        val store = RecordingStore()
        MailIngestor(sqs, s3, store, jacksonObjectMapper(), "q", "b", "", "", "school@in.example.com", "district.example.org", 365)
            .process(notification("PASS", listOf("school@in.example.com")))
        assertEquals("notify", store.saved.single().purpose)               // authenticated but not a school domain
    }

    @Test
    fun `retried notification produces the same id`() {
        val a = RecordingStore().also { ingestor(it).process(notification("PASS", listOf("x@in.example.com"))) }
        val b = RecordingStore().also { ingestor(it).process(notification("PASS", listOf("x@in.example.com"))) }
        assertEquals(a.saved.single().id, b.saved.single().id)
        assertTrue(a.saved.single().expiresAt!! > a.saved.single().timestamp)
    }
}
