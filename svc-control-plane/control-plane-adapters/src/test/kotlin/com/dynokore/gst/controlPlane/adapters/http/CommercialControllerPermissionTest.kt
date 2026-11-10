package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.application.commercial.CommercialPermission
import com.dynokore.gst.controlPlane.application.commercial.GetLicence
import com.dynokore.gst.controlPlane.application.commercial.GetTenantProvisioningRequest
import com.dynokore.gst.controlPlane.application.commercial.InvitationDelivery
import com.dynokore.gst.controlPlane.application.commercial.IssueLicence
import com.dynokore.gst.controlPlane.application.commercial.LicenceRepository
import com.dynokore.gst.controlPlane.application.commercial.ManagePlan
import com.dynokore.gst.controlPlane.application.commercial.ProvisionTenant
import com.dynokore.gst.controlPlane.application.commercial.ProvisioningRecord
import com.dynokore.gst.controlPlane.application.commercial.ProvisioningRepository
import com.dynokore.gst.controlPlane.application.commercial.RecordQualification
import com.dynokore.gst.controlPlane.application.commercial.ReissueFirstAdministratorInvitation
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.schedule.ApproveTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.CancelTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.ChangeTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.GetTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.SetTenantSchedule
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Licence
import com.dynokore.gst.controlPlane.domain.commercial.PlanRef
import com.dynokore.gst.controlPlane.domain.commercial.ProvisioningStatus
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.gst.controlPlane.domain.commercial.TenantProvisioning
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.lang.reflect.Proxy
import java.time.Instant

/** INV-AC-17761 (the test names carry the criterion key and the exact case text): the two reads decide by the operation's own READ key through [OperatorPermissions], never a role. */
@Suppress("MaxLineLength", "ktlint:standard:max-line-length")
class CommercialControllerPermissionTest {
    private val actor = "op-1"
    private val licenceKey = CommercialPermission.LICENCE_VIEW
    private val provisioningKey = CommercialPermission.TENANT_PROVISIONING_REQUEST_VIEW
    private val asked = mutableListOf<Pair<String, String>>()
    private var licenceLookups = 0
    private var provisioningLookups = 0

    private val licence =
        Licence(CommercialRef("LIC-1"), CommercialRef("INST-1"), "2026", Instant.EPOCH, byteArrayOf(1))
    private val provisioning =
        TenantProvisioning(
            CommercialRef("CON-1"),
            CommercialRef("TEN-1"),
            "Acme",
            PlanRef("PLAN-1"),
            "a@b.sg",
            RegionKey("sg"),
            ProvisioningStatus.CREATED,
            null,
            Instant.EPOCH,
        )

    private companion object {
        // the catalogue codes of ClosedErrorCode.ACCESS_DENIED, NOT_FOUND and UNAUTHENTICATED
        val CODES = mapOf("SRT-004" to "ACCESS_DENIED", "SRT-005" to "NOT_FOUND", "SRT-003" to "UNAUTHENTICATED")
    }

    @AfterEach
    fun clear() = RequestContextHolder.resetRequestAttributes()

    private inline fun <reified T : Any> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T

    private fun licences() =
        object : LicenceRepository by stub() {
            override fun findByRef(licenceRef: CommercialRef): Licence? {
                licenceLookups++
                return licence.takeIf { it.licenceRef == licenceRef }
            }
        }

    private fun provisionings() =
        object : ProvisioningRepository by stub() {
            override fun findByContract(contractReference: CommercialRef): ProvisioningRecord? {
                provisioningLookups++
                return ProvisioningRecord(provisioning, java.util.UUID.randomUUID(), "f")
                    .takeIf { provisioning.contractReference == contractReference }
            }
        }

    private fun controller(held: Set<String>): CommercialController {
        val permissions =
            OperatorPermissions { who, key ->
                asked += who to key
                key in held
            }
        val delivery =
            InvitationDelivery(stub(), stub(), stub(), java.time.Clock.systemUTC(), java.time.Duration.ofDays(1))
        return CommercialController(
            EndUserPrincipalResolver { header ->
                if (header ==
                    "Bearer ok"
                ) {
                    EndUserPrincipal(actor, null, emptySet())
                } else {
                    null
                }
            },
            object : PlatformTenantBinding {
                override fun <T> bindPlatform(
                    actor: String,
                    block: () -> T,
                ): T = block()
            },
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            stub<OperationRunner>(),
            permissions,
            ManagePlan(stub(), stub<OperatorRoles>()),
            RecordQualification(stub(), stub<OperatorRoles>()),
            ProvisionTenant(stub(), stub(), stub(), delivery, stub<OperatorRoles>()) { CommercialRef("x") },
            GetTenantProvisioningRequest(provisionings()),
            IssueLicence(
                stub(),
                stub(),
                stub(),
                stub<OperatorRoles>(),
                java.time.Clock.systemUTC(),
            ) { CommercialRef("x") },
            GetLicence(licences()),
            ReissueFirstAdministratorInvitation(stub(), stub(), delivery, permissions),
            scheduleEndpoint(),
        )
    }

    /** The schedule operations are not under test here; the endpoint is composed from stubs. */
    private fun scheduleEndpoint(): TenantScheduleEndpoint {
        val clock = java.time.Clock.systemUTC()
        return TenantScheduleEndpoint(
            stub(),
            stub(),
            stub(),
            stub(),
            SetTenantSchedule(stub(), stub(), clock) { "SCH-x" },
            ApproveTenantSchedule(stub(), stub(), clock),
            ChangeTenantSchedule(stub(), stub(), clock),
            CancelTenantSchedule(stub(), stub()),
            GetTenantSchedule(stub(), stub()),
        )
    }

    private fun request(authorization: String? = "Bearer ok") {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                if (method.name == "getHeader" && args?.get(0) == "Authorization") authorization else null
            } as HttpServletRequest
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    private fun statusOf(call: () -> Any): String =
        try {
            call()
            "200"
        } catch (e: ProblemException) {
            "${e.problem.status} " + CODES.entries.single { "\"code\":\"${it.key}\"" in e.problem.body }.value
        }

    private fun licenceCall(
        held: Set<String>,
        ref: String = "LIC-1",
    ): String {
        request()
        val c = controller(held)
        return statusOf { c.getLicence(ref) }
    }

    private fun provisioningCall(
        held: Set<String>,
        ref: String = "CON-1",
    ): String {
        request()
        val c = controller(held)
        return statusOf { c.getTenantProvisioningRequest(ref) }
    }

    @Test
    fun `INV-AC-17761 getLicence answers 200 when the view key is held`() {
        licenceCall(setOf(licenceKey)) shouldBe "200"
    }

    @Test
    fun `INV-AC-17761 getLicence answers 404 NOT_FOUND for an unknown reference when the view key is held`() {
        licenceCall(setOf(licenceKey), "LIC-9") shouldBe "404 NOT_FOUND"
    }

    @Test
    fun `INV-AC-17761 getLicence answers 403 ACCESS_DENIED and calls no use case when the view key is not held`() {
        licenceCall(emptySet()) shouldBe "403 ACCESS_DENIED"
        licenceLookups shouldBe 0
    }

    @Test
    fun `INV-AC-17761 getLicence is not opened by the view key of getTenantProvisioningRequest`() {
        licenceCall(setOf(provisioningKey, "licence.manage")) shouldBe "403 ACCESS_DENIED"
        licenceLookups shouldBe 0
    }

    @Test
    fun `INV-AC-17761 getTenantProvisioningRequest answers 200 when the view key is held`() {
        provisioningCall(setOf(provisioningKey)) shouldBe "200"
    }

    @Test
    fun `INV-AC-17761 getTenantProvisioningRequest answers 404 NOT_FOUND for an unknown reference when the view key is held`() {
        provisioningCall(setOf(provisioningKey), "CON-9") shouldBe "404 NOT_FOUND"
    }

    @Test
    fun `INV-AC-17761 getTenantProvisioningRequest answers 403 ACCESS_DENIED and calls no use case when the view key is not held`() {
        provisioningCall(emptySet()) shouldBe "403 ACCESS_DENIED"
        provisioningLookups shouldBe 0
    }

    @Test
    fun `INV-AC-17761 getTenantProvisioningRequest is not opened by the view key of getLicence`() {
        provisioningCall(setOf(licenceKey, "plan.manage")) shouldBe "403 ACCESS_DENIED"
        provisioningLookups shouldBe 0
    }

    @Test
    fun `INV-AC-17761 the port is asked with the token subject and the exact key of each read`() {
        licenceCall(setOf(licenceKey))
        provisioningCall(setOf(provisioningKey))
        asked shouldBe listOf(actor to "licence.view", actor to "tenant-provisioning-request.view")
    }

    @Test
    fun `INV-AC-17761 an unauthenticated caller keeps the UNAUTHENTICATED answer of both reads`() {
        request(null)
        val c = controller(setOf(licenceKey, provisioningKey))
        statusOf { c.getLicence("LIC-1") } shouldBe "401 UNAUTHENTICATED"
        statusOf { c.getTenantProvisioningRequest("CON-1") } shouldBe "401 UNAUTHENTICATED"
        asked shouldBe emptyList()
        licenceLookups + provisioningLookups shouldBe 0
        shouldThrow<ProblemException> { c.getLicence("LIC-1") }
    }
}
