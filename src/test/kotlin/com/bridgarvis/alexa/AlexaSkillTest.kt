package com.bridgarvis.alexa

import com.bridgarvis.message.JUUJARVIS
import com.bridgarvis.store.MemoryMessageStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlexaSkillTest {

    private val mapper = ObjectMapper()
    private val store = MemoryMessageStore { }
    private val replies = AlexaReplies()
    private val progressiveCalls = CopyOnWriteArrayList<String>()
    private val progressive = object : ProgressiveResponder(mapper) {
        override fun speak(apiEndpoint: String?, apiAccessToken: String?, requestId: String?, speech: String) {
            progressiveCalls += speech
        }
    }

    private fun skill(connected: Boolean = true, waitFor: Duration = Duration.ofSeconds(2), voice: String = "") = AlexaSkill(
        store, replies, progressive, { connected },
        progressiveAfter = Duration.ofMillis(100), waitFor = waitFor, retention = Duration.ofDays(1), voice = voice
    )

    private fun request(type: String, intent: String? = null, query: String? = null): JsonNode = mapper.valueToTree(mapOf<String, Any>(
        "session" to mapOf<String, Any>("sessionId" to "s1", "new" to true),
        "context" to mapOf("System" to mapOf(
            "device" to mapOf("deviceId" to "device-kitchen"),
            "person" to mapOf("personId" to "person-1"),
            "apiEndpoint" to "https://api.amazonalexa.com",
            "apiAccessToken" to "token"
        )),
        "request" to buildMap<String, Any> {
            put("type", type)
            put("requestId", "r1")
            put("locale", "en-US")
            if (intent != null) put("intent", mapOf<String, Any>(
                "name" to intent,
                "slots" to mapOf("query" to mapOf("name" to "query", "value" to (query ?: "")))
            ))
        }
    ))

    @Suppress("UNCHECKED_CAST")
    private fun speech(response: Map<String, Any>) =
        ((response["response"] as Map<String, Any>)["outputSpeech"] as Map<String, Any>)["text"] as String

    @Suppress("UNCHECKED_CAST")
    private fun ends(response: Map<String, Any>) = (response["response"] as Map<String, Any>)["shouldEndSession"] as Boolean

    @Test
    fun `a question is handed to Juujarvis and its reply is spoken`() {
        val responder = thread {
            while (store.pending().isEmpty()) Thread.sleep(10)
            Thread.sleep(300) // longer than progressiveAfter
            val pending = store.pending().single()
            assertTrue(replies.complete(pending.id, AlexaReply("You have soccer at five.", endSession = true)))
        }
        val response = skill().handle(request("IntentRequest", AlexaSkill.QUERY_INTENT, "what's on today"))
        responder.join()

        assertEquals("You have soccer at five.", speech(response))
        assertTrue(ends(response))
        assertEquals(listOf("Let me check."), progressiveCalls)

        val message = store.pending().single()
        assertEquals("alexa", message.channel)
        assertEquals("what's on today", message.text)
        assertEquals(JUUJARVIS, message.pendingFor)
        assertEquals("device-kitchen", message.context["deviceId"])
        assertEquals("person-1", message.context["personId"])
        assertEquals("en-US", message.context["locale"])
        assertEquals("s1", message.context["sessionId"])
        // the access token is never stored
        assertFalse(message.context.values.contains("token"))
    }

    @Test
    fun `a follow-up question keeps the session open`() {
        thread {
            while (store.pending().isEmpty()) Thread.sleep(10)
            replies.complete(store.pending().single().id, AlexaReply("Which day?", endSession = false))
        }
        val response = skill().handle(request("IntentRequest", AlexaSkill.QUERY_INTENT, "remind Alex"))
        assertEquals("Which day?", speech(response))
        assertFalse(ends(response))
    }

    @Test
    fun `a slow answer is promised for later and a late reply is reported undelivered`() {
        val response = skill(waitFor = Duration.ofMillis(200)).handle(request("IntentRequest", AlexaSkill.QUERY_INTENT, "plan a trip"))
        assertEquals("I'm still working on that. I'll send you the answer.", speech(response))
        assertTrue(ends(response))
        assertFalse(replies.complete(store.pending().single().id, AlexaReply("late")))
    }

    @Test
    fun `nothing is queued when Juujarvis is offline`() {
        val response = skill(connected = false).handle(request("IntentRequest", AlexaSkill.QUERY_INTENT, "hello"))
        assertTrue(speech(response).contains("isn't reachable"))
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `launch, help and stop are answered locally`() {
        assertFalse(ends(skill().handle(request("LaunchRequest"))))
        assertFalse(ends(skill().handle(request("IntentRequest", "AMAZON.HelpIntent"))))
        assertTrue(ends(skill().handle(request("IntentRequest", "AMAZON.StopIntent"))))
        assertTrue(store.pending().isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun output(response: Map<String, Any>) =
        (response["response"] as Map<String, Any>)["outputSpeech"] as Map<String, Any>

    @Test
    fun `a configured voice speaks escaped SSML`() {
        thread {
            while (store.pending().isEmpty()) Thread.sleep(10)
            Thread.sleep(300)
            replies.complete(store.pending().single().id, AlexaReply("Tom & Jerry <speak>\"hi\"</speak>"))
        }
        val response = skill(voice = "Brian").handle(request("IntentRequest", AlexaSkill.QUERY_INTENT, "cartoons"))
        assertEquals("SSML", output(response)["type"])
        assertEquals(
            "<speak><voice name=\"Brian\">Tom &amp; Jerry &lt;speak&gt;&quot;hi&quot;&lt;/speak&gt;</voice></speak>",
            output(response)["ssml"]
        )
        assertEquals(listOf("<speak><voice name=\"Brian\">Let me check.</voice></speak>"), progressiveCalls)
    }

    @Test
    fun `an invalid voice name falls back to plain text`() {
        val response = skill(voice = "Brian\"><audio src=x/>").handle(request("LaunchRequest"))
        assertEquals("PlainText", output(response)["type"])
    }
}
