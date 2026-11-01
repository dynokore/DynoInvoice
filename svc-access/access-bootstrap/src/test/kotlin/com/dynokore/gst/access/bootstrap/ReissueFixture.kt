package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.movableClock
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.provisioned
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.CONTROL_PLANE
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.operation
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.rows
import com.dynokore.gst.access.bootstrap.served.OPERATOR_TOKEN
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.gst.access.bootstrap.served.workloadToken
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal const val NEW_EXPIRY = "2031-02-03T04:05:06Z"
internal const val ACTION = "reissueFirstAdministratorInvitation"

// The helpers of the tests of INV-AC-8281 (INV-T-8282).

internal fun ServedContext.reissue(
    tenant: TenantId,
    caller: String? = CONTROL_PLANE,
    auth: String? = OPERATOR_TOKEN,
    expires: String = NEW_EXPIRY,
    key: UUID = UUID.randomUUID(),
): MockHttpServletResponse {
    val headers = mutableMapOf<String, String>()
    (application.getBean(ServiceCaller::class.java) as? MovableServiceCaller)?.value = caller
    caller?.let { headers["X-Workload-Token"] = workloadToken(it) }
    auth?.let { headers["Authorization"] = it }
    val op = operation(ACTION)
    return post(
        op,
        headers,
        """{"invitationExpiresAt":"$expires"}""",
        key,
        op.path.replace("{tenantRef}", "$tenant"),
    )
}

/** A provisioned invitation whose stored expiry is [secondsAhead] after the frozen clock. */
internal fun ServedContext.provisionedExpiring(
    secondsAhead: Long,
    tenant: TenantId = newTenant(),
): Pair<Invitation, Instant> {
    val expiry = movableClock().freeze().plusSeconds(secondsAhead)
    return provisioned(tenant, expires = expiry.toString()) to expiry
}

internal fun ServedContext.expired(tenant: TenantId = newTenant()): Invitation {
    val (invitation, expiry) = provisionedExpiring(10, tenant)
    movableClock().advance(Duration.ofSeconds(11))
    assertThat(movableClock().instant()).isAfter(expiry)
    return invitation
}

internal fun invitationRows(tenant: TenantId) = rows("access.first_administrator_invitation", tenant).map(::json)

internal fun auditEntries(tenant: TenantId): List<String> =
    BoundaryFixture.asSuperuser { c ->
        c
            .prepareStatement(
                "select data::text from access.outbox where tenant_id = ?::uuid " +
                    "and type like '%AuditEntryRecorded%' and data::text like '%$ACTION%'",
            ).use {
                it.setString(1, tenant.toString())
                it.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
    }
