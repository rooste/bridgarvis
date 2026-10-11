package com.bridgarvis.message

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
class MessageController(private val store: MessageStore, private val sms: MockSmsService) {

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

    @PostMapping("/messages/send")
    fun send(@RequestBody request: SendRequest): ResponseEntity<Any> {
        val to = request.to?.takeIf { it.isNotBlank() }
        val text = request.text?.takeIf { it.isNotBlank() }
        if (to == null || text == null) {
            return ResponseEntity.badRequest().body(mapOf("error" to "'to' and 'text' are required"))
        }
        val message = sms.send(to, text)
        return ResponseEntity.ok(SendResult(message.id, message.status))
    }

    /** Recently sent messages. */
    @GetMapping("/messages/outbox")
    fun outbox(): List<Message> = store.outbox()

    /** Simulates an SMS arriving from a phone (until the toll-free number is approved). */
    @PostMapping("/mock/inbound")
    fun mockInbound(@RequestBody request: MockInboundRequest): ResponseEntity<Any> {
        val from = request.from?.takeIf { it.isNotBlank() }
        val text = request.text?.takeIf { it.isNotBlank() }
        if (from == null || text == null) {
            return ResponseEntity.badRequest().body(mapOf("error" to "'from' and 'text' are required"))
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(sms.receive(from, text))
    }
}
