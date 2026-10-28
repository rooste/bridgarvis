package com.bridgarvis.sms

import com.bridgarvis.message.Direction
import com.bridgarvis.message.JUUJARVIS
import com.bridgarvis.message.Message
import com.bridgarvis.message.Purpose
import com.bridgarvis.message.Ulid
import com.bridgarvis.store.MessageStore
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Texts in and out of the toll-free number.
 *
 * In: keyword texts (STOP/JOIN/HELP) update the consent record and reach Juujarvis as keyword events
 * (AWS has already answered them); everything else is a text for Juujarvis to answer.
 *
 * Out: only to numbers that opted in with JOIN, within a rate limit. An opted-out number is reported
 * as [SmsRejected.OPTED_OUT] so Juujarvis stops using it.
 */
class SmsService(
    private val store: MessageStore,
    private val consent: SmsConsentStore,
    private val sender: SmsSender,
    private val ownNumber: String,
    private val retention: Duration,
    private val maxPerHour: Int = 30,
    private val maxPerNumberPerHour: Int = 10,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val box = "sms#$ownNumber"
    private val sent = ConcurrentLinkedDeque<Pair<Instant, String>>()

    /** A text that arrived. [providerId] makes redelivery idempotent. */
    fun receive(fromRaw: String, text: String, providerId: String, receivedAt: Instant = clock.instant()): Message? {
        val from = Phone.normalize(fromRaw) ?: run {
            log.info("SMS from a non-US or invalid number ignored")
            return null
        }
        val keyword = SmsKeyword.of(text)
        when (keyword) {
            SmsKeyword.STOP -> consent.set(from, Consent.OPTED_OUT, "keyword")
            SmsKeyword.JOIN -> consent.set(from, Consent.OPTED_IN, "keyword")
            else -> {}
        }
        val message = Message(
            id = Ulid.deterministic(receivedAt, providerId),
            channel = "sms",
            direction = Direction.INBOUND,
            box = box,
            from = from,
            to = ownNumber,
            text = text.take(2000),
            preview = text.take(200),
            timestamp = receivedAt,
            status = "RECEIVED",
            purpose = if (keyword != null) Purpose.KEYWORD else Purpose.ASSISTANT,
            context = keyword?.let { mapOf("keyword" to it.name) } ?: emptyMap(),
            pendingFor = JUUJARVIS,
            providerId = providerId,
            expiresAt = receivedAt.plus(retention)
        )
        store.save(message)
        log.info("SMS {} from {} stored{}", message.id, Phone.masked(from), keyword?.let { " (keyword $it)" } ?: "")
        return message
    }

    /** Sends a text, or throws [SmsRejected] with the reason. */
    fun send(toRaw: String, text: String): Message {
        val to = Phone.normalize(toRaw) ?: throw SmsRejected(SmsRejected.INVALID_NUMBER, "not a US phone number")
        when (consent.get(to)?.consent) {
            Consent.OPTED_OUT -> throw SmsRejected(SmsRejected.OPTED_OUT, "the number has opted out (texted STOP)")
            null -> throw SmsRejected(SmsRejected.NOT_OPTED_IN, "the number has not opted in (text JOIN to the number first)")
            Consent.OPTED_IN -> {}
        }
        reserve(to)
        val providerId = try {
            sender.send(to, text)
        } catch (e: SmsRejected) {
            if (e.code == SmsRejected.OPTED_OUT) consent.set(to, Consent.OPTED_OUT, "send-rejected")
            throw e
        }
        val now = clock.instant()
        return Message(
            id = Ulid.random(now),
            channel = "sms",
            direction = Direction.OUTBOUND,
            box = box,
            from = ownNumber,
            to = to,
            text = text,
            timestamp = now,
            status = if (sender.live) "SENT" else "MOCK_SENT",
            providerId = providerId,
            expiresAt = now.plus(retention)
        ).also {
            store.save(it)
            log.info("SMS {} to {} sent", it.id, Phone.masked(to))
        }
    }

    fun consents(): List<SmsConsentRecord> = consent.all()

    /** Mirrors the provider's opt-out list (catches opt-outs made outside our keyword path). */
    fun syncOptOuts(): Int {
        var changed = 0
        for (number in sender.optedOutNumbers()) {
            if (consent.get(number)?.consent != Consent.OPTED_OUT) {
                consent.set(number, Consent.OPTED_OUT, "opt-out-list")
                changed++
            }
        }
        if (changed > 0) log.info("Opt-out sync: {} number(s) marked opted out", changed)
        return changed
    }

    /** A delivery event for a text we sent (configuration set → SNS → SQS). */
    fun delivery(providerId: String, eventType: String, description: String?) {
        val message = store.findByProvider(box, providerId) ?: return
        val status = when (eventType) {
            "TEXT_DELIVERED" -> "DELIVERED"
            "TEXT_SUCCESSFUL", "TEXT_SENT", "TEXT_QUEUED", "TEXT_PENDING" -> "SENT"
            else -> if (eventType.startsWith("TEXT_")) "FAILED" else return
        }
        if (message.status == "DELIVERED" && status != "DELIVERED") return
        store.updateDelivery(box, message.id, status, if (status == "FAILED") "$eventType: ${description.orEmpty()}".take(300) else null)
        if (status == "FAILED") log.warn("SMS {} failed: {}", message.id, eventType)
    }

    @Synchronized
    private fun reserve(to: String) {
        val now = clock.instant()
        while (sent.peekFirst()?.first?.isBefore(now.minus(Duration.ofHours(1))) == true) sent.pollFirst()
        if (sent.size >= maxPerHour || sent.count { it.second == to } >= maxPerNumberPerHour) {
            throw SmsRejected(SmsRejected.RATE_LIMITED, "too many texts in the last hour")
        }
        sent.addLast(now to to)
    }
}
