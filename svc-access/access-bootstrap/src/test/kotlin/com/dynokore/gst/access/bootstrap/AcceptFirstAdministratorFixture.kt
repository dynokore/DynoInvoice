package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.CONTROL_PLANE
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.FIRST
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.operation
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.provisionBody
import com.dynokore.gst.access.bootstrap.served.Doubles
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.gst.access.bootstrap.served.workloadToken
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.util.UUID

/** A provisioned first administrator invitation as the control plane's call returned it. */
data class Invitation(
    val tenant: TenantId,
    val grantRef: String,
    val ref: String,
    val code: String,
)

/** The helpers of the acceptance tests of INV-AC-8224 (INV-FLOW-227 steps 2 and 3). */
object AcceptFirstAdministratorFixture {
    const val ACCEPT = "acceptFirstAdministratorInvitation"
    private const val PATH = "/internal/v1/access/first-administrator-invitations"

    /** Registers a token for [sub] (tenantless unless [tenant] is given) and returns its Authorization value. */
    fun token(
        sub: String,
        tenant: TenantId? = null,
    ): String {
        val value = "Bearer token-${UUID.randomUUID()}"
        Doubles.principals[value] = EndUserPrincipal(sub, tenant, emptySet())
        return value
    }

    /** Step 2: the control plane provisions the first administrator; the invitation expires at [expires]. */
    fun ServedContext.provisioned(
        tenant: TenantId = FirstAdministratorFixture.newTenant(),
        expires: String = FirstAdministratorFixture.EXPIRES,
    ): Invitation {
        val response =
            provisionBody(FIRST, CONTROL_PLANE, FirstAdministratorFixture.body(tenant.toString(), expires = expires))
        check(response.status == 201) { "provisioning answered ${response.status}" }
        val body = json(response)
        return Invitation(
            tenant,
            body["grantRef"].asString(),
            body["invitationRef"].asString(),
            body["invitationCode"].asString(),
        )
    }

    /** Step 3: one call of the operation with [auth] (null: no token). */
    fun ServedContext.accept(
        ref: String,
        code: String,
        auth: String?,
        key: UUID = UUID.randomUUID(),
    ): MockHttpServletResponse =
        post(
            operation(ACCEPT),
            viaConsole(auth),
            """{"invitationCode":"$code"}""",
            key,
            "$PATH/$ref/accept",
        )

    /** The headers of a call that console-bff forwards with the end-user [auth] (edge console-bff->access-svc). */
    fun ServedContext.viaConsole(auth: String?): Map<String, String> {
        val caller = FirstAdministratorFixture.CONSOLE_BFF
        (application.getBean(ServiceCaller::class.java) as? MovableServiceCaller)?.value = caller
        val headers = mutableMapOf("X-Workload-Token" to workloadToken(caller))
        auth?.let { headers["Authorization"] = it }
        return headers
    }

    fun ServedContext.movableClock(): MovableClock = application.getBean(Clock::class.java) as MovableClock
}
