package com.bridgarvis.alexa

import com.bridgarvis.message.Direction
import com.bridgarvis.message.JUUJARVIS
import com.bridgarvis.message.Message
import com.bridgarvis.message.Ulid
import com.bridgarvis.store.MessageStore
import com.bridgarvis.ws.ClientPresence
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Handles verified Alexa custom-skill requests. A question (the catch-all intent) becomes a message on
 * the "alexa" channel for Juujarvis, and the request waits for Juujarvis's reply, saying "Let me check"
 * if that takes a moment. Alexa allows about 8 seconds in total; after [waitFor] the user is told the
 * answer will come another way.
 */
@Component
class AlexaSkill(
    private val store: MessageStore,
    private val replies: AlexaReplies,
    private val progressive: ProgressiveResponder,
    private val presence: ClientPresence,
    @Value("\${bridgarvis.alexa.progressive-after:1200ms}") private val progressiveAfter: Duration,
    @Value("\${bridgarvis.alexa.wait-for:6500ms}") private val waitFor: Duration,
    @Value("\${bridgarvis.alexa.retention:1d}") private val retention: Duration,
    /** Amazon Polly voice for everything the skill says (e.g. "Brian"); empty = Alexa's own voice. */
    @Value("\${bridgarvis.alexa.voice:}") voice: String = ""
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val voice = voice.trim().takeIf { it.matches(Regex("[A-Za-z]{2,20}")) }

    fun handle(request: JsonNode): Map<String, Any> {
        val type = request.path("request").path("type").asText()
        return when (type) {
            "LaunchRequest" -> speak(GREETING, reprompt = REPROMPT)
            "IntentRequest" -> intent(request)
            "SessionEndedRequest" -> empty()
            else -> empty()
        }
    }

    private fun intent(request: JsonNode): Map<String, Any> {
        val intent = request.path("request").path("intent")
        return when (val name = intent.path("name").asText()) {
            QUERY_INTENT -> {
                val query = intent.path("slots").path(QUERY_SLOT).path("value").asText("").trim()
                if (query.isEmpty()) speak("Sorry, I didn't catch that. What can I do for you?", reprompt = REPROMPT)
                else ask(request, query)
            }
            "AMAZON.HelpIntent" -> speak(
                "You can ask me anything, like what's on the calendar tomorrow, or to remind someone about something.",
                reprompt = REPROMPT
            )
            "AMAZON.StopIntent", "AMAZON.CancelIntent", "AMAZON.NavigateHomeIntent" -> speak("Bye!", end = true)
            "AMAZON.FallbackIntent" -> speak("Sorry, I didn't catch that. What can I do for you?", reprompt = REPROMPT)
            else -> {
                log.info("Unhandled Alexa intent {}", name)
                speak("Sorry, I can't do that yet.", end = true)
            }
        }
    }

    private fun ask(request: JsonNode, query: String): Map<String, Any> {
        if (!presence.connected()) {
            log.warn("Alexa question received but Juujarvis is not connected")
            return speak("Sorry, Juujarvis isn't reachable right now. Please try again later.", end = true)
        }
        val system = request.path("context").path("System")
        val now = Instant.now()
        val id = Ulid.random()
        val message = Message(
            id = id,
            channel = CHANNEL,
            direction = Direction.INBOUND,
            box = "$CHANNEL#$JUUJARVIS",
            from = CHANNEL,
            to = JUUJARVIS,
            text = query.take(MAX_QUERY),
            timestamp = now,
            status = "RECEIVED",
            context = buildMap {
                system.path("device").path("deviceId").textOrNull()?.let { put("deviceId", it) }
                system.path("person").path("personId").textOrNull()?.let { put("personId", it) }
                request.path("request").path("locale").textOrNull()?.let { put("locale", it) }
                request.path("session").path("sessionId").textOrNull()?.let { put("sessionId", it) }
                request.path("session").path("new").takeIf { it.isBoolean }?.let { put("newSession", it.asText()) }
            },
            pendingFor = JUUJARVIS,
            expiresAt = now.plus(retention)
        )

        val reply = replies.register(id)
        store.save(message)
        log.info("Alexa question {} handed to Juujarvis", id)
        CompletableFuture.delayedExecutor(progressiveAfter.toMillis(), TimeUnit.MILLISECONDS).execute {
            if (!reply.isDone) progressive.speak(
                system.path("apiEndpoint").textOrNull(), system.path("apiAccessToken").textOrNull(),
                request.path("request").path("requestId").textOrNull(), ssml("Let me check.") ?: "Let me check."
            )
        }
        return try {
            val answer = reply.get(waitFor.toMillis(), TimeUnit.MILLISECONDS)
            log.info("Alexa question {} answered", id)
            if (answer.endSession) speak(answer.speech, end = true)
            else speak(answer.speech, reprompt = answer.reprompt ?: "Anything else?")
        } catch (e: TimeoutException) {
            replies.forget(id)
            log.info("Alexa question {} not answered in time", id)
            speak("I'm still working on that. I'll send you the answer.", end = true)
        }
    }

    private fun speak(text: String, end: Boolean = false, reprompt: String? = null): Map<String, Any> {
        val response = buildMap<String, Any> {
            put("outputSpeech", plain(text))
            if (!end && reprompt != null) put("reprompt", mapOf("outputSpeech" to plain(reprompt)))
            put("shouldEndSession", end)
        }
        return mapOf("version" to "1.0", "response" to response)
    }

    private fun plain(text: String): Map<String, String> =
        ssml(text)?.let { mapOf("type" to "SSML", "ssml" to it) } ?: mapOf("type" to "PlainText", "text" to text.take(MAX_SPEECH))

    /**
     * The text in the configured voice, or null without one. The text is XML-escaped, so nothing in an
     * answer can add its own SSML.
     */
    private fun ssml(text: String): String? = voice?.let { name ->
        val escaped = text.take(MAX_SPEECH)
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        "<speak><voice name=\"$name\">$escaped</voice></speak>"
    }

    private fun empty(): Map<String, Any> = mapOf("version" to "1.0", "response" to emptyMap<String, Any>())

    private fun JsonNode.textOrNull(): String? = if (isTextual && asText().isNotBlank()) asText() else null

    companion object {
        const val CHANNEL = "alexa"
        const val QUERY_INTENT = "QueryIntent"
        const val QUERY_SLOT = "query"
        private const val GREETING = "Hi, it's Juujarvis. What can I do for you?"
        private const val REPROMPT = "What can I do for you?"
        private const val MAX_QUERY = 2000
        private const val MAX_SPEECH = 6000
    }
}
