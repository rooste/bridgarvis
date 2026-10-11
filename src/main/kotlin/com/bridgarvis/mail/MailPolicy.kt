package com.bridgarvis.mail

/**
 * Decides whether a received mail is trustworthy enough to reach Juujarvis, based on SES verdicts.
 * Juujarvis still checks the sender against its family members; this only filters forgeries and junk.
 */
object MailPolicy {
    /**
     * DMARC PASS, or GRAY with an aligned DKIM PASS. GRAY means the sender's domain publishes no DMARC
     * policy or p=none (common for large mailbox providers), so aligned DKIM is what proves the From
     * domain. Spam or virus FAIL always blocks.
     */
    fun isTrusted(verdicts: Map<String, String>): Boolean {
        val dmarc = verdicts["dmarc"]
        val dkim = verdicts["dkim"]
        val authenticated = dmarc == "PASS" || (dmarc == "GRAY" && dkim == "PASS")
        return authenticated && verdicts["spam"] != "FAIL" && verdicts["virus"] != "FAIL"
    }
}
