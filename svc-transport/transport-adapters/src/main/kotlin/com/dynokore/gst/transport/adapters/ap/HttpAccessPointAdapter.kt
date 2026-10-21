package com.dynokore.gst.transport.adapters.ap

import com.dynokore.gst.transport.application.AccessPointAdapters
import com.dynokore.gst.transport.application.AccessPointPort
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.FailureReason
import com.dynokore.gst.transport.domain.TransportOutcome
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
) : AccessPointPort {
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
        const val HTTP_OK = 200
        const val HTTP_ACCEPTED = 202
        const val HTTP_UNPROCESSABLE = 422
    }
}

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
