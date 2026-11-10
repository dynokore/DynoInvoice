package com.dynokore.gst.controlPlane.adapters.tenancy

import com.dynokore.gst.controlPlane.application.schedule.TenantOpener
import com.dynokore.gst.controlPlane.application.schedule.TenantOpening
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenSource
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * tenancy-svc `openTenant` over HTTP (control-plane-svc->tenancy-svc, `x-caller-auth: service-job`): a POST of the
 * OpenTenantRequest with the `scheduleRef`, under the workload token of this service and no end-user token, bounded by
 * [timeout] (the contract's `x-timeout` 5000). Only a 2xx answer is [TenantOpening.OPENED]; a timeout, an I/O failure
 * or any other status (a CLOSED tenant answers 409) is [TenantOpening.NOT_OPENED] and changes nothing here. The
 * Idempotency-Key derives from the schedule reference, so a retry is a repeat at tenancy-svc.
 */
class HttpTenantOpener(
    private val baseUrl: URI,
    private val workloadToken: WorkloadTokenSource,
    private val timeout: Duration = TIMEOUT,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : TenantOpener {
    private val log = LoggerFactory.getLogger(HttpTenantOpener::class.java)
    private val json = JsonMapper.builder().build()

    @Suppress("TooGenericExceptionCaught")
    override fun open(
        tenantRef: CommercialRef,
        scheduleRef: String,
    ): TenantOpening =
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create(urlOf(tenantRef)))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", keyOf(scheduleRef))
                    .header(WorkloadToken.HEADER_NAME, workloadToken.current().value)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyOf(scheduleRef)))
                    .build()
            val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            if (status in SUCCESS_FIRST..SUCCESS_LAST) TenantOpening.OPENED else TenantOpening.NOT_OPENED
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            TenantOpening.NOT_OPENED
        } catch (e: IOException) {
            log.warn("openTenant did not complete: {}", e.javaClass.simpleName)
            TenantOpening.NOT_OPENED
        } catch (e: RuntimeException) {
            log.warn("openTenant failed: {}", e.javaClass.simpleName)
            TenantOpening.NOT_OPENED
        }

    private fun urlOf(tenantRef: CommercialRef): String {
        val tenant = URLEncoder.encode(tenantRef.value, StandardCharsets.UTF_8)
        return baseUrl.toString().trimEnd('/') + "/internal/v1/tenancy/tenants/$tenant/open"
    }

    private fun keyOf(scheduleRef: String) = UUID.nameUUIDFromBytes("openTenant:$scheduleRef".toByteArray()).toString()

    private fun bodyOf(scheduleRef: String) = json.writeValueAsString(linkedMapOf("scheduleRef" to scheduleRef))

    companion object {
        /** The `x-timeout` of `openTenant` in the pinned tenancy contract. */
        val TIMEOUT: Duration = Duration.ofMillis(5000)
        private const val SUCCESS_FIRST = 200
        private const val SUCCESS_LAST = 299
    }
}
