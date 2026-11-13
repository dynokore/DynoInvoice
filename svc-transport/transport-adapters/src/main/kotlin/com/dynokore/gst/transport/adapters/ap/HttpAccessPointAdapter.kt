package com.dynokore.gst.transport.adapters.ap

import com.dynokore.gst.transport.application.AccessPointAdapters
import com.dynokore.gst.transport.application.AccessPointPort
import com.dynokore.gst.transport.application.NotificationPage
import com.dynokore.gst.transport.application.NotificationSource
import com.dynokore.gst.transport.application.ProbeOutcome
import com.dynokore.gst.transport.application.TestClass
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.ApNotification
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.FailureReason
import com.dynokore.gst.transport.domain.NotificationReason
import com.dynokore.gst.transport.domain.NotificationTranslator
import com.dynokore.gst.transport.domain.TransportOutcome
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * The Access Point adapter for an AP that speaks the business API of the certification-kit AP simulator
 * (`POST /ap/v1/{ap path}/submissions`). The envelope, the AP's HTTP statuses and its error codes stop here: the
 * answer is translated to one of the three internal outcomes (INV-REQ-539 behaviour 2).
 *
 * The AP's receipt (custody) is the acknowledgement of an accepted outcome; the IRAS acknowledgement arrives later as
 * a notification (INV-T-4982). Every AP status other than a receipt or a structural refusal, a timeout and any I/O
 * failure is a transient failure.
 */
class HttpAccessPointAdapter(
    override val adapterId: AdapterId,
    private val endpoints: ApEndpoints,
    private val apPath: String,
    private val timeout: Duration,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
    private val onUnknownType: (apNotificationId: String?, type: String?) -> Unit = { id, type ->
        LOG.warn("AP notification {} of adapter {} has the unknown type '{}' and is skipped", id, adapterId, type)
    },
) : AccessPointPort,
    NotificationSource {
    private val mapper = JsonMapper.builder().build()

    /** The URI of the last request this adapter sent, for diagnostics and the endpoint test (INV-REQ-501). */
    @Volatile
    var lastRequestUri: URI? = null
        private set

    override fun submit(
        doc: DocRef,
        payload: String,
    ): TransportOutcome {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "packageId" to "${doc.docUuid}-v${doc.version}",
                    "documents" to
                        listOf(
                            mapOf(
                                "docUuid" to doc.docUuid.toString(),
                                "version" to doc.version,
                                "documentType" to "INVOICE",
                                "sizeBytes" to payload.toByteArray(Charsets.UTF_8).size,
                                "payload" to payload,
                            ),
                        ),
                ),
            )
        val base = endpoints.active().toString().trimEnd('/')
        val target = URI.create("$base/ap/v1/$apPath/submissions")
        lastRequestUri = target
        val request =
            HttpRequest
                .newBuilder(target)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return try {
            translate(client.send(request, HttpResponse.BodyHandlers.ofString()))
        } catch (_: IOException) {
            TransportOutcome.TransientFailure
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            TransportOutcome.TransientFailure
        }
    }

    /**
     * One page of the AP's notifications after [cursor] (`GET /notifications?after=&limit=` of the simulator), in the
     * application form a callback carries (INV-Q-9670 A). An item of an unknown type is reported by notification id
     * and skipped; a page with no `nextCursor` keeps [cursor]. An AP answer other than 200, a timeout, an I/O failure
     * or a body that is not a page is a failed read: it throws and the caller's cursor stays where it was.
     */
    override fun pageAfter(
        adapter: AdapterId,
        cursor: String?,
    ): NotificationPage {
        require(adapter == adapterId) { "adapter $adapter is not served by $adapterId" }
        val base = endpoints.active().toString().trimEnd('/')
        val query =
            buildList {
                if (cursor != null) add("after=" + URLEncoder.encode(cursor, Charsets.UTF_8))
                add("limit=$PAGE_LIMIT")
            }.joinToString("&")
        val target = URI.create("$base/ap/v1/$apPath/notifications?$query")
        lastRequestUri = target
        val request =
            HttpRequest
                .newBuilder(target)
                .timeout(timeout)
                .GET()
                .build()
        val root = parsePage(send(request))
        val items = root.get("notifications")
        if (items == null || !items.isArray) throw AccessPointReadException("the AP page has no notifications array")
        val next = root.get("nextCursor")?.takeUnless { it.isNull || it.isMissingNode }?.asString()
        return NotificationPage(items.mapNotNull(::item), next ?: cursor)
    }

    /**
     * The probe of "Test connection" (INV-REQ-835): one read of the AP's notification page with limit 1, which changes
     * nothing at the AP and moves no cursor. The answer is classified, never passed on raw: an I/O failure or a timeout
     * is UNREACHABLE; 401 or 403 is AUTHENTICATION_REFUSED; a 200 whose body is the expected page is SUCCESS; anything
     * else (another status, a body that is not the page) was reached and answered, but not as the contract says:
     * UNEXPECTED_RESPONSE. The detail is a code only (INV-Q-2944).
     */
    fun probe(): ProbeOutcome {
        val base = endpoints.active().toString().trimEnd('/')
        val target = URI.create("$base/ap/v1/$apPath/notifications?limit=1")
        lastRequestUri = target
        val request =
            HttpRequest
                .newBuilder(target)
                .timeout(timeout)
                .GET()
                .build()
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (_: java.net.http.HttpTimeoutException) {
                return ProbeOutcome(TestClass.UNREACHABLE, "AP_TIMEOUT")
            } catch (_: IOException) {
                return ProbeOutcome(TestClass.UNREACHABLE, "AP_UNREACHABLE")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return ProbeOutcome(TestClass.UNREACHABLE, "AP_INTERRUPTED")
            }
        return when {
            response.statusCode() == HTTP_UNAUTHORIZED || response.statusCode() == HTTP_FORBIDDEN -> {
                ProbeOutcome(TestClass.AUTHENTICATION_REFUSED, "AP_AUTH_REFUSED")
            }

            response.statusCode() == HTTP_OK && isPage(response.body()) -> {
                ProbeOutcome(TestClass.SUCCESS)
            }

            response.statusCode() == HTTP_OK -> {
                ProbeOutcome(TestClass.UNEXPECTED_RESPONSE, "AP_BODY_UNEXPECTED")
            }

            else -> {
                ProbeOutcome(TestClass.UNEXPECTED_RESPONSE, "AP_STATUS_${response.statusCode()}")
            }
        }
    }

    private fun isPage(body: String): Boolean =
        try {
            mapper.readTree(body)?.get("notifications")?.isArray == true
        } catch (_: tools.jackson.core.JacksonException) {
            false
        }

    private fun send(request: HttpRequest): String {
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                throw AccessPointReadException("the AP page could not be read", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AccessPointReadException("the AP page read was interrupted", e)
            }
        return bodyOf200(response)
    }

    private fun bodyOf200(response: HttpResponse<String>): String {
        if (response.statusCode() != HTTP_OK) {
            throw AccessPointReadException("the AP answered ${response.statusCode()} to the page read")
        }
        return response.body()
    }

    private fun parsePage(body: String): JsonNode =
        try {
            mapper.readTree(body)
        } catch (e: tools.jackson.core.JacksonException) {
            throw AccessPointReadException("the AP page is not JSON", e)
        }

    private fun item(node: JsonNode): ApNotification? {
        val id = node.text("notificationId")
        val type = node.text("type")
        val kind = KIND_OF_TYPE[type]
        if (kind == null) {
            onUnknownType(id, type)
            return null
        }
        return ApNotification(
            apNotificationId = id,
            kind = kind,
            docUuid = node.text("docUuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            version = node.get("version")?.takeIf { it.isIntegralNumber }?.asInt(),
            acknowledgementId = node.text("acknowledgementId"),
            reasons =
                node.get("reasons")?.takeIf { it.isArray }?.toList()?.map {
                    NotificationReason(it.text("code"), it.text("message"))
                },
        )
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.asString()

    private fun translate(response: HttpResponse<String>): TransportOutcome =
        when (response.statusCode()) {
            HTTP_OK, HTTP_ACCEPTED -> {
                val receipt =
                    runCatching {
                        mapper
                            .readTree(
                                response.body(),
                            ).get("apSubmissionId")
                            ?.asString()
                    }.getOrNull()
                if (receipt.isNullOrBlank()) {
                    TransportOutcome.TransientFailure
                } else {
                    TransportOutcome.Accepted(receipt)
                }
            }

            HTTP_UNPROCESSABLE -> {
                TransportOutcome.Rejected(
                    listOf(FailureReason(FailureReason.PACKAGE_REFUSED, null, "the payload was refused")),
                )
            }

            else -> {
                TransportOutcome.TransientFailure
            }
        }

    private companion object {
        val LOG: Logger = LoggerFactory.getLogger(HttpAccessPointAdapter::class.java)
        const val PAGE_LIMIT = 50

        /** The simulator's own type names to the kinds a callback carries (INV-Q-9670 A). */
        val KIND_OF_TYPE: Map<String?, String> =
            mapOf(
                "IRAS_ACKNOWLEDGEMENT" to NotificationTranslator.ACKNOWLEDGEMENT,
                "IRAS_REJECTION" to NotificationTranslator.REJECTION,
                "DELIVERY_STATUS" to NotificationTranslator.DELIVERY_STATUS,
                "INBOUND_DOCUMENT" to NotificationTranslator.INBOUND_NETWORK_DOCUMENT,
            )
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_ACCEPTED = 202
        const val HTTP_UNPROCESSABLE = 422
    }
}

/** The AP's notification page could not be read: nothing was taken from it and the poll cursor is unchanged. */
class AccessPointReadException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The registry of the adapters of this installation. */
class AccessPointRegistry(
    ports: List<AccessPointPort>,
) : AccessPointAdapters {
    private val byId: Map<AdapterId, AccessPointPort> = ports.associateBy { it.adapterId }

    init {
        require(byId.size == ports.size) { "an adapter id is registered once" }
    }

    override fun find(id: AdapterId): AccessPointPort? = byId[id]

    override fun ids(): Set<AdapterId> = byId.keys
}
