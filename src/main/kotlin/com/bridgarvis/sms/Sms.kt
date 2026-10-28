package com.bridgarvis.sms

import java.time.Instant

/** US phone numbers in E.164 ("+12145550123"); anything else is rejected. */
object Phone {
    fun normalize(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() } ?: return null
        return when {
            raw.trim().startsWith("+") && digits.length == 11 && digits.startsWith("1") -> "+$digits"
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> null
        }
    }

    /** For logs: only the last four digits. */
    fun masked(number: String) = "…" + number.takeLast(4)
}

/**
 * Keywords the number answers by itself (AWS keyword settings). The text is matched here because AWS's
 * messageKeyword field names the number's registered keyword, not the word that was sent.
 */
enum class SmsKeyword {
    STOP, JOIN, HELP;

    companion object {
        private val stop = setOf("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT", "OPTOUT", "OPT-OUT", "REVOKE")
        private val join = setOf("JOIN", "START", "UNSTOP")
        private val help = setOf("HELP", "INFO")

        fun of(text: String): SmsKeyword? = when (text.trim().trimEnd('.', '!').uppercase()) {
            in stop -> STOP
            in join -> JOIN
            in help -> HELP
            else -> null
        }
    }
}

enum class Consent { OPTED_IN, OPTED_OUT }

data class SmsConsentRecord(val number: String, val consent: Consent, val source: String, val updatedAt: Instant)

/** Who agreed to receive texts. Only OPTED_IN numbers are texted (the registration promises this). */
interface SmsConsentStore {
    fun get(number: String): SmsConsentRecord?
    fun set(number: String, consent: Consent, source: String)
    fun all(): List<SmsConsentRecord>
}

/** Why a text was not sent. Juujarvis uses [code] to decide what to do with that number. */
class SmsRejected(val code: String, val detail: String? = null) : RuntimeException(detail ?: code) {
    companion object {
        const val OPTED_OUT = "opted_out"
        const val NOT_OPTED_IN = "not_opted_in"
        const val RATE_LIMITED = "rate_limited"
        const val INVALID_NUMBER = "invalid_number"
        const val SEND_FAILED = "send_failed"
    }
}

/** Hands one text to the carrier side. Returns the provider's message id. */
interface SmsSender {
    val live: Boolean
    fun send(to: String, text: String): String
    /** Numbers on the provider's opt-out list. */
    fun optedOutNumbers(): Set<String> = emptySet()
}
