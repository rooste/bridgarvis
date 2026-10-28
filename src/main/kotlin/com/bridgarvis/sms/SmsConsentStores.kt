package com.bridgarvis.sms

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class MemorySmsConsentStore : SmsConsentStore {
    private val records = ConcurrentHashMap<String, SmsConsentRecord>()
    override fun get(number: String) = records[number]
    override fun set(number: String, consent: Consent, source: String) {
        records[number] = SmsConsentRecord(number, consent, source, Instant.now())
    }
    override fun all() = records.values.sortedBy { it.number }
}

/** One item per number: {number, consent, source, updatedAt}. */
class DynamoSmsConsentStore(private val db: DynamoDbClient, private val table: String) : SmsConsentStore {
    override fun get(number: String): SmsConsentRecord? =
        db.getItem { it.tableName(table).key(mapOf("number" to s(number))).consistentRead(true) }.item()
            .takeIf { it.isNotEmpty() }?.let(::record)

    override fun set(number: String, consent: Consent, source: String) {
        db.putItem {
            it.tableName(table).item(mapOf(
                "number" to s(number), "consent" to s(consent.name), "source" to s(source),
                "updatedAt" to s(Instant.now().toString())
            ))
        }
    }

    override fun all(): List<SmsConsentRecord> =
        db.scanPaginator { it.tableName(table) }.items().map(::record).sortedBy { it.number }

    private fun record(i: Map<String, AttributeValue>) = SmsConsentRecord(
        number = i.getValue("number").s(),
        consent = runCatching { Consent.valueOf(i["consent"]!!.s()) }.getOrDefault(Consent.OPTED_OUT),
        source = i["source"]?.s() ?: "",
        updatedAt = i["updatedAt"]?.s()?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
    )

    private fun s(v: String) = AttributeValue.fromS(v)
}
