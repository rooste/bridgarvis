package com.bridgarvis.alexa

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.catalina.connector.Connector
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Configuration
class AlexaConfig {

    @Bean
    fun alexaRequestVerifier(
        @Value("\${bridgarvis.alexa.skill-id:}") skillId: String,
        mapper: ObjectMapper
    ): AlexaRequestVerifier {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        return AlexaRequestVerifier(skillId, mapper, fetch = { url ->
            val response = http.send(
                HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            )
            check(response.statusCode() == 200 && response.body().size < 64 * 1024) { "HTTP ${response.statusCode()}" }
            response.body()
        })
    }

    /**
     * Plain-HTTP connector on localhost for Alexa: nginx terminates TLS for the Alexa host name and
     * forwards only /alexa here. Amazon can't present our client certificate, so it can't use the mTLS port;
     * ClientRoleFilter allows nothing but POST /alexa on this connector.
     */
    @Bean
    fun alexaConnector(@Value("\${bridgarvis.alexa.port:0}") port: Int) =
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> { factory ->
            if (port > 0) factory.addAdditionalTomcatConnectors(Connector().apply {
                this.port = port
                setProperty("address", "127.0.0.1")
            })
        }
}
