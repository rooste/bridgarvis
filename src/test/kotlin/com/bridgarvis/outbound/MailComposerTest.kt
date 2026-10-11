package com.bridgarvis.outbound

import com.bridgarvis.mail.MailParser
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailComposerTest {
    private val base = OutgoingEmail(to = listOf("alex@example.com"), subject = "Re: Hyvää päivää", text = "Hei!\nRivi kaksi ✓")

    @Test
    fun `composed mail round-trips through the parser with threading headers`() {
        val raw = MailComposer.compose("assistant@example.com", "Juujarvis", "<id1@example.com>",
            base.copy(cc = listOf("mom@example.com"), inReplyTo = "<orig@example.com>", references = "<a@example.com> <orig@example.com>"),
            ZonedDateTime.parse("2026-10-11T12:00:00-05:00"))
        val text = String(raw, Charsets.US_ASCII)
        assertTrue(text.contains("\r\nIn-Reply-To: <orig@example.com>\r\n"))
        assertTrue(text.contains("\r\nReferences: <a@example.com> <orig@example.com>\r\n"))
        assertTrue(text.startsWith("From: Juujarvis <assistant@example.com>\r\nTo: alex@example.com\r\nCc: mom@example.com\r\n"))

        val parsed = MailParser().parse(raw)
        assertEquals("Re: Hyvää päivää", parsed.subject)
        assertEquals("Hei!\nRivi kaksi ✓", parsed.text)
        assertEquals("<id1@example.com>", parsed.messageId)
        assertEquals(listOf("alex@example.com"), parsed.to.map { it.address })
    }

    @Test
    fun `header injection and invalid input are rejected`() {
        val bad = listOf(
            base.copy(to = listOf("alex@example.com\r\nBcc: evil@example.com")),
            base.copy(to = listOf("alex@example.com, evil@example.com")),
            base.copy(to = listOf("not-an-address")),
            base.copy(to = emptyList()),
            base.copy(to = (1..11).map { "u$it@example.com" }),
            base.copy(subject = "Hello\r\nBcc: evil@example.com"),
            base.copy(subject = " "),
            base.copy(text = ""),
            base.copy(inReplyTo = "<x@example.com>\r\nBcc: evil@example.com"),
            base.copy(references = "<a@example.com> not-a-message-id"),
        )
        bad.forEach { assertFailsWith<InvalidEmailException> { MailComposer.validate(it) } }
    }

    @Test
    fun `delivery status keeps the most significant event`() {
        val m = jacksonObjectMapper()
        val bounce = m.readTree("""{"eventType":"Bounce","bounce":{"bounceType":"Permanent","bouncedRecipients":[{"emailAddress":"x@example.com","diagnosticCode":"550 no such user"}]}}""")
        assertEquals("BOUNCED" to "Permanent bounce: x@example.com (550 no such user)", DeliveryStatus.fromEvent(bounce))
        assertEquals("DELIVERED", DeliveryStatus.fromEvent(m.readTree("""{"eventType":"Delivery"}"""))?.first)
        assertEquals(null, DeliveryStatus.fromEvent(m.readTree("""{"eventType":"Open"}""")))
        assertTrue(DeliveryStatus.shouldReplace("SENT", "DELIVERED"))
        assertTrue(DeliveryStatus.shouldReplace("DELIVERED", "BOUNCED"))
        assertFalse(DeliveryStatus.shouldReplace("DELIVERED", "DELAYED"))
        assertFalse(DeliveryStatus.shouldReplace("COMPLAINT", "DELIVERED"))
    }
}
