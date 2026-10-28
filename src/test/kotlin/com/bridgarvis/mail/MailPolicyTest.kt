package com.bridgarvis.mail

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailPolicyTest {
    private fun v(dmarc: String, dkim: String = "PASS", spam: String = "PASS", virus: String = "PASS") =
        mapOf("dmarc" to dmarc, "dkim" to dkim, "spf" to "PASS", "spam" to spam, "virus" to virus)

    @Test
    fun `trust requires authentication and no spam or virus`() {
        assertTrue(MailPolicy.isTrusted(v("PASS")))
        assertTrue(MailPolicy.isTrusted(v("GRAY", dkim = "PASS")))      // e.g. p=none domains with aligned DKIM
        assertFalse(MailPolicy.isTrusted(v("GRAY", dkim = "GRAY")))     // only SPF: not enough
        assertFalse(MailPolicy.isTrusted(v("FAIL")))
        assertFalse(MailPolicy.isTrusted(v("PROCESSING_FAILED")))
        assertFalse(MailPolicy.isTrusted(v("PASS", spam = "FAIL")))
        assertFalse(MailPolicy.isTrusted(v("PASS", virus = "FAIL")))
        assertFalse(MailPolicy.isTrusted(emptyMap()))
    }

    @Test
    fun `allowed sender matches domain and subdomains only`() {
        val d = listOf("exampleschool.org", "schoolnotices.example")
        assertTrue(MailPolicy.isAllowedSender("teacher@exampleschool.org", d))
        assertTrue(MailPolicy.isAllowedSender("student@g.exampleschool.org", d))
        assertTrue(MailPolicy.isAllowedSender("donotreply+abc@schoolnotices.example", d))
        assertFalse(MailPolicy.isAllowedSender("x@evilexampleschool.org", d))
        assertFalse(MailPolicy.isAllowedSender("x@exampleschool.org.evil.net", d))
        assertFalse(MailPolicy.isAllowedSender(null, d))
        assertFalse(MailPolicy.isAllowedSender("no-at-sign", d))
        val withAddress = d + "parent@mail.example"
        assertTrue(MailPolicy.isAllowedSender("Parent@Mail.Example", withAddress))     // exact address, any case
        assertFalse(MailPolicy.isAllowedSender("other@mail.example", withAddress))     // not the whole domain
    }

    @Test
    fun `mailbox patterns match exact addresses or a local-part prefix in one domain`() {
        kotlin.test.assertTrue(MailPolicy.matchesMailbox("Customer@Example.com", "customer@example.com"))
        listOf("customer@example.com", "customer1@example.com", "Customer-QA@example.com")
            .forEach { kotlin.test.assertTrue(MailPolicy.matchesMailbox(it, "customer*@example.com"), it) }
        listOf("customer1@other.com", "customer1@sub.example.com", "xcustomer@example.com", "info@example.com",
            "customer@example.com@evil.com")
            .forEach { kotlin.test.assertFalse(MailPolicy.matchesMailbox(it, "customer*@example.com"), it) }
        kotlin.test.assertFalse(MailPolicy.matchesMailbox("a@example.com", "*@example.com".replace("*", "*x*")))
        kotlin.test.assertFalse(MailPolicy.matchesMailbox("customer@example.com", "customer@*.com"))
    }
}
