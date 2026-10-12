package com.bridgarvis.alexa

import org.springframework.boot.ssl.SslBundle
import org.springframework.boot.ssl.pem.PemSslStoreBundle
import org.springframework.boot.ssl.pem.PemSslStoreDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Alexa connector exposes only POST /alexa, and /alexa is not reachable on the mTLS port. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.ssl.bundle.pem.bridgarvis.keystore.certificate=classpath:certs/server.pem",
        "spring.ssl.bundle.pem.bridgarvis.keystore.private-key=classpath:certs/server.key",
        "spring.ssl.bundle.pem.bridgarvis.truststore.certificate=classpath:certs/clients.pem",
        "management.server.port=0",
        "bridgarvis.alexa.skill-id=amzn1.ask.skill.test"
    ]
)
class AlexaConnectorIntegrationTest {

    @LocalServerPort
    var port: Int = 0

    private val plain = HttpClient.newHttpClient()

    private fun alexa(method: String, path: String, body: String = "", headers: Map<String, String> = emptyMap()) =
        plain.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$alexaPort$path"))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        ).statusCode()

    @Test
    fun `unsigned or forged Alexa requests are rejected`() {
        assertEquals(400, alexa("POST", "/alexa", """{"request":{"type":"LaunchRequest"}}"""))
        assertEquals(400, alexa("POST", "/alexa", "{}", mapOf(
            "SignatureCertChainUrl" to "https://evil.example.com/echo.api/cert.pem",
            "Signature-256" to "AAAA"
        )))
    }

    @Test
    fun `nothing but POST alexa is served on the Alexa connector`() {
        assertEquals(404, alexa("GET", "/api/messages"))
        assertEquals(404, alexa("POST", "/api/alexa/x/reply", """{"speech":"hi"}"""))
        assertEquals(404, alexa("POST", "/api/email/send", "{}"))
        assertEquals(404, alexa("GET", "/ui"))
        assertEquals(404, alexa("GET", "/alexa"))
        assertEquals(404, alexa("GET", "/ws"))
    }

    @Test
    fun `alexa is not served on the mTLS port, and late replies report undelivered`() {
        val trust = PemSslStoreDetails.forCertificate("classpath:certs/server.pem")
        val identity = PemSslStoreDetails.forCertificate("classpath:certs/juujarvis.pem").withPrivateKey("classpath:certs/juujarvis.key")
        val mtls = HttpClient.newBuilder().sslContext(SslBundle.of(PemSslStoreBundle(identity, trust)).createSslContext()).build()
        fun post(path: String, body: String) = mtls.send(
            HttpRequest.newBuilder(URI.create("https://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(403, post("/alexa", "{}").statusCode())
        val reply = post("/api/alexa/unknown/reply", """{"speech":"hello"}""")
        assertEquals(200, reply.statusCode())
        assertEquals("""{"delivered":false}""", reply.body())
        assertEquals(400, post("/api/alexa/unknown/reply", """{"speech":" "}""").statusCode())
    }

    companion object {
        private val alexaPort = ServerSocket(0).use { it.localPort }

        @JvmStatic
        @DynamicPropertySource
        fun alexaProperties(registry: DynamicPropertyRegistry) {
            registry.add("bridgarvis.alexa.port") { alexaPort }
        }
    }
}
