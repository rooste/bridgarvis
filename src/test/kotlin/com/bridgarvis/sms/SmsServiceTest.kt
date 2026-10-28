package com.bridgarvis.sms

import com.bridgarvis.message.JUUJARVIS
import com.bridgarvis.message.Purpose
import com.bridgarvis.store.MemoryMessageStore
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmsServiceTest {
    private val store = MemoryMessageStore { }
    private val consent = MemorySmsConsentStore()
    private val sent = mutableListOf<Pair<String, String>>()
    private var optedOutAtProvider = setOf<String>()
    private var rejectAs: String? = null
    private val sender = object : SmsSender {
        override val live = true
        override fun send(to: String, text: String): String {
            rejectAs?.let { throw SmsRejected(it) }
            sent += to to text
            return "provider-${sent.size}"
        }
        override fun optedOutNumbers() = optedOutAtProvider
    }
    private val sms = SmsService(store, consent, sender, "+18005550100", Duration.ofDays(365), maxPerHour = 5, maxPerNumberPerHour = 3)
    private val alex = "+12145550123"

    @Test
    fun `numbers are normalized to US E164`() {
        assertEquals(alex, Phone.normalize("(214) 555-0123"))
        assertEquals(alex, Phone.normalize("1-214-555-0123"))
        assertEquals(alex, Phone.normalize("+1 214 555 0123"))
        assertNull(Phone.normalize("+44 20 7946 0958"))
        assertNull(Phone.normalize("555-0123"))
    }

    @Test
    fun `keywords update consent and reach Juujarvis as keyword events`() {
        val join = sms.receive("(214) 555-0123", " join ", "in-1")!!
        assertEquals(Purpose.KEYWORD, join.purpose)
        assertEquals("JOIN", join.context["keyword"])
        assertEquals(Consent.OPTED_IN, consent.get(alex)?.consent)

        sms.receive(alex, "Stop", "in-2")
        assertEquals(Consent.OPTED_OUT, consent.get(alex)?.consent)
        sms.receive(alex, "help", "in-3")
        assertEquals(Consent.OPTED_OUT, consent.get(alex)?.consent)   // HELP changes nothing

        val question = sms.receive(alex, "Stop by the store?", "in-4")!!
        assertEquals(Purpose.ASSISTANT, question.purpose)               // not a keyword: a sentence
        assertTrue(store.pending().all { it.channel == "sms" && it.from == alex })
        assertEquals(4, store.pending().size)
        assertNull(sms.receive("+44 20 7946 0958", "hello", "in-5"))
    }

    @Test
    fun `redelivered texts are stored once`() {
        val at = java.time.Instant.parse("2026-10-12T20:00:00Z")
        sms.receive(alex, "hello", "same-id", at)
        sms.receive(alex, "hello", "same-id", at)
        assertEquals(1, store.pending().size)
    }

    @Test
    fun `texts go only to numbers that opted in`() {
        assertEquals(SmsRejected.NOT_OPTED_IN, assertFailsWith<SmsRejected> { sms.send(alex, "hi") }.code)
        sms.receive(alex, "JOIN", "in-1")
        assertEquals("SENT", sms.send("214-555-0123", "Dinner at six").status)
        assertEquals(listOf(alex to "Dinner at six"), sent)

        sms.receive(alex, "STOP", "in-2")
        assertEquals(SmsRejected.OPTED_OUT, assertFailsWith<SmsRejected> { sms.send(alex, "hi") }.code)
        assertEquals(SmsRejected.INVALID_NUMBER, assertFailsWith<SmsRejected> { sms.send("12345", "hi") }.code)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a provider opt-out rejection is remembered`() {
        consent.set(alex, Consent.OPTED_IN, "test")
        rejectAs = SmsRejected.OPTED_OUT
        assertEquals(SmsRejected.OPTED_OUT, assertFailsWith<SmsRejected> { sms.send(alex, "hi") }.code)
        assertEquals(Consent.OPTED_OUT, consent.get(alex)?.consent)
        rejectAs = null
        assertEquals(SmsRejected.OPTED_OUT, assertFailsWith<SmsRejected> { sms.send(alex, "hi") }.code)
    }

    @Test
    fun `the opt-out list sync marks numbers opted out`() {
        consent.set(alex, Consent.OPTED_IN, "test")
        optedOutAtProvider = setOf(alex, "+19725550199")
        assertEquals(2, sms.syncOptOuts())
        assertEquals(0, sms.syncOptOuts())
        assertEquals(Consent.OPTED_OUT, consent.get(alex)?.consent)
        assertEquals("opt-out-list", consent.get("+19725550199")?.source)
    }

    @Test
    fun `sending is rate limited per number and overall`() {
        consent.set(alex, Consent.OPTED_IN, "test")
        repeat(3) { sms.send(alex, "m$it") }
        assertEquals(SmsRejected.RATE_LIMITED, assertFailsWith<SmsRejected> { sms.send(alex, "again") }.code)
    }

    @Test
    fun `delivery events update the sent text`() {
        consent.set(alex, Consent.OPTED_IN, "test")
        val message = sms.send(alex, "hi")
        sms.delivery("provider-1", "TEXT_CARRIER_BLOCKED", "Blocked by carrier")
        val outbox = store.outbox().single { it.id == message.id }
        assertEquals("FAILED", outbox.status)
        assertTrue(outbox.deliveryDetail!!.contains("TEXT_CARRIER_BLOCKED"))
        assertTrue(store.pending().none { it.pendingFor == JUUJARVIS && it.id == message.id })
    }
}
