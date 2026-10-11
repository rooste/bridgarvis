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
        return authenticated && isClean(verdicts)
    }

    /**
     * Sender matches an allowlist entry: an exact address ("someone@example.com") or a domain, which also
     * covers its subdomains ("exampleschool.org" allows "g.exampleschool.org"). Only meaningful together with
     * [isTrusted], which proves the From domain.
     */
    fun isAllowedSender(fromAddress: String?, allowed: List<String>): Boolean {
        val address = fromAddress?.lowercase()?.trim()?.takeIf { it.contains('@') } ?: return false
        val domain = address.substringAfterLast('@').ifBlank { return false }
        return allowed.map { it.lowercase().trim() }.any { entry ->
            if (entry.contains('@')) address == entry else domain == entry || domain.endsWith(".$entry")
        }
    }

    /** Not flagged as spam or virus. Enough for notify-only mailboxes, where strangers write legitimately. */
    fun isClean(verdicts: Map<String, String>): Boolean =
        verdicts["spam"] != "FAIL" && verdicts["virus"] != "FAIL"
}
