package com.bridgarvis.alexa

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class AlexaVerificationException(message: String) : RuntimeException(message)

/**
 * Checks that a request really comes from Alexa for our skill, as Amazon requires for skills not hosted
 * on Lambda:
 *  - the signing certificate chain is fetched only from Amazon's location (https, s3.amazonaws.com,
 *    path under /echo.api/), chains to a trusted root, is currently valid and names echo-api.amazon.com
 *  - Signature-256 (SHA256withRSA) verifies over the raw request body
 *  - the request timestamp is within 150 seconds
 *  - the application id is the configured skill id
 *
 * [fetch] downloads a certificate chain (PEM) from an already validated URL; certificates are cached
 * per URL until they expire.
 */
class AlexaRequestVerifier(
    private val skillId: String,
    private val mapper: ObjectMapper,
    private val trustAnchors: Set<TrustAnchor> = defaultTrustAnchors(),
    private val fetch: (URI) -> ByteArray,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<URI, X509Certificate>()

    /** Verifies the request and returns its parsed body; throws [AlexaVerificationException] otherwise. */
    fun verify(certChainUrl: String?, signature256: String?, body: ByteArray): JsonNode {
        if (skillId.isBlank()) fail("no skill id configured")
        val url = certUrl(certChainUrl ?: fail("missing SignatureCertChainUrl")) ?: fail("bad certificate URL")
        val signature = runCatching { Base64.getMimeDecoder().decode(signature256 ?: fail("missing Signature-256")) }
            .getOrElse { fail("bad signature encoding") }

        val signer = signingCertificate(url)
        val valid = Signature.getInstance("SHA256withRSA").run {
            initVerify(signer.publicKey)
            update(body)
            runCatching { verify(signature) }.getOrDefault(false)
        }
        if (!valid) fail("signature does not match")

        val json = runCatching { mapper.readTree(body) }.getOrNull() ?: fail("body is not JSON")
        val timestamp = runCatching { Instant.parse(json.path("request").path("timestamp").asText()) }
            .getOrNull() ?: fail("missing timestamp")
        if (Duration.between(timestamp, clock.instant()).abs() > MAX_AGE) fail("stale timestamp")

        val appId = json.path("context").path("System").path("application").path("applicationId").asText(null)
            ?: json.path("session").path("application").path("applicationId").asText(null)
        if (appId != skillId) fail("unexpected application id")
        return json
    }

    private fun signingCertificate(url: URI): X509Certificate {
        cache[url]?.let { cert ->
            if (runCatching { cert.checkValidity(Date.from(clock.instant())) }.isSuccess) return cert
            cache.remove(url)
        }
        val chain = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(fetch(url)))
                .map { it as X509Certificate }
        }.getOrElse { fail("certificate chain could not be loaded") }
        if (chain.isEmpty()) fail("empty certificate chain")
        val leaf = chain.first()

        runCatching { leaf.checkValidity(Date.from(clock.instant())) }.onFailure { fail("certificate not valid now") }
        val names = leaf.subjectAlternativeNames.orEmpty().filter { it[0] == 2 }.map { it[1].toString() }
        if (SIGNER_NAME !in names) fail("certificate is not for $SIGNER_NAME")

        val path = chain.filterNot { it.subjectX500Principal == it.issuerX500Principal }
        runCatching {
            val params = PKIXParameters(trustAnchors).apply {
                isRevocationEnabled = false
                date = Date.from(clock.instant())
            }
            CertPathValidator.getInstance("PKIX").validate(CertificateFactory.getInstance("X.509").generateCertPath(path), params)
        }.onFailure { fail("certificate chain not trusted") }

        cache[url] = leaf
        log.info("Alexa signing certificate loaded, valid until {}", leaf.notAfter.toInstant())
        return leaf
    }

    private fun fail(reason: String): Nothing = throw AlexaVerificationException(reason)

    companion object {
        const val SIGNER_NAME = "echo-api.amazon.com"
        private val MAX_AGE: Duration = Duration.ofSeconds(150)

        /**
         * The certificate URL normalized, or null unless it is https on s3.amazonaws.com (port 443 if
         * given) with a path under /echo.api/ (case-sensitive) after resolving dot segments.
         */
        fun certUrl(raw: String): URI? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            if (!"https".equals(uri.scheme, ignoreCase = true)) return null
            if (!"s3.amazonaws.com".equals(uri.host, ignoreCase = true)) return null
            if (uri.port != -1 && uri.port != 443) return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val rawPath = uri.rawPath ?: return null
            if ('%' in rawPath) return null
            val path = URI(null, null, rawPath, null).normalize().path
            if (!path.startsWith("/echo.api/")) return null
            return URI("https", "s3.amazonaws.com", path, null)
        }

        fun defaultTrustAnchors(): Set<TrustAnchor> {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>()
                .flatMap { it.acceptedIssuers.toList() }
                .map { TrustAnchor(it, null) }
                .toSet()
        }
    }
}
