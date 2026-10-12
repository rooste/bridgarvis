package com.bridgarvis.alexa

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * POST /alexa: requests from the Alexa custom skill. Only reachable through the Alexa connector
 * (see [ClientRoleFilter][com.bridgarvis.web.ClientRoleFilter]); every request is verified first.
 */
@RestController
class AlexaController(
    private val verifier: AlexaRequestVerifier,
    private val skill: AlexaSkill
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/alexa")
    fun handle(
        @RequestBody body: ByteArray,
        @RequestHeader("SignatureCertChainUrl", required = false) certChainUrl: String?,
        @RequestHeader("Signature-256", required = false) signature: String?
    ): ResponseEntity<Any> {
        if (body.size > MAX_BODY) return ResponseEntity.badRequest().build()
        val request = try {
            verifier.verify(certChainUrl, signature, body)
        } catch (e: AlexaVerificationException) {
            log.warn("Rejected Alexa request: {}", e.message)
            return ResponseEntity.badRequest().build()
        }
        return ResponseEntity.ok(skill.handle(request))
    }

    companion object {
        private const val MAX_BODY = 128 * 1024
    }
}

data class AlexaReplyRequest(
    val speech: String?,
    val reprompt: String? = null,
    val endSession: Boolean? = null,
    /** The asking Echo's room, as Juujarvis knows it: names that Echo's sensor. */
    val room: String? = null
)

/**
 * Juujarvis (mTLS) answers an Alexa question. delivered: Alexa spoke it now. queued: too late for the
 * request, kept for the asking Echo. Neither: unknown or expired, use another channel.
 */
@RestController
class AlexaReplyController(private val replies: AlexaReplies, private val sensors: AlexaSensorTrigger) {

    @PostMapping("/api/alexa/{id}/reply")
    fun reply(@PathVariable id: String, @RequestBody body: AlexaReplyRequest): ResponseEntity<Map<String, Any>> {
        val speech = body.speech?.trim()
        if (speech.isNullOrEmpty() || speech.length > 6000) {
            return ResponseEntity.badRequest().body(mapOf("error" to "speech must be 1-6000 characters"))
        }
        val deviceId = replies.deviceOf(id)
        val outcome = replies.complete(id, AlexaReply(speech, body.reprompt?.trim()?.ifEmpty { null }, body.endSession ?: true))
        val room = body.room?.trim()?.ifEmpty { null }
        if (deviceId != null && room != null) runCatching { sensors.roomKnown(deviceId, room) }
        // A late answer waits for the Echo: open its sensor so the household routine plays it there
        val announced = outcome == ReplyOutcome.QUEUED && deviceId != null && runCatching { sensors.open(deviceId) }.getOrDefault(false)
        return ResponseEntity.ok(mapOf(
            "delivered" to (outcome == ReplyOutcome.SPOKEN),
            "queued" to (outcome == ReplyOutcome.QUEUED),
            "announced" to announced
        ))
    }
}

data class AlexaAnnounceRequest(val deviceId: String?, val text: String?)

/**
 * Juujarvis (mTLS) asks for a message to be spoken on an Echo. queued: kept for that Echo (15 min).
 * announced: its sensor was opened, so the household routine plays it now; otherwise it waits until
 * someone opens the skill there.
 */
@RestController
class AlexaAnnounceController(private val replies: AlexaReplies, private val sensors: AlexaSensorTrigger) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/alexa/announce")
    fun announce(@RequestBody body: AlexaAnnounceRequest): ResponseEntity<Map<String, Any>> {
        val deviceId = body.deviceId?.trim()
        val text = body.text?.trim()
        if (deviceId.isNullOrEmpty() || deviceId.length > 512 || text.isNullOrEmpty() || text.length > 1000) {
            return ResponseEntity.badRequest().body(mapOf("error" to "deviceId and text (1-1000 characters) are required"))
        }
        replies.announce(deviceId, text)
        val announced = runCatching { sensors.open(deviceId) }.getOrDefault(false)
        log.info("Alexa announcement queued (sensor opened: {})", announced)
        return ResponseEntity.ok(mapOf("queued" to true, "announced" to announced))
    }
}
