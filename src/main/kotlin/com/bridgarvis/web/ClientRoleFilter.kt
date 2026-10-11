package com.bridgarvis.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.cert.X509Certificate
import javax.naming.ldap.LdapName

/**
 * Least privilege per client certificate. TLS already guarantees the certificate is one of the pinned
 * ones; this decides what each may use, by its CN:
 *   juujarvis   → /api/..., /ws   (the assistant)
 *   browser-...  → /ui/...        (people reading mail in a browser)
 * Requests without a client certificate only exist on the plain-HTTP localhost management port.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ClientRoleFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!request.isSecure) return chain.doFilter(request, response)

        @Suppress("UNCHECKED_CAST")
        val certs = request.getAttribute("jakarta.servlet.request.X509Certificate") as? Array<X509Certificate>
        val cn = certs?.firstOrNull()?.let { commonName(it) }
        val path = request.requestURI

        val allowed = when {
            cn == "juujarvis" -> path.startsWith("/api/") || path == "/ws"
            cn != null && cn.startsWith("browser-") ->
                path == "/" || path == "/ui" || path.startsWith("/ui/") || path == "/favicon.ico"
            else -> false
        }
        if (allowed) {
            request.setAttribute(CLIENT_ATTRIBUTE, cn)
            chain.doFilter(request, response)
        } else {
            log.warn("Denied {} {} for client {}", request.method, path, cn ?: "none")
            response.sendError(HttpServletResponse.SC_FORBIDDEN)
        }
    }

    private fun commonName(cert: X509Certificate): String? =
        LdapName(cert.subjectX500Principal.name).rdns.firstOrNull { it.type.equals("CN", true) }?.value?.toString()

    companion object {
        const val CLIENT_ATTRIBUTE = "bridgarvis.client"
    }
}
