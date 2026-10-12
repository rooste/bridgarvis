package com.bridgarvis.alexa

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sends an Alexa progressive response ("Let me check…") while the real answer is being prepared.
 * Uses the request's own apiEndpoint and apiAccessToken; the token is only ever sent to an https
 * *.amazonalexa.com endpoint.
 */
@Component
class ProgressiveResponder(private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    fun speak(apiEndpoint: String?, apiAccessToken: String?, requestId: String?, speech: String) {
        val base = apiEndpoint?.let { runCatching { URI(it) }.getOrNull() }
        if (base == null || base.scheme != "https" || base.host?.endsWith(".amazonalexa.com") != true ||
            apiAccessToken.isNullOrBlank() || requestId.isNullOrBlank()
        ) return
        val body = mapper.writeValueAsString(mapOf(
            "header" to mapOf("requestId" to requestId),
            "directive" to mapOf("type" to "VoicePlayer.Speak", "speech" to speech)
        ))
        val request = HttpRequest.newBuilder(base.resolve("/v1/directives"))
            .header("Authorization", "Bearer $apiAccessToken")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(2))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete { response, error ->
            if (error != null) log.warn("Progressive response failed: {}", error.message)
            else if (response.statusCode() >= 300) log.warn("Progressive response returned HTTP {}", response.statusCode())
        }
    }
}
