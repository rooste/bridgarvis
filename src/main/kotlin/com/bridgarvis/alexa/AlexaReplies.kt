package com.bridgarvis.alexa

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** What Juujarvis wants Alexa to say. [endSession] false keeps the microphone open for a follow-up. */
data class AlexaReply(val speech: String, val reprompt: String? = null, val endSession: Boolean = true)

/** An answer that came after Alexa stopped waiting, kept for the Echo that asked. */
data class Announcement(val question: String, val speech: String, val createdAt: Instant)

enum class ReplyOutcome {
    /** The waiting request spoke it. */
    SPOKEN,
    /** Too late for the request: kept for the Echo, spoken when the skill opens there next. */
    QUEUED,
    /** No such question (unknown, or its answer window has passed). */
    UNKNOWN
}

/**
 * Alexa questions and their answers. The /alexa request thread waits on its future; Juujarvis completes
 * it through POST /api/alexa/{id}/reply. After the request has given up (Alexa's ~8 s limit) a reply is
 * kept as an [Announcement] for the asking Echo for [keepFor], and spoken the next time the skill opens on
 * that Echo (by the user, or by a routine). In memory only: a restart loses waiting answers.
 */
@Component
class AlexaReplies(private val clock: Clock = Clock.systemUTC()) {
    private data class Question(val deviceId: String?, val text: String, val askedAt: Instant)

    private val waiting = ConcurrentHashMap<String, CompletableFuture<AlexaReply>>()
    private val questions = ConcurrentHashMap<String, Question>()
    private val announcements = ConcurrentHashMap<String, Announcement>()
    private val keepFor: Duration = Duration.ofMinutes(15)

    fun register(id: String, deviceId: String? = null, question: String = ""): CompletableFuture<AlexaReply> {
        prune()
        questions[id] = Question(deviceId, question, clock.instant())
        return CompletableFuture<AlexaReply>().also { waiting[id] = it }
    }

    /** The request stopped waiting; a later reply becomes an announcement. */
    fun forget(id: String) {
        waiting.remove(id)
    }

    /** The Echo that asked question [id], while the question is known. */
    fun deviceOf(id: String): String? = questions[id]?.deviceId

    fun complete(id: String, reply: AlexaReply): ReplyOutcome {
        waiting.remove(id)?.let { future ->
            if (future.complete(reply)) {
                questions.remove(id)
                return ReplyOutcome.SPOKEN
            }
        }
        val question = questions.remove(id) ?: return ReplyOutcome.UNKNOWN
        val deviceId = question.deviceId ?: return ReplyOutcome.UNKNOWN
        if (Duration.between(question.askedAt, clock.instant()) > keepFor) return ReplyOutcome.UNKNOWN
        announcements[deviceId] = Announcement(question.text, reply.speech, clock.instant())
        return ReplyOutcome.QUEUED
    }

    /**
     * A message Juujarvis wants spoken on [deviceId] without being asked (spoken when the skill opens there,
     * normally by the Echo's sensor routine). Joins a message that is still waiting rather than replacing it.
     */
    fun announce(deviceId: String, text: String) {
        prune()
        announcements.merge(deviceId, Announcement("", "Message from Juujarvis: $text", clock.instant())) { old, new ->
            new.copy(question = old.question, speech = old.speech + " " + new.speech)
        }
    }

    /** Takes the waiting announcement for [deviceId], if any (each is spoken once). */
    fun takeAnnouncement(deviceId: String?): Announcement? {
        if (deviceId == null) return null
        val announcement = announcements.remove(deviceId) ?: return null
        return announcement.takeIf { Duration.between(it.createdAt, clock.instant()) <= keepFor }
    }

    private fun prune() {
        val cutoff = clock.instant().minus(keepFor)
        questions.entries.removeIf { it.value.askedAt.isBefore(cutoff) && !waiting.containsKey(it.key) }
        announcements.entries.removeIf { it.value.createdAt.isBefore(cutoff) }
    }
}
