package com.bridgarvis.mail

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Result of a [PublicHttp.get]: the last status, the host that gave it, and how many redirects were followed. */
data class FetchResult(val status: Int, val host: String, val redirects: Int)

class RefusedUrlException(message: String) : RuntimeException(message)

/**
 * GETs a URL taken from untrusted mail, without letting it reach anything but the public internet:
 * http/https on the default ports only, and every host (including each redirect) must resolve to public
 * addresses only, so loopback, the VPC, link-local (the instance metadata service), carrier NAT and
 * multicast are refused. Up to [maxRedirects] redirects are followed by hand, each one checked again (0 = the
 * redirect response itself is the result). The body is discarded.
 *
 * The host is resolved again by the HTTP client after the check, so DNS rebinding remains possible in
 * principle; callers only use this for mail from allowlisted, authenticated senders.
 */
class PublicHttp(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val timeout: Duration = Duration.ofSeconds(10),
    private val maxRedirects: Int = 5,
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
) {
    fun get(url: String): FetchResult {
        var uri = check(url)
        var redirects = 0
        while (true) {
            val response = client.send(
                HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("User-Agent", "Mozilla/5.0 (compatible; Bridgarvis test mailbox)")
                    .GET().build(),
                HttpResponse.BodyHandlers.discarding()
            )
            val location = response.headers().firstValue("Location").orElse(null)
            if (response.statusCode() !in 300..399 || location == null || redirects >= maxRedirects)
                return FetchResult(response.statusCode(), uri.host, redirects)
            redirects++
            uri = check(uri.resolve(location.trim()).toString())
        }
    }

    /** Parses and checks one URL; throws [RefusedUrlException] if it may not be fetched. */
    fun check(url: String): URI {
        val uri = runCatching { URI(url.trim()) }.getOrElse { throw RefusedUrlException("not a valid URL") }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") throw RefusedUrlException("only http and https")
        if (uri.rawUserInfo != null) throw RefusedUrlException("credentials in URL")
        val host = uri.host?.lowercase() ?: throw RefusedUrlException("no host")
        val port = uri.port
        if (port != -1 && port != 80 && port != 443) throw RefusedUrlException("non-standard port")
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".local"))
            throw RefusedUrlException("internal host name")
        val addresses = runCatching { resolve(host) }.getOrElse { throw RefusedUrlException("host does not resolve") }
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw RefusedUrlException("host is not a public address")
        return uri
    }

    companion object {
        fun isPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val b = address.address.map { it.toInt() and 0xFF }
            return when (address) {
                is Inet4Address -> !(
                    b[0] == 0 || b[0] >= 240 ||                         // "this network", reserved, broadcast
                    (b[0] == 100 && b[1] in 64..127) ||                 // carrier-grade NAT
                    (b[0] == 169 && b[1] == 254) ||                     // link-local (instance metadata)
                    (b[0] == 192 && b[1] == 0 && b[2] in setOf(0, 2)) ||  // IETF protocol, TEST-NET-1
                    (b[0] == 198 && b[1] in 18..19)                     // benchmarking
                )
                is Inet6Address -> !(
                    (b[0] and 0xFE) == 0xFC ||                          // unique local fc00::/7
                    (b[0] == 0xFE && (b[1] and 0xC0) == 0x80) ||        // link-local fe80::/10
                    (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B) ||  // NAT64 64:ff9b::/96
                    (b.take(10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF &&   // IPv4-mapped
                        !isPublic(InetAddress.getByAddress(b.takeLast(4).map { it.toByte() }.toByteArray())))
                )
                else -> false
            }
        }
    }
}
