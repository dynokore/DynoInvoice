package com.dynokore.gst.controlPlane.adapters.tenancy

import com.dynokore.gst.controlPlane.application.commercial.CreateTenantCall
import com.dynokore.gst.controlPlane.application.commercial.TenantCreation
import com.dynokore.gst.controlPlane.application.commercial.TenantCreator
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * tenancy-svc `createTenant` over HTTP (control-plane-svc->tenancy-svc, INV-API-3495): POST of the CreateTenantRequest
 * with the tenantRef minted by this service, the planRef unchanged (INV-Q-8691 A) and the elected `dataRegion`
 * (INV-Q-8675 A), bounded by [timeout]. A tenant counts as created only on a 2xx answer; a timeout, an I/O failure
 * or any other status leaves the request PENDING. The Idempotency-Key derives from the contract reference, so a retry
 * of the same request is a repeat at tenancy-svc.
 */
class HttpTenantCreator(
    private val baseUrl: URI,
    private val timeout: Duration,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : TenantCreator {
    private val log = LoggerFactory.getLogger(HttpTenantCreator::class.java)
    private val json = JsonMapper.builder().build()

    override fun create(call: CreateTenantCall): TenantCreation {
        val body =
            json.writeValueAsString(
                linkedMapOf(
                    "contractReference" to call.contractReference.value,
                    "legalName" to call.legalName,
                    "planRef" to call.planRef.value,
                    "tenantRef" to call.tenantRef.value,
                    "dataRegion" to call.dataRegion.value,
                ),
            )
        val request =
            HttpRequest
                .newBuilder(baseUrl.resolve(PATH))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header(
                    "Idempotency-Key",
                    UUID.nameUUIDFromBytes(call.contractReference.value.toByteArray()).toString(),
                ).header("Authorization", call.credential.value)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return try {
            val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            if (status in SUCCESS_FIRST..SUCCESS_LAST) TenantCreation.Created else TenantCreation.Pending
        } catch (e: IOException) {
            log.warn("createTenant did not complete: {}", e.javaClass.simpleName)
            TenantCreation.Pending
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            TenantCreation.Pending
        }
    }

    companion object {
        const val PATH = "/internal/v1/tenancy/tenants"
        private const val SUCCESS_FIRST = 200
        private const val SUCCESS_LAST = 299
    }
}
