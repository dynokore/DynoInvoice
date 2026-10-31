package com.dynokore.gst.controlPlane.adapters.stage

import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The sandbox of the stage over the Access Point business API (`POST /ap/v1/{adapterId}/submissions`): one document per
 * package. The wire contract is provisional (BLK-2), so only the class of the answer is read: 200 or 202 is accepted;
 * every other answer, an unreachable endpoint and an interrupted call are a rejection (fail closed). [target] is
 * `SIMULATOR` until a train has the real integration (INV-REQ-2662).
 */
class AccessPointSandboxSubmitter(
    private val baseUrl: String,
    private val adapterId: String = "primary",
    override val target: String = "SIMULATOR",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
) : SandboxSubmitter {
    private val mapper = JsonMapper.builder().build()

    override fun submit(submission: StagedSubmission): SandboxVerdict {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "packageId" to "PKG-${submission.documentRef}",
                    "documents" to
                        listOf(
                            mapOf(
                                "docUuid" to submission.documentRef,
                                "version" to 1,
                                "documentType" to DOCUMENT_TYPE,
                                "sizeBytes" to submission.payload.toByteArray().size,
                            ),
                        ),
                ),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create("${baseUrl.trimEnd('/')}/ap/v1/$adapterId/submissions"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return try {
            val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            if (status in ACCEPTED) SandboxVerdict.Accepted else SandboxVerdict.Rejected("sandbox answered $status")
        } catch (e: IOException) {
            SandboxVerdict.Rejected("sandbox unreachable: ${e.javaClass.simpleName}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            SandboxVerdict.Rejected("sandbox call interrupted: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        val ACCEPTED = setOf(200, 202)
        const val DOCUMENT_TYPE = "INVOICE"
    }
}
