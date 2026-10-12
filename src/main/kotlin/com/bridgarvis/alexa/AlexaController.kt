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

data class AlexaReplyRequest(val speech: String?, val reprompt: String? = null, val endSession: Boolean? = null)

/** Juujarvis (mTLS) answers a waiting Alexa question. delivered=false: too late, use another channel. */
@RestController
class AlexaReplyController(private val replies: AlexaReplies) {

    @PostMapping("/api/alexa/{id}/reply")
    fun reply(@PathVariable id: String, @RequestBody body: AlexaReplyRequest): ResponseEntity<Map<String, Any>> {
        val speech = body.speech?.trim()
        if (speech.isNullOrEmpty() || speech.length > 6000) {
            return ResponseEntity.badRequest().body(mapOf("error" to "speech must be 1-6000 characters"))
        }
        val delivered = replies.complete(id, AlexaReply(speech, body.reprompt?.trim()?.ifEmpty { null }, body.endSession ?: true))
        return ResponseEntity.ok(mapOf("delivered" to delivered))
    }
}
