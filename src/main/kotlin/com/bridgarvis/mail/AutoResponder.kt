package com.bridgarvis.mail

import com.bridgarvis.message.Message
import com.bridgarvis.outbound.EmailSender
import com.bridgarvis.outbound.OutgoingEmail
import com.bridgarvis.store.MessageStore
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Test mailboxes for a mail-sending service under development (e.g. a "customer" address). Mail to one of
 * them from an allowlisted sender domain that passes authentication is handled so the service's tests can
 * check delivery and unsubscribe handling end to end:
 *
 *   every mail                   → reply "response " + its first line (e.g. "response test message X")
 *   the word "halt" anywhere     → GET of the mail's visible "unsubscribe" link
 *
 * "halt" is used because it is not in the service's footers ("stop" is: "to stop receiving messages"). Replies go
 * from the test mailbox to the sender's Reply-To (or From) within the allowlisted domains only. Links are
 * fetched through [PublicHttp] (public addresses only). Both actions are rate limited. The outcome is
 * recorded on the stored mail (context "autoReply" / "unsubscribe").
 */
@Component
class AutoResponder(
    private val sender: EmailSender,
    private val store: MessageStore,
    @Value("\${bridgarvis.mail.responder.addresses:}") addresses: String,
    @Value("\${bridgarvis.mail.responder.sender-domains:}") senderDomains: String,
    @Value("\${bridgarvis.mail.responder.max-per-hour:50}") private val maxPerHour: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mailboxes = addresses.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    private val domains = senderDomains.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mail-responder").apply { isDaemon = true } }
    private val replies = ArrayDeque<Instant>()
    private val fetches = ArrayDeque<Instant>()
    private val handled = LinkedHashSet<String>()

    /** Fetches an unsubscribe link (public addresses only); replaceable in tests. */
    // The click-tracking request itself records the unsubscribe; where it redirects to doesn't matter
    internal var fetch: (String) -> FetchResult = PublicHttp(maxRedirects = 0)::get

    fun handles(recipient: String) = mailboxes.any { MailPolicy.matchesMailbox(recipient, it) }

    /** Queues a stored mail for handling; returns at once (the unsubscribe request may take seconds). */
    fun submit(message: Message, parsed: ParsedMail, trusted: Boolean) {
        if (!handles(message.to)) return
        executor.execute {
            try {
                handle(message, parsed, trusted)
            } catch (e: Exception) {
                log.warn("Auto-responder failed for mail {}: {}", message.id, e.toString())
            }
        }
    }

    /** Handles one mail now. Returns what was done, for tests and the stored context. */
    fun handle(message: Message, parsed: ParsedMail, trusted: Boolean): Map<String, String> {
        if (!handles(message.to)) return emptyMap()
        val from = parsed.from?.address
        if (!trusted || !MailPolicy.isAllowedSender(from, domains)) {
            log.info("Auto-responder ignored mail {}: sender not allowlisted or not authenticated", message.id)
            return emptyMap()
        }
        synchronized(handled) {
            if (!handled.add(message.id)) return emptyMap()
            if (handled.size > 1000) handled.remove(handled.first())
        }
        val firstLine = parsed.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200) ?: ""

        val outcome = linkedMapOf<String, String>()
        if (UNSUBSCRIBE_WORD.containsMatchIn(parsed.text)) outcome["unsubscribe"] = unsubscribe(parsed)
        outcome["autoReply"] = reply(message, parsed, "response $firstLine".trim())
        store.save(message.copy(context = message.context + outcome))
        return outcome
    }

    private fun unsubscribe(parsed: ParsedMail): String {
        val link = parsed.links.firstOrNull { it.text.contains("unsubscribe", ignoreCase = true) }
            ?: parsed.links.firstOrNull { it.href.contains("unsub", ignoreCase = true) }
            ?: return "no unsubscribe link found"
        if (!allow(fetches)) return "not attempted, rate limit reached"
        return try {
            val result = fetch(link.href)
            log.info("Unsubscribe link fetched: {} at {} after {} redirect(s)", result.status, result.host, result.redirects)
            "GET → ${result.status} at ${result.host}" + if (result.redirects > 0) " (${result.redirects} redirect(s))" else ""
        } catch (e: RefusedUrlException) {
            log.info("Unsubscribe link refused: {}", e.message)
            "refused: ${e.message}"
        } catch (e: Exception) {
            log.info("Unsubscribe request failed: {}", e.toString())
            "failed: ${e.javaClass.simpleName}"
        }
    }

    private fun reply(message: Message, parsed: ParsedMail, text: String): String {
        val target = parsed.replyTo?.address?.takeIf { MailPolicy.isAllowedSender(it, domains) } ?: parsed.from!!.address
        if (!allow(replies)) return "not sent, rate limit reached"
        val subject = (parsed.subject ?: "").replace(Regex("[\\r\\n]+"), " ").trim()
            .let { if (it.isEmpty()) "Re: test" else if (it.startsWith("re:", ignoreCase = true)) it else "Re: $it" }
            .take(300)
        val msgId = parsed.messageId?.trim()?.takeIf { MESSAGE_ID.matches(it) }
        val references = listOfNotNull(parsed.references, msgId).joinToString(" ")
            .split(Regex("\\s+")).filter { MESSAGE_ID.matches(it) }.takeLast(20).joinToString(" ").ifBlank { null }
        return try {
            val sent = sender.send(
                OutgoingEmail(to = listOf(target), subject = subject, text = text, inReplyTo = msgId, references = references),
                from = message.to.lowercase(),
                fromName = message.to.substringBefore('@').replaceFirstChar { it.uppercase() }
            )
            log.info("Auto-reply {} sent for mail {}", sent.id, message.id)
            "sent"
        } catch (e: Exception) {
            log.warn("Auto-reply for mail {} failed: {}", message.id, e.toString())
            "failed: ${e.javaClass.simpleName}"
        }
    }

    private fun allow(window: ArrayDeque<Instant>): Boolean = synchronized(window) {
        val now = Instant.now()
        while (window.isNotEmpty() && window.first().isBefore(now.minus(Duration.ofHours(1)))) window.removeFirst()
        if (window.size >= maxPerHour) false else { window.addLast(now); true }
    }

    @PreDestroy
    fun stop() = executor.shutdownNow()

    companion object {
        private val UNSUBSCRIBE_WORD = Regex("\\bhalt\\b", RegexOption.IGNORE_CASE)
        private val MESSAGE_ID = Regex("^<[^<>\\s]{1,250}>$")
    }
}
