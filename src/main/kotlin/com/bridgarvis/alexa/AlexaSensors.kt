package com.bridgarvis.alexa

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.ssm.SsmClient
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Echo-side trigger for late answers: one virtual contact sensor per Echo (smart home skill
 * "Juujarvis Sensors"). Opening it runs the household's routine "when Juujarvis <room> opens → open the
 * Juujarvis skill on that Echo", which then speaks the waiting answer.
 */
interface AlexaSensorTrigger {
    /** Creates or renames the Echo's sensor ("Juujarvis <room>" in the Alexa app). */
    fun roomKnown(deviceId: String, room: String) {}

    /** Whether opening this Echo's sensor is possible (sensor exists, account linked). */
    fun canAnnounce(deviceId: String?): Boolean = false

    /** Opens the Echo's sensor (and closes it again shortly after). False if not possible. */
    fun open(deviceId: String): Boolean = false
}

@Configuration
class AlexaSensorConfig {
    /** Without the sensors table (local runs, tests) late answers wait until the skill is opened by hand. */
    @Bean
    fun alexaSensors(
        db: ObjectProvider<DynamoDbClient>,
        mapper: ObjectMapper,
        @Value("\${bridgarvis.alexa.sensors-table:}") table: String,
        @Value("\${bridgarvis.alexa.smarthome-params:/bridgarvis/alexa-smarthome}") params: String
    ): AlexaSensorTrigger {
        val client = db.ifAvailable
        return if (table.isBlank() || client == null) object : AlexaSensorTrigger {} else AlexaSensors(client, mapper, table, params)
    }
}

/**
 * Sensors in DynamoDB (items "sensor#<endpointId>", read by the smart home Lambda for discovery) and
 * events to the Alexa event gateway. The gateway token comes from account linking (the Lambda stores it
 * at AcceptGrant); it is refreshed here with the skill's client credentials from Parameter Store.
 */
class AlexaSensors(
    private val db: DynamoDbClient,
    private val mapper: ObjectMapper,
    private val table: String,
    private val params: String
) : AlexaSensorTrigger {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val ssm by lazy { SsmClient.create() }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "alexa-sensors").apply { isDaemon = true } }

    override fun roomKnown(deviceId: String, room: String) {
        val id = endpointId(deviceId)
        val name = room.replace(Regex("[^\\p{L}\\p{N} '-]"), "").trim().take(40).ifEmpty { return }
        if (sensor(id)?.get("room")?.s() == name) return
        db.putItem {
            it.tableName(table).item(mapOf(
                "pk" to s("sensor#$id"), "endpointId" to s(id), "deviceId" to s(deviceId),
                "room" to s(name), "state" to s("NOT_DETECTED"), "updatedAt" to s(Instant.now().toString())
            ))
        }
        log.info("Alexa sensor {} is now 'Juujarvis {}'", id, name)
        scheduler.execute {
            val token = accessToken() ?: return@execute
            send(mapOf("event" to mapOf(
                "header" to header("Alexa.Discovery", "AddOrUpdateReport"),
                "payload" to mapOf("endpoints" to listOf(endpoint(id, name)), "scope" to bearer(token))
            )), token, "AddOrUpdateReport")
        }
    }

    override fun canAnnounce(deviceId: String?): Boolean =
        deviceId != null && sensor(endpointId(deviceId)) != null && tokenItem() != null

    override fun open(deviceId: String): Boolean {
        val id = endpointId(deviceId)
        if (sensor(id) == null || tokenItem() == null) return false
        scheduler.execute {
            if (report(id, "DETECTED")) {
                scheduler.schedule({ report(id, "NOT_DETECTED") }, 15, TimeUnit.SECONDS)
            }
        }
        return true
    }

    private fun report(id: String, state: String): Boolean {
        val token = accessToken() ?: return false
        db.updateItem {
            it.tableName(table).key(mapOf("pk" to s("sensor#$id")))
                .updateExpression("SET #s = :s, updatedAt = :u")
                .expressionAttributeNames(mapOf("#s" to "state"))
                .expressionAttributeValues(mapOf(":s" to s(state), ":u" to s(Instant.now().toString())))
        }
        val now = Instant.now().toString()
        return send(mapOf(
            "event" to mapOf(
                "header" to header("Alexa", "ChangeReport"),
                "endpoint" to mapOf("scope" to bearer(token), "endpointId" to id),
                "payload" to mapOf("change" to mapOf(
                    "cause" to mapOf("type" to "PHYSICAL_INTERACTION"),
                    "properties" to listOf(mapOf(
                        "namespace" to "Alexa.ContactSensor", "name" to "detectionState", "value" to state,
                        "timeOfSample" to now, "uncertaintyInMilliseconds" to 0
                    ))
                ))
            ),
            "context" to mapOf("properties" to listOf(mapOf(
                "namespace" to "Alexa.EndpointHealth", "name" to "connectivity", "value" to mapOf("value" to "OK"),
                "timeOfSample" to now, "uncertaintyInMilliseconds" to 0
            )))
        ), token, "ChangeReport $state")
    }

    /** The event gateway wants the token both in the event's scope and as the bearer header. */
    private fun send(event: Map<String, Any>, token: String, what: String): Boolean = try {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(EVENT_GATEWAY))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(event)))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        val ok = response.statusCode() == 202
        if (ok) log.info("Alexa event {} accepted", what)
        else log.warn("Alexa event {} returned HTTP {} {}", what, response.statusCode(), response.body().take(200))
        ok
    } catch (e: Exception) {
        log.warn("Alexa event {} failed: {}", what, e.message)
        false
    }

    /** A valid event-gateway access token, refreshed with the skill's client credentials when needed. */
    @Synchronized
    private fun accessToken(): String? {
        val item = tokenItem() ?: run {
            log.warn("No Alexa event gateway authorization yet (link the smart home skill in the Alexa app)")
            return null
        }
        val expiresAt = item["accessExpiresAt"]?.n()?.toLongOrNull() ?: 0
        if (Instant.now().epochSecond < expiresAt) return item["accessToken"]?.s()

        val refresh = item["refreshToken"]?.s() ?: return null
        val credentials = ssm.getParameters { it.names("$params/client-id", "$params/client-secret").withDecryption(true) }
            .parameters().associate { it.name().substringAfterLast('/') to it.value() }
        val clientId = credentials["client-id"] ?: return null.also { log.warn("Smart home client id missing in Parameter Store") }
        val clientSecret = credentials["client-secret"] ?: return null.also { log.warn("Smart home client secret missing in Parameter Store") }
        return try {
            val form = mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId, "client_secret" to clientSecret)
                .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
            val response = http.send(
                HttpRequest.newBuilder(URI.create(LWA_TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (response.statusCode() != 200) {
                log.warn("Alexa token refresh returned HTTP {}", response.statusCode())
                return null
            }
            val tokens = mapper.readTree(response.body())
            val access = tokens["access_token"].asText()
            db.updateItem {
                it.tableName(table).key(mapOf("pk" to s("token")))
                    .updateExpression("SET accessToken = :a, accessExpiresAt = :e, refreshToken = :r, updatedAt = :u")
                    .expressionAttributeValues(mapOf(
                        ":a" to s(access),
                        ":e" to AttributeValue.fromN((Instant.now().epochSecond + tokens.path("expires_in").asLong(3600) - 60).toString()),
                        ":r" to s(tokens["refresh_token"]?.asText() ?: refresh),
                        ":u" to s(Instant.now().toString())
                    ))
            }
            access
        } catch (e: Exception) {
            log.warn("Alexa token refresh failed: {}", e.message)
            null
        }
    }

    private fun tokenItem(): Map<String, AttributeValue>? =
        db.getItem { it.tableName(table).key(mapOf("pk" to s("token"))) }.item().takeIf { it.isNotEmpty() }

    private fun sensor(id: String): Map<String, AttributeValue>? =
        db.getItem { it.tableName(table).key(mapOf("pk" to s("sensor#$id"))) }.item().takeIf { it.isNotEmpty() }

    private fun header(namespace: String, name: String) =
        mapOf("namespace" to namespace, "name" to name, "payloadVersion" to "3", "messageId" to UUID.randomUUID().toString())

    private fun bearer(token: String) = mapOf("type" to "BearerToken", "token" to token)

    /** Same structure as the Lambda's discovery response. */
    private fun endpoint(id: String, room: String) = mapOf(
        "endpointId" to id,
        "manufacturerName" to "Juujarvis",
        "friendlyName" to "Juujarvis $room",
        "description" to "Opens when Juujarvis has an answer waiting for the $room Echo",
        "displayCategories" to listOf("CONTACT_SENSOR"),
        "capabilities" to listOf(
            mapOf("type" to "AlexaInterface", "interface" to "Alexa.ContactSensor", "version" to "3",
                "properties" to mapOf("supported" to listOf(mapOf("name" to "detectionState")), "proactivelyReported" to true, "retrievable" to true)),
            mapOf("type" to "AlexaInterface", "interface" to "Alexa.EndpointHealth", "version" to "3",
                "properties" to mapOf("supported" to listOf(mapOf("name" to "connectivity")), "proactivelyReported" to true, "retrievable" to true)),
            mapOf("type" to "AlexaInterface", "interface" to "Alexa", "version" to "3")
        )
    )

    private fun s(value: String) = AttributeValue.fromS(value)

    companion object {
        private const val EVENT_GATEWAY = "https://api.amazonalexa.com/v3/events"
        private const val LWA_TOKEN_URL = "https://api.amazon.com/auth/o2/token"

        /** Stable, non-reversible sensor id for an Echo (the device id itself stays out of Alexa's device list). */
        fun endpointId(deviceId: String): String =
            "echo-" + MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }
}
