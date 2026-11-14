package com.dynokore.gst.controlPlane.adapters.tenancy

import com.dynokore.gst.controlPlane.application.schedule.TenantCloser
import com.dynokore.gst.controlPlane.application.schedule.TenantClosing
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
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
 * tenancy-svc `closeTenant` over HTTP (control-plane-svc->tenancy-svc, `x-caller-auth: service-job`): a POST of the
 * CloseTenantRequest with the `scheduleRef`, the `retentionCustodyHolder` and the optional `noticeWindowDays` the
 * schedule recorded, under the workload token of this service and no end-user token, bounded by [timeout] (the
 * contract's `x-timeout` 2000). It never sends a second confirmation or an unsubmitted-document override. Only 202 is
 * [TenantClosing.Closed]; any other status, a timeout or an I/O failure is [TenantClosing.NotClosed] with the
 * refusal's `detail` (API-20 problem). The Idempotency-Key derives from the schedule reference, so a repeat is
 * answered with the original record (API-15).
 */
class HttpTenantCloser(
    private val baseUrl: URI,
    private val workloadToken: WorkloadTokenSource,
    private val timeout: Duration = TIMEOUT,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : TenantCloser {
    private val log = LoggerFactory.getLogger(HttpTenantCloser::class.java)
    private val json = JsonMapper.builder().build()

    @Suppress("TooGenericExceptionCaught")
    override fun close(
        tenantRef: CommercialRef,
        schedule: TenantSchedule,
    ): TenantClosing =
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create(urlOf(tenantRef)))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", keyOf(schedule.scheduleRef))
                    .header(WorkloadToken.HEADER_NAME, workloadToken.current().value)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyOf(schedule)))
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == ACCEPTED) {
                TenantClosing.Closed
            } else {
                TenantClosing.NotClosed(detailOf(response.statusCode(), response.body()))
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            TenantClosing.NotClosed("closeTenant was interrupted")
        } catch (e: IOException) {
            log.warn("closeTenant did not complete: {}", e.javaClass.simpleName)
            TenantClosing.NotClosed("closeTenant did not complete: ${e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            log.warn("closeTenant failed: {}", e.javaClass.simpleName)
            TenantClosing.NotClosed("closeTenant failed: ${e.javaClass.simpleName}")
        }

    private fun urlOf(tenantRef: CommercialRef): String {
        val tenant = URLEncoder.encode(tenantRef.value, StandardCharsets.UTF_8)
        return baseUrl.toString().trimEnd('/') + "/internal/v1/tenancy/tenants/$tenant/close"
    }

    private fun keyOf(scheduleRef: String) = UUID.nameUUIDFromBytes("closeTenant:$scheduleRef".toByteArray()).toString()

    private fun bodyOf(schedule: TenantSchedule): String {
        val holder = schedule.custodyHolder
        val custody =
            linkedMapOf<String, Any>("name" to holder.name, "contactEmail" to holder.contactEmail)
        holder.contactMobile?.let { custody["contactMobile"] = it }
        val body = linkedMapOf<String, Any>("scheduleRef" to schedule.scheduleRef, "retentionCustodyHolder" to custody)
        schedule.noticeWindowDays?.let { body["noticeWindowDays"] = it }
        return json.writeValueAsString(body)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun detailOf(
        status: Int,
        body: String,
    ): String {
        val detail =
            try {
                json.readTree(body).path("detail").asString("")
            } catch (e: RuntimeException) {
                log.debug("closeTenant answer is not a problem document: {}", e.javaClass.simpleName)
                ""
            }
        return detail.ifBlank { "closeTenant answered $status" }.take(MAX_DETAIL)
    }

    companion object {
        /** The `x-timeout` of `closeTenant` in the pinned tenancy contract. */
        val TIMEOUT: Duration = Duration.ofMillis(2000)
        private const val ACCEPTED = 202
        private const val MAX_DETAIL = 500
    }
}
