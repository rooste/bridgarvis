package com.bridgarvis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.ssl.SslBundle
import org.springframework.boot.ssl.pem.PemSslStoreBundle
import org.springframework.boot.ssl.pem.PemSslStoreDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.fail
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.ssl.bundle.pem.bridgarvis.keystore.certificate=classpath:certs/server.pem",
        "spring.ssl.bundle.pem.bridgarvis.keystore.private-key=classpath:certs/server.key",
        "spring.ssl.bundle.pem.bridgarvis.truststore.certificate=classpath:certs/clients.pem",
        "management.server.port=0"
    ]
)
class MutualTlsIntegrationTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var mapper: ObjectMapper

    private fun sslContext(clientCert: String?): SSLContext {
        val trust = PemSslStoreDetails.forCertificate("classpath:certs/server.pem")
        val identity = clientCert?.let {
            PemSslStoreDetails.forCertificate("classpath:certs/$it.pem").withPrivateKey("classpath:certs/$it.key")
        }
        return SslBundle.of(PemSslStoreBundle(identity, trust)).createSslContext()
    }

    private fun client(clientCert: String? = "juujarvis"): HttpClient =
        HttpClient.newBuilder().sslContext(sslContext(clientCert)).connectTimeout(Duration.ofSeconds(5)).build()

    private fun uri(path: String) = URI.create("https://localhost:$port$path")

    private fun get(path: String, http: HttpClient = client()) =
        http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, body: Any? = null, http: HttpClient = client()) =
        http.send(
            HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body?.let { mapper.writeValueAsString(it) } ?: ""))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

    private fun json(body: String): JsonNode = mapper.readTree(body)

    @Test
    fun `client without a certificate is rejected`() {
        assertFailsWith<IOException> { get("/api/messages", client(clientCert = null)) }
    }

    @Test
    fun `client with an untrusted certificate is rejected`() {
        assertFailsWith<IOException> { get("/api/messages", client(clientCert = "rogue")) }
    }

    @Test
    fun `messages can be fetched by id until acked, and send needs an opt-in`() {
        val created = post("/api/mock/inbound", mapOf("from" to "+15551230001", "text" to "hello"))
        assertEquals(201, created.statusCode())
        val id = json(created.body())["id"].asText()

        val fetched = get("/api/messages/$id")
        assertEquals(200, fetched.statusCode())
        assertEquals("hello", json(fetched.body())["text"].asText())
        assertTrue(json(get("/api/messages").body()).any { it["id"].asText() == id })

        assertEquals(204, post("/api/messages/$id/ack").statusCode())
        assertEquals(404, get("/api/messages/$id").statusCode())
        assertEquals(404, post("/api/messages/$id/ack").statusCode())

        // texts go only to numbers that opted in with JOIN
        val refused = post("/api/messages/send", mapOf("to" to "+15551230002", "text" to "hi back"))
        assertEquals(409, refused.statusCode())
        assertEquals("not_opted_in", json(refused.body())["error"].asText())
        assertEquals(201, post("/api/mock/inbound", mapOf("from" to "+15551230002", "text" to "JOIN")).statusCode())
        assertTrue(json(get("/api/sms/consent").body()).any { it["number"].asText() == "+15551230002" && it["consent"].asText() == "OPTED_IN" })

        val sent = post("/api/messages/send", mapOf("to" to "+15551230002", "text" to "hi back"))
        assertEquals(200, sent.statusCode())
        assertEquals("MOCK_SENT", json(sent.body())["status"].asText())
        assertEquals(400, post("/api/messages/send", mapOf("to" to "+15551230002")).statusCode())
    }

    @Test
    fun `websocket announces new messages by id only`() {
        val frames = LinkedBlockingQueue<JsonNode>()
        val ws = connect(frames)

        val id = json(post("/api/mock/inbound", mapOf("from" to "+15551230003", "text" to "via ws")).body())["id"].asText()
        val frame = awaitFrame(frames) { it["id"]?.asText() == id }
        assertEquals("message.available", frame["type"].asText())
        assertEquals("sms", frame["channel"].asText())
        assertEquals(setOf("type", "id", "channel"), frame.fieldNames().asSequence().toSet(), "no message content on the socket")

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        assertEquals(204, post("/api/messages/$id/ack").statusCode())
    }

    @Test
    fun `websocket re-announces unacked messages on connect`() {
        val id = json(post("/api/mock/inbound", mapOf("from" to "+15551230005", "text" to "while offline")).body())["id"].asText()

        val frames = LinkedBlockingQueue<JsonNode>()
        val ws = connect(frames)
        awaitFrame(frames) { it["id"]?.asText() == id }

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        assertEquals(204, post("/api/messages/$id/ack").statusCode())
    }

    @Test
    fun `each client certificate is limited to its own area`() {
        val browser = client("browser-test")
        assertEquals(200, get("/ui", browser).statusCode())
        assertEquals(200, get("/ui/style.css", browser).statusCode())
        assertEquals(403, get("/api/messages", browser).statusCode())
        assertEquals(403, post("/api/messages/send", mapOf("to" to "+1555", "text" to "x"), browser).statusCode())
        assertEquals(403, get("/ui", client("juujarvis")).statusCode())
        assertEquals(403, get("/", client("juujarvis")).statusCode())
    }

    @Test
    fun `ui shows messages as escaped text with strict headers`() {
        val created = json(post("/api/mock/inbound", mapOf("from" to "+15551230007", "text" to "<script>alert(1)</script><b>hi</b>")).body())
        val browser = client("browser-test")
        val index = get("/ui", browser)
        assertTrue(index.body().contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(index.body().contains("<script>alert"))
        assertTrue(index.headers().firstValue("Content-Security-Policy").get().startsWith("default-src 'none'"))
        assertEquals("no-store", index.headers().firstValue("Cache-Control").get())

        val box = java.net.URLEncoder.encode("sms#+15550000000", Charsets.UTF_8)
        val page = get("/ui/message?box=$box&id=${created["id"].asText()}", browser)
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("&lt;b&gt;hi&lt;/b&gt;"))
        assertEquals(204, post("/api/messages/${created["id"].asText()}/ack").statusCode())
    }

    @Test
    fun `ui delete requires a same-origin post`() {
        val id = json(post("/api/mock/inbound", mapOf("from" to "+15551230011", "text" to "delete me")).body())["id"].asText()
        val box = java.net.URLEncoder.encode("sms#+15550000000", Charsets.UTF_8)
        val browser = client("browser-test")
        fun deleteWith(vararg headers: String) = browser.send(
            HttpRequest.newBuilder(uri("/ui/delete"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .apply { headers.toList().chunked(2).forEach { (k, v) -> header(k, v) } }
                .POST(HttpRequest.BodyPublishers.ofString("box=$box&id=$id")).build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertEquals(200, get("/ui/delete?box=$box&id=$id", browser).statusCode())   // confirmation page
        assertEquals(403, deleteWith().statusCode())                                    // no origin evidence
        assertEquals(403, deleteWith("Sec-Fetch-Site", "cross-site").statusCode())
        assertEquals(403, deleteWith("Origin", "https://evil.example").statusCode())
        assertEquals(403, deleteWith("Sec-Fetch-Site", "same-site", "Origin", "https://localhost:$port").statusCode())
        assertEquals("text/plain", deleteWith().headers().firstValue("Content-Type").get().substringBefore(';'))
        assertEquals(200, get("/ui/message?box=$box&id=$id", browser).statusCode())      // still there

        // What Safari sends: Fetch Metadata says same-origin while Origin is "null"
        val ok = deleteWith("Sec-Fetch-Site", "same-origin", "Origin", "null")
        assertEquals(303, ok.statusCode())
        assertTrue(get("/ui/message?box=$box&id=$id", browser).body().contains("Message not found"))
        assertEquals(404, get("/api/messages/$id").statusCode())
    }

    @Test
    fun `juujarvis can send email, browsers cannot, and invalid mail is rejected`() {
        val ok = post("/api/email/send", mapOf("to" to listOf("alex@example.com"), "subject" to "Hello", "text" to "Body",
            "inReplyTo" to "<orig@example.com>"))
        assertEquals(200, ok.statusCode())
        val sent = json(ok.body())
        assertEquals("MOCK_SENT", sent["status"].asText())
        assertTrue(sent["messageId"].asText().matches(Regex("^<[0-9a-z]{26}@example\\.com>$")))
        assertTrue(json(get("/api/messages/outbox").body()).any { it["id"].asText() == sent["id"].asText() })

        assertEquals(400, post("/api/email/send", mapOf("to" to listOf("x@example.com\r\nBcc: e@evil.example"), "subject" to "s", "text" to "t")).statusCode())
        assertEquals(400, post("/api/email/send", mapOf("to" to emptyList<String>(), "subject" to "s", "text" to "t")).statusCode())
        assertEquals(403, post("/api/email/send", mapOf("to" to listOf("a@example.com"), "subject" to "s", "text" to "t"), client("browser-test")).statusCode())
    }

    private fun connect(frames: LinkedBlockingQueue<JsonNode>): WebSocket =
        client().newWebSocketBuilder()
            .buildAsync(URI.create("wss://localhost:$port/ws"), collector(frames))
            .get(5, TimeUnit.SECONDS)

    @Test
    fun `websocket rejects a client with an untrusted certificate`() {
        val failure = runCatching {
            client("rogue").newWebSocketBuilder()
                .buildAsync(URI.create("wss://localhost:$port/ws"), collector(LinkedBlockingQueue()))
                .get(5, TimeUnit.SECONDS)
        }
        assertTrue(failure.isFailure)
    }

    private fun collector(frames: LinkedBlockingQueue<JsonNode>) = object : WebSocket.Listener {
        private val buffer = StringBuilder()
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                frames.add(json(buffer.toString()))
                buffer.setLength(0)
            }
            webSocket.request(1)
            return null
        }
    }

    private fun awaitFrame(frames: LinkedBlockingQueue<JsonNode>, match: (JsonNode) -> Boolean): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val frame = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (match(frame)) return frame
        }
        fail("Expected WebSocket frame did not arrive")
    }
}
