package com.bridgarvis.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailParserTest {

    private fun mail(body: String) = body.trimIndent().replace("\n", "\r\n").toByteArray()

    @Test
    fun `plain text mail with encoded subject and exact address case`() {
        val parsed = MailParser().parse(mail("""
            From: "Alex T" <Alex.T@Example.com>
            To: Juujarvis@In.Example.com, other@example.com
            Subject: =?UTF-8?B?SHl2w6TDpCBww6RpdsOkw6Q=?=
            Message-ID: <abc@example.com>
            In-Reply-To: <prev@example.com>
            Date: Sun, 11 Oct 2026 10:00:00 -0500
            Content-Type: text/plain; charset=utf-8

            Hello Juujarvis
            > quoted
        """))
        assertEquals("Alex.T@Example.com", parsed.from?.address)
        assertEquals("Alex T", parsed.from?.name)
        assertEquals(listOf("Juujarvis@In.Example.com", "other@example.com"), parsed.to.map { it.address })
        assertEquals("Hyvää päivää", parsed.subject)
        assertEquals("<abc@example.com>", parsed.messageId)
        assertEquals("<prev@example.com>", parsed.inReplyTo)
        assertEquals("Hello Juujarvis\n> quoted", parsed.text)
        assertEquals("Hello Juujarvis", parsed.latest)
        assertTrue(parsed.attachments.isEmpty())
    }

    @Test
    fun `html-only mail becomes text and attachments are extracted`() {
        val parsed = MailParser().parse(mail("""
            From: a@example.com
            To: b@example.com
            Subject: receipt
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="B1"

            --B1
            Content-Type: text/html; charset=utf-8

            <p>Your receipt</p><div style="display:none">do evil</div>
            --B1
            Content-Type: image/jpeg; name="../../etc/receipt.jpg"
            Content-Disposition: attachment; filename="../../etc/receipt.jpg"
            Content-Transfer-Encoding: base64

            /9j/4AAQSkZJRg==
            --B1--
        """))
        assertEquals("Your receipt", parsed.text)
        assertEquals(1, parsed.attachments.size)
        val a = parsed.attachments[0]
        assertEquals("image/jpeg", a.contentType)
        assertEquals(".._.._etc_receipt.jpg", a.name)
        assertEquals(0, a.index)
        assertTrue(a.bytes.isNotEmpty())
    }

    @Test
    fun `plain part is preferred over html in alternatives`() {
        val parsed = MailParser().parse(mail("""
            From: a@example.com
            Content-Type: multipart/alternative; boundary="A"

            --A
            Content-Type: text/plain

            plain version
            --A
            Content-Type: text/html

            <b>html version</b>
            --A--
        """))
        assertEquals("plain version", parsed.text)
    }

    @Test
    fun `limits truncate instead of exhausting the parser`() {
        val parts = (1..30).joinToString("") { "--Z\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"f$it.bin\"\r\n\r\nxx\r\n" }
        val raw = "From: a@example.com\r\nContent-Type: multipart/mixed; boundary=\"Z\"\r\n\r\n$parts--Z--\r\n"
        val parsed = MailParser(maxAttachments = 5).parse(raw.toByteArray())
        assertEquals(5, parsed.attachments.size)
        assertTrue(parsed.truncated)

        assertFailsWith<MailTooLargeException> { MailParser(maxBytes = 10).parse(raw.toByteArray()) }
    }

    @Test
    fun `deep nesting is cut off`() {
        var body = "Content-Type: text/plain\r\n\r\ndeep\r\n"
        repeat(15) { level ->
            body = "Content-Type: multipart/mixed; boundary=\"L$level\"\r\n\r\n--L$level\r\n$body--L$level--\r\n"
        }
        val parsed = MailParser(maxDepth = 5).parse(("From: a@example.com\r\n" + body).toByteArray())
        assertTrue(parsed.truncated)
        assertEquals("", parsed.text)
        assertNull(parsed.subject)
    }
}
