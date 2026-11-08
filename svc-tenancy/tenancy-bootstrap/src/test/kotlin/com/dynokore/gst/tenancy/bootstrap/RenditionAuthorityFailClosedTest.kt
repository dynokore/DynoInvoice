package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.ReplicaActionAuthority
import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.EntitiesController
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.contract.model.RenditionResponsibilityRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.util.UUID

private const val CALLER = "identity:USR-R1"

/**
 * recordRenditionResponsibility over the production binding of the authority port (INV-T-16411): with no replica
 * bound, with a replica that has not completed its snapshot (DEGRADED) or with a port that throws, the caller is
 * refused 403 and the use case never runs (the use case here is an unconstructed instance, so a call would fail
 * with a different error).
 */
class RenditionAuthorityFailClosedTest {
    private val tenants =
        object : TenantContext {
            override fun current() = TestPeople.TENANT

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private inline fun <reified T : Any> unused(): T {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return T::class.java.cast((field.get(null) as sun.misc.Unsafe).allocateInstance(T::class.java))
    }

    private fun refused(authority: ActionAuthority): ClosedErrorCode {
        val controller =
            EntitiesController(
                unused(),
                unused(),
                unused(),
                unused(),
                unused(),
                tenants,
                unused(),
                unused(),
                unused(),
                unused(),
                authority,
                CallerSource { CALLER },
            )
        return assertThrows<ApiRefusal> {
            controller.recordRenditionResponsibility(
                "E1",
                UUID.randomUUID(),
                RenditionResponsibilityRequest("TAX_INVOICE", RenditionResponsibilityRequest.Renderer.SELF, CALLER),
            )
        }.code
    }

    @Test
    fun `probe an absent replica is 403 and the use case does not run`() {
        assertThat(refused(ReplicaActionAuthority(null, tenants))).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
    }

    @Test
    fun `probe a replica answering DEGRADED is 403 and the use case does not run`() {
        val degraded = AuthorityReplica(Clock.systemUTC(), TestOperationRoles.map)
        assertThat(refused(ReplicaActionAuthority(degraded, tenants))).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
    }

    @Test
    fun `probe an authority that throws is 403 and the use case does not run`() {
        assertThat(
            refused(
                ActionAuthority {
                    _,
                    _,
                    _,
                    ->
                    error("replica down")
                },
            ),
        ).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
    }
}
