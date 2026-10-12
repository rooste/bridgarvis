package com.bridgarvis.alexa

import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** What Juujarvis wants Alexa to say. [endSession] false keeps the microphone open for a follow-up. */
data class AlexaReply(val speech: String, val reprompt: String? = null, val endSession: Boolean = true)

/**
 * Alexa requests waiting for Juujarvis's answer. The /alexa request thread waits on its future;
 * Juujarvis completes it through POST /api/alexa/{id}/reply. Once the request has given up waiting
 * (Alexa's ~8 s limit) the entry is gone, and a late reply reports "not delivered" so Juujarvis can
 * use another channel.
 */
@Component
class AlexaReplies {
    private val waiting = ConcurrentHashMap<String, CompletableFuture<AlexaReply>>()

    fun register(id: String): CompletableFuture<AlexaReply> = CompletableFuture<AlexaReply>().also { waiting[id] = it }

    fun forget(id: String) {
        waiting.remove(id)
    }

    /** True if a request was still waiting and got the reply. */
    fun complete(id: String, reply: AlexaReply): Boolean = waiting.remove(id)?.complete(reply) ?: false
}
