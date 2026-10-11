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
}
