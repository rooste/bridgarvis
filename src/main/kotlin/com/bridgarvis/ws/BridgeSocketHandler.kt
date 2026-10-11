package com.bridgarvis.ws

import com.bridgarvis.message.Message
import com.bridgarvis.message.MessageAvailableEvent
import com.bridgarvis.store.MessageStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * Notification-only WebSocket. The server sends one frame type:
 *
 *   {"type":"message.available","id":"<message id>","channel":"sms|email"}
 *
 * It carries no message content. The client fetches the message with
 * GET /api/messages/{id} and acks it over REST once it has been processed.
 * On connect, every pending (unacked) message is announced again, so nothing
 * is lost while the client is offline. Frames sent by the client are ignored.
 */
@Component
class BridgeSocketHandler(
    private val store: MessageStore,
    private val mapper: ObjectMapper
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val safe = ConcurrentWebSocketSessionDecorator(session, 10_000, 64 * 1024)
        sessions[session.id] = safe
        log.info("Client connected: session {}", session.id)
        store.pending().forEach { notify(safe, it) }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        sessions.remove(session.id)
        log.info("Client disconnected: session {} ({})", session.id, status.code)
    }

    @EventListener
    fun onAvailable(event: MessageAvailableEvent) {
        sessions.values.forEach { notify(it, event.message) }
    }

    private fun notify(session: WebSocketSession, message: Message) {
        try {
            val frame = mapOf("type" to "message.available", "id" to message.id, "channel" to message.channel)
            session.sendMessage(TextMessage(mapper.writeValueAsString(frame)))
        } catch (e: Exception) {
            log.warn("Failed to notify session {}: {}", session.id, e.message)
        }
    }
}
