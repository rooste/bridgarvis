package com.bridgarvis.sms

import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.MessageType
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.PinpointSmsVoiceV2Exception

/** Sends from the one toll-free number through AWS End User Messaging, with delivery events on [configurationSet]. */
class AwsSmsSender(
    private val client: PinpointSmsVoiceV2Client,
    private val originationIdentity: String,
    private val configurationSet: String?,
    private val optOutList: String = "Default"
) : SmsSender {
    private val log = LoggerFactory.getLogger(javaClass)
    override val live = true

    override fun send(to: String, text: String): String = try {
        client.sendTextMessage {
            it.destinationPhoneNumber(to).originationIdentity(originationIdentity).messageBody(text)
                .messageType(MessageType.TRANSACTIONAL)
            if (!configurationSet.isNullOrBlank()) it.configurationSetName(configurationSet)
        }.messageId()
    } catch (e: PinpointSmsVoiceV2Exception) {
        val detail = e.awsErrorDetails()?.errorMessage() ?: e.message ?: "unknown"
        // The reason sits in the error body (e.g. "Reason": "DESTINATION_PHONE_NUMBER_OPTED_OUT")
        val raw = e.awsErrorDetails()?.rawResponse()?.asUtf8String().orEmpty()
        if ("OPTED_OUT" in raw || "OPTED_OUT" in detail) throw SmsRejected(SmsRejected.OPTED_OUT, "the number has opted out")
        log.warn("SMS to {} failed: {} {}", Phone.masked(to), e.statusCode(), detail)
        throw SmsRejected(SmsRejected.SEND_FAILED, detail.take(300))
    }

    override fun optedOutNumbers(): Set<String> =
        client.describeOptedOutNumbersPaginator { it.optOutListName(optOutList) }
            .optedOutNumbers().mapNotNull { Phone.normalize(it.optedOutNumber()) }.toSet()
}

/** Local runs and tests: nothing leaves the gateway, sends are only recorded. */
class MockSmsSender : SmsSender {
    override val live = false
    override fun send(to: String, text: String) = "mock-" + java.util.UUID.randomUUID()
}
