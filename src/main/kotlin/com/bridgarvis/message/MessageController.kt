package com.bridgarvis.message

import com.bridgarvis.sms.SmsRejected
import com.bridgarvis.sms.SmsService
import com.bridgarvis.store.MessageStore
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class SendRequest(val to: String?, val text: String?)
data class SendResult(val messageId: String, val status: String)
data class MockInboundRequest(val from: String?, val text: String?)

@RestController
@RequestMapping("/api")
class MessageController(private val store: MessageStore, private val sms: SmsService) {

    /** Messages waiting for Juujarvis. */
    @GetMapping("/messages")
    fun pending(): List<Message> = store.pending()

    @GetMapping("/messages/{id}")
    fun get(@PathVariable id: String): ResponseEntity<Message> =
        store.get(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("/messages/{id}/attachments/{index}")
    fun attachment(@PathVariable id: String, @PathVariable index: Int): ResponseEntity<InputStreamResource> {
        val content = store.openAttachment(id, index) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(runCatching { MediaType.parseMediaType(content.info.contentType) }.getOrDefault(MediaType.APPLICATION_OCTET_STREAM))
            .contentLength(content.info.size)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(content.info.name, Charsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(InputStreamResource(content.stream))
    }

    @PostMapping("/messages/{id}/ack")
    fun ack(@PathVariable id: String): ResponseEntity<Void> =
        if (store.ack(id)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    /**
     * Sends an SMS. Refusals carry a stable "error" code Juujarvis acts on: opted_out / not_opted_in (409:
     * don't text this number), rate_limited (429), invalid_number (400), send_failed (502: use another channel).
     */
    @PostMapping("/messages/send")
    fun send(@RequestBody request: SendRequest): ResponseEntity<Any> {
        val to = request.to?.takeIf { it.isNotBlank() }
        val text = request.text?.trim()?.takeIf { it.isNotEmpty() }
        if (to == null || text == null || text.length > 1600) {
            return ResponseEntity.badRequest().body(mapOf("error" to "invalid_request", "detail" to "'to' and 'text' (1-1600 characters) are required"))
        }
        return try {
            val message = sms.send(to, text)
            ResponseEntity.ok(SendResult(message.id, message.status))
        } catch (e: SmsRejected) {
            val status = when (e.code) {
                SmsRejected.OPTED_OUT, SmsRejected.NOT_OPTED_IN -> HttpStatus.CONFLICT
                SmsRejected.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS
                SmsRejected.INVALID_NUMBER -> HttpStatus.BAD_REQUEST
                else -> HttpStatus.BAD_GATEWAY
            }
            ResponseEntity.status(status).body(mapOf("error" to e.code, "detail" to (e.detail ?: e.code)))
        }
    }

    /** SMS consent per number (opted in with JOIN / out with STOP), so Juujarvis can resync its view. */
    @GetMapping("/sms/consent")
    fun consent(): List<Map<String, String>> = sms.consents().map {
        mapOf("number" to it.number, "consent" to it.consent.name, "updatedAt" to it.updatedAt.toString())
    }

    /** Recently sent messages. */
    @GetMapping("/messages/outbox")
    fun outbox(): List<Message> = store.outbox()

    /** Simulates an SMS arriving from a phone (local runs and tests; same keyword handling as real texts). */
    @PostMapping("/mock/inbound")
    fun mockInbound(@RequestBody request: MockInboundRequest): ResponseEntity<Any> {
        val from = request.from?.takeIf { it.isNotBlank() }
        val text = request.text?.takeIf { it.isNotBlank() }
        if (from == null || text == null) {
            return ResponseEntity.badRequest().body(mapOf("error" to "'from' and 'text' are required"))
        }
        val message = sms.receive(from, text, "mock-" + java.util.UUID.randomUUID())
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "invalid_number"))
        return ResponseEntity.status(HttpStatus.CREATED).body(message)
    }
}
