package com.bridgarvis.outbound

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import software.amazon.awssdk.awscore.exception.AwsServiceException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class EmailSendRequest(
    val to: List<String>? = null,
    val cc: List<String>? = null,
    val subject: String? = null,
    val text: String? = null,
    val inReplyTo: String? = null,
    val references: String? = null
)

data class EmailSendResult(val id: String, val messageId: String, val status: String)

/** Email sending for Juujarvis (API area: juujarvis client certificate only). */
@RestController
@RequestMapping("/api/email")
class EmailController(private val sender: EmailSender) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/send")
    fun send(@RequestBody request: EmailSendRequest): ResponseEntity<Any> {
        val mail = OutgoingEmail(
            to = request.to.orEmpty().map { it.trim() },
            cc = request.cc.orEmpty().map { it.trim() },
            subject = request.subject.orEmpty().trim(),
            text = request.text.orEmpty(),
            inReplyTo = request.inReplyTo?.trim()?.ifBlank { null },
            references = request.references?.trim()?.ifBlank { null }
        )
        return try {
            val sent = sender.send(mail)
            ResponseEntity.ok(EmailSendResult(sent.id, sent.messageId!!, sent.status))
        } catch (e: InvalidEmailException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: AwsServiceException) {
            // e.g. sandbox: recipient not verified; message rejected
            log.warn("SES refused email: {} {}", e.awsErrorDetails()?.errorCode(), e.awsErrorDetails()?.errorMessage())
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf(
                "error" to "SES refused the email",
                "code" to e.awsErrorDetails()?.errorCode(),
                "detail" to e.awsErrorDetails()?.errorMessage()?.replace(Regex("arn:aws:[^ ']+"), "<arn>")))
        }
    }
}
