package com.bridgarvis.mail

import com.bridgarvis.message.Direction
import com.bridgarvis.message.Message
import com.bridgarvis.outbound.EmailSender
import com.bridgarvis.outbound.OutgoingEmail
import com.bridgarvis.store.MemoryMessageStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoResponderTest {
    private val store = MemoryMessageStore { }
    private val sent = mutableListOf<Triple<OutgoingEmail, String?, String>>()
    private val sender = object : EmailSender {
        override fun send(mail: OutgoingEmail, from: String?, fromName: String): Message {
            sent += Triple(mail, from, fromName)
            return Message("out-${sent.size}", "email", Direction.OUTBOUND, "email#$from", from ?: "", to = mail.to.first(),
                text = mail.text, timestamp = Instant.now(), status = "MOCK_SENT")
        }
    }
    private val fetched = mutableListOf<String>()

    private fun responder(max: Int = 50) = AutoResponder(sender, store, "Customer*@in.example.com", "crm.example.com", max).apply {
        fetch = { url -> fetched += url; FetchResult(200, "unsubscribe.crm.example.com", 1) }
    }

    private var ids = 0
    private fun mail(text: String, from: String = "message@app.crm.example.com", replyTo: String? = null,
                     links: List<MailLink> = emptyList()): Pair<Message, ParsedMail> {
        val id = "m${++ids}"
        val parsed = ParsedMail(MailAddress(from, null), replyTo?.let { MailAddress(it, null) }, listOf(), listOf(),
            "Hello", null, "<$id@crm.example.com>", null, null, text, text, emptyList(), false, links)
        return Message(id, "email", Direction.INBOUND, "email#customer@in.example.com", from,
            to = "customer@in.example.com", text = text, timestamp = Instant.now(), status = "RECEIVED") to parsed
    }

    @Test
    fun `test message on the first line is answered from the test mailbox`() {
        val (m, p) = mail("test message 10-16\nrest of the mail\nunsubscribe")
        assertEquals(mapOf("autoReply" to "sent"), responder().handle(m, p, trusted = true))
        val (out, from, name) = sent.single()
        assertEquals("response test message 10-16", out.text)
        assertEquals(listOf("message@app.crm.example.com"), out.to)
        assertEquals("Re: Hello", out.subject)
        assertEquals("<m1@crm.example.com>", out.inReplyTo)
        assertEquals("customer@in.example.com", from)
        assertEquals("Customer", name)
        assertTrue(fetched.isEmpty())
        assertEquals("sent", store.find(m.box, m.id)?.context?.get("autoReply"))
    }

    @Test
    fun `the word halt anywhere fetches the visible unsubscribe link`() {
        val links = listOf(MailLink("Get started", "https://app.crm.example.com/x"), MailLink("Unsubscribe", "https://go.sp.example/f/a/abc"))
        val (m, p) = mail("test message 2 HALT\nClick unsubscribe to stop receiving messages", links = links)
        val outcome = responder().handle(m, p, trusted = true)
        assertEquals(listOf("https://go.sp.example/f/a/abc"), fetched)
        assertEquals("GET → 200 at unsubscribe.crm.example.com (1 redirect(s))", outcome["unsubscribe"])
        assertEquals("response test message 2 HALT", sent.single().first.text)
        val (m2, p2) = mail("please halt it", links = links)
        assertEquals(setOf("unsubscribe", "autoReply"), responder().handle(m2, p2, trusted = true).keys)
        val (m3, p3) = mail("asphalt, halting, stop", links = links)   // not the word halt; stop isn't a trigger
        assertEquals(setOf("autoReply"), responder().handle(m3, p3, trusted = true).keys)
        assertEquals(2, fetched.size)
        assertEquals(3, sent.size)
    }

    @Test
    fun `any mail is answered, but strangers, unauthenticated mail and other mailboxes do nothing`() {
        val r = responder()
        val unsubLink = listOf(MailLink("unsubscribe", "https://go.sp.example/f/a/abc"))
        val (m0, p0) = mail("LDT email-roundtrip run abc-123\nunsub\nClick unsubscribe to stop receiving", links = unsubLink)
        assertEquals(mapOf("autoReply" to "sent"), r.handle(m0, p0, trusted = true))
        assertEquals("response LDT email-roundtrip run abc-123", sent.single().first.text)
        assertTrue(fetched.isEmpty())
        sent.clear()
        val (m1, p1) = mail("test message 1", from = "evil@attacker.example")
        assertEquals(emptyMap(), r.handle(m1, p1, trusted = true))
        val (m2, p2) = mail("test message 2")
        assertEquals(emptyMap(), r.handle(m2, p2, trusted = false))
        val (m3, p3) = mail("test message 3")
        assertEquals(emptyMap(), r.handle(m3.copy(to = "info@in.example.com"), p3, trusted = true))
        val (m4, p4) = mail("test message 4")
        assertEquals(mapOf("autoReply" to "sent"), r.handle(m4.copy(to = "Customer-QA@in.example.com"), p4, trusted = true))
        assertEquals("customer-qa@in.example.com", sent.single().second)
        sent.clear()
        assertTrue(sent.isEmpty() && fetched.isEmpty())
    }

    @Test
    fun `reply-to outside the allowlist is ignored, duplicates and the rate limit stop repeats`() {
        val r = responder(max = 1)
        val (m, p) = mail("test message a", replyTo = "victim@elsewhere.example")
        r.handle(m, p, trusted = true)
        assertEquals(listOf("message@app.crm.example.com"), sent.single().first.to)
        assertEquals(emptyMap(), r.handle(m, p, trusted = true))                 // same mail again
        val (m2, p2) = mail("test message b")
        assertEquals("not sent, rate limit reached", r.handle(m2, p2, trusted = true)["autoReply"])
        assertEquals(1, sent.size)
    }
}
