package com.bridgarvis.web

import com.bridgarvis.message.Message
import com.bridgarvis.store.MessageStore
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Mailbox(val name: String, val channel: String, val messages: List<Message>)

/**
 * Read-only mail viewer (casemail replacement). Mail content is only ever shown as escaped plain text;
 * HTML from mail is never rendered. Attachments are download-only.
 */
@Controller
@RequestMapping("/ui")
class MailUiController(private val store: MessageStore) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val zone = ZoneId.of("America/Chicago")
    private val timeFormat = DateTimeFormatter.ofPattern("EEE MMM d yyyy, h:mm a z").withZone(zone)

    @ModelAttribute
    fun securityHeaders(response: HttpServletResponse) {
        response.setHeader("Content-Security-Policy",
            "default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "same-origin")
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Cache-Control", "no-store")
    }

    @GetMapping("", "/")
    fun index(model: Model): String {
        val messages = store.recent("email", 300) + store.recent("sms", 100)
        // Group by exact mailbox: "Alex@…" and "alex@…" stay separate
        val boxes = messages
            .groupBy { it.box }
            .map { (box, list) -> Mailbox(box.substringAfter('#'), box.substringBefore('#'), list.sortedByDescending { it.id }) }
            .sortedByDescending { it.messages.first().id }
        model.addAttribute("boxes", boxes)
        model.addAttribute("total", messages.size)
        model.addAttribute("fmt", timeFormat)
        return "ui/index"
    }

    @GetMapping("/message")
    fun message(@RequestParam box: String, @RequestParam id: String, model: Model): String {
        val message = store.find(box, id) ?: return "ui/notfound"
        model.addAttribute("m", message)
        model.addAttribute("box", box)
        model.addAttribute("fmt", timeFormat)
        return "ui/message"
    }

    /** Confirmation page; the actual delete is a POST from this page. */
    @GetMapping("/delete")
    fun confirmDelete(@RequestParam box: String, @RequestParam id: String, model: Model): String {
        val message = store.find(box, id) ?: return "ui/notfound"
        model.addAttribute("m", message)
        model.addAttribute("box", box)
        model.addAttribute("fmt", timeFormat)
        return "ui/delete"
    }

    @PostMapping("/delete")
    fun delete(@RequestParam box: String, @RequestParam id: String, request: HttpServletRequest): ResponseEntity<String> {
        // The client certificate is sent automatically, even on requests another site triggers,
        // so state changes must prove they come from this UI (blocks cross-site request forgery).
        if (!isSameOrigin(request)) {
            log.warn("Rejected delete of {}: Sec-Fetch-Site={}, Origin={}", id, request.getHeader("Sec-Fetch-Site"), request.getHeader("Origin"))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_PLAIN)
                .body("Delete rejected: the request did not come from this page.")
        }
        store.delete(box, id)
        log.info("Message {} deleted by {}", id, request.getAttribute(ClientRoleFilter.CLIENT_ATTRIBUTE))
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, "/ui").build()
    }

    /**
     * Sec-Fetch-Site is set by the browser and cannot be forged by page scripts, so when present it
     * decides. (Origin can legitimately be "null" on same-origin posts, e.g. under strict referrer
     * policies.) Browsers without Fetch Metadata must send an Origin that matches exactly.
     */
    private fun isSameOrigin(request: HttpServletRequest): Boolean {
        val fetchSite = request.getHeader("Sec-Fetch-Site")
        if (fetchSite != null) return fetchSite == "same-origin"
        val origin = request.getHeader("Origin") ?: return false
        val port = request.serverPort.takeUnless { it == 443 }?.let { ":$it" } ?: ""
        return origin.equals("${request.scheme}://${request.serverName}$port", ignoreCase = true)
    }

    @GetMapping("/attachment/{index}")
    fun attachment(@RequestParam box: String, @RequestParam id: String, @PathVariable index: Int): ResponseEntity<InputStreamResource> {
        val content = store.openAttachment(box, id, index) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            // Never let the browser render mail-supplied content inline
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(content.info.size)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(content.info.name, Charsets.UTF_8).build().toString())
            .body(InputStreamResource(content.stream))
    }
}
