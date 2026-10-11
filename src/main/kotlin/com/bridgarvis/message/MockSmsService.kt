package com.bridgarvis.message

import com.bridgarvis.store.MessageStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Stand-in for SMS until the toll-free number is approved: inbound SMS are simulated through
 * /api/mock/inbound and sends are only recorded.
 */
@Component
class MockSmsService(
    private val store: MessageStore,
    @Value("\${bridgarvis.sms.number:+15550000000}") private val ownNumber: String,
    @Value("\${bridgarvis.mail.retention-days:365}") private val retentionDays: Long
) {
    private fun expiry(now: Instant) = now.plus(Duration.ofDays(retentionDays))

    fun receive(from: String, text: String): Message = Message(
        id = Ulid.random(),
        channel = "sms",
        direction = Direction.INBOUND,
        box = "sms#$ownNumber",
        from = from,
        to = ownNumber,
        text = text,
        preview = text.take(200),
        timestamp = Instant.now(),
        status = "RECEIVED",
        pendingFor = JUUJARVIS,
        expiresAt = expiry(Instant.now())
    ).also { store.save(it) }

    fun send(to: String, text: String): Message = Message(
        id = Ulid.random(),
        channel = "sms",
        direction = Direction.OUTBOUND,
        box = "sms#$ownNumber",
        from = ownNumber,
        to = to,
        text = text,
        timestamp = Instant.now(),
        status = "MOCK_SENT",
        expiresAt = expiry(Instant.now())
    ).also { store.save(it) }
}
