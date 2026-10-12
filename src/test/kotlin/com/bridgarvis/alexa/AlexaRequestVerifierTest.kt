package com.bridgarvis.alexa

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AlexaRequestVerifierTest {

    private val mapper = ObjectMapper()
    private val skillId = "amzn1.ask.skill.test"
    private val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    private val certUrl = "https://s3.amazonaws.com/echo.api/echo-api-cert-12.pem"

    private fun resource(name: String) = javaClass.getResourceAsStream("/alexa/$name")!!.readBytes()

    private val anchors = setOf(TrustAnchor(
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(resource("ca.pem"))) as X509Certificate,
        null
    ))

    private fun verifier(chain: String = "signer-chain.pem", fetched: MutableList<URI> = mutableListOf()) =
        AlexaRequestVerifier(skillId, mapper, anchors, fetch = { fetched += it; resource(chain) }, clock = Clock.fixed(now, ZoneOffset.UTC))

    private fun body(timestamp: Instant = now, appId: String = skillId) = mapper.writeValueAsBytes(mapOf(
        "version" to "1.0",
        "context" to mapOf("System" to mapOf("application" to mapOf("applicationId" to appId))),
        "request" to mapOf("type" to "LaunchRequest", "requestId" to "r1", "timestamp" to timestamp.toString())
    ))

    private fun sign(body: ByteArray, key: String = "signer.key"): String {
        val pem = String(resource(key)).replace(Regex("-----[A-Z ]+-----|\\s"), "")
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        return Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey); update(body); sign()
        })
    }

    @Test
    fun `accepts a correctly signed request for our skill`() {
        val body = body()
        val json = verifier().verify(certUrl, sign(body), body)
        assertEquals("LaunchRequest", json["request"]["type"].asText())
    }

    @Test
    fun `fetches the certificate once and reuses it`() {
        val fetched = mutableListOf<URI>()
        val v = verifier(fetched = fetched)
        repeat(3) { val b = body(); v.verify(certUrl, sign(b), b) }
        assertEquals(listOf(URI("https://s3.amazonaws.com/echo.api/echo-api-cert-12.pem")), fetched)
    }

    @Test
    fun `rejects a tampered body`() {
        val body = body()
        val tampered = body(appId = skillId).also { it[it.size - 3] = 'x'.code.toByte() }
        assertFailsWith<AlexaVerificationException> { verifier().verify(certUrl, sign(body), tampered) }
    }

    @Test
    fun `rejects a signature by another key`() {
        val body = body()
        assertFailsWith<AlexaVerificationException> { verifier().verify(certUrl, sign(body, "wrongsan.key"), body) }
    }

    @Test
    fun `rejects a certificate not issued for echo-api`() {
        val body = body()
        val e = assertFailsWith<AlexaVerificationException> {
            verifier(chain = "wrongsan-chain.pem").verify(certUrl, sign(body, "wrongsan.key"), body)
        }
        assertEquals("certificate is not for echo-api.amazon.com", e.message)
    }

    @Test
    fun `rejects a chain that does not lead to a trusted root`() {
        val body = body()
        val untrusting = AlexaRequestVerifier(skillId, mapper, AlexaRequestVerifier.defaultTrustAnchors(),
            fetch = { resource("signer-chain.pem") }, clock = Clock.fixed(now, ZoneOffset.UTC))
        assertFailsWith<AlexaVerificationException> { untrusting.verify(certUrl, sign(body), body) }
    }

    @Test
    fun `rejects stale and future timestamps`() {
        for (t in listOf(now.minusSeconds(151), now.plusSeconds(151))) {
            val body = body(timestamp = t)
            assertFailsWith<AlexaVerificationException> { verifier().verify(certUrl, sign(body), body) }
        }
        val body = body(timestamp = now.minusSeconds(100))
        verifier().verify(certUrl, sign(body), body)
    }

    @Test
    fun `rejects another skill`() {
        val body = body(appId = "amzn1.ask.skill.other")
        assertFailsWith<AlexaVerificationException> { verifier().verify(certUrl, sign(body), body) }
    }

    @Test
    fun `rejects missing headers and unconfigured skill id`() {
        val body = body()
        assertFailsWith<AlexaVerificationException> { verifier().verify(null, sign(body), body) }
        assertFailsWith<AlexaVerificationException> { verifier().verify(certUrl, null, body) }
        val unconfigured = AlexaRequestVerifier("", mapper, anchors, fetch = { resource("signer-chain.pem") })
        assertFailsWith<AlexaVerificationException> { unconfigured.verify(certUrl, sign(body), body) }
    }

    @Test
    fun `certificate URL rules follow Amazon's examples`() {
        val valid = listOf(
            "https://s3.amazonaws.com/echo.api/echo-api-cert.pem",
            "https://s3.amazonaws.com:443/echo.api/echo-api-cert.pem",
            "https://s3.amazonaws.com/echo.api/../echo.api/echo-api-cert.pem",
            "HTTPS://S3.AmAzOnAwS.CoM/echo.api/echo-api-cert.pem"
        )
        val invalid = listOf(
            "http://s3.amazonaws.com/echo.api/echo-api-cert.pem",
            "https://notamazon.com/echo.api/echo-api-cert.pem",
            "https://s3.amazonaws.com/EcHo.aPi/echo-api-cert.pem",
            "https://s3.amazonaws.com/invalid.path/echo-api-cert.pem",
            "https://s3.amazonaws.com:563/echo.api/echo-api-cert.pem",
            "https://s3.amazonaws.com/echo.api/../evil/cert.pem",
            "https://s3.amazonaws.com/echo.api/%2e%2e/evil/cert.pem",
            "https://user@s3.amazonaws.com/echo.api/echo-api-cert.pem",
            "https://s3.amazonaws.com.evil.net/echo.api/echo-api-cert.pem"
        )
        valid.forEach { assertNotNull(AlexaRequestVerifier.certUrl(it), it) }
        invalid.forEach { assertNull(AlexaRequestVerifier.certUrl(it), it) }
    }
}
