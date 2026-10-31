package com.dynokore.gst.tenancy.application.workspace

import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.domain.CredentialRefusal
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class LoadCredentialTest {
    private val credentials =
        listOf(
            AccessPointCredential("sandbox-looking", "PRODUCTION"),
            AccessPointCredential("prod-looking", "SANDBOX"),
            AccessPointCredential("plain", null),
        )

    private fun run(
        service: Environment,
        row: Environment,
        accepted: Environment,
    ) {
        val id = UUID.randomUUID()
        val context = InMemoryTenantContext(TenantId(id))
        val store = InMemoryWorkspaceStore(context, TenantRecord(id, "t", "T", "plan", "c", environment = row))
        val binder = CountingBinder()
        val load = LoadCredential(DirectTransactions, store, ServiceEnvironment(service), binder)
        val target =
            if (row == Environment.SANDBOX ||
                service == Environment.SANDBOX
            ) {
                Environment.SANDBOX
            } else {
                Environment.PRODUCTION
            }
        assertEquals(accepted, target)
        credentials.forEach { c ->
            val outcome = load.handle(c)
            if (c.environmentTag == accepted.name) {
                assertEquals(CredentialLoad.Accepted(accepted), outcome, c.name)
            } else {
                val reason = if (c.environmentTag == null) CredentialRefusal.UNTAGGED else CredentialRefusal.TAG_DIFFERS
                assertEquals(CredentialLoad.Refused(reason), outcome, c.name)
            }
        }
        assertEquals(1, binder.bound.size, "an accepted credential is bound once, a refused one never")
        assertEquals(
            accepted.name,
            binder.bound
                .single()
                .second.environmentTag,
        )
    }

    @Test
    fun `production service and production tenant accept only a PRODUCTION credential`() =
        run(Environment.PRODUCTION, Environment.PRODUCTION, Environment.PRODUCTION)

    @Test
    fun `production service and sandbox tenant accept only a SANDBOX credential`() =
        run(Environment.PRODUCTION, Environment.SANDBOX, Environment.SANDBOX)

    @Test
    fun `sandbox service and production tenant row accept only a SANDBOX credential`() =
        run(Environment.SANDBOX, Environment.PRODUCTION, Environment.SANDBOX)

    @Test
    fun `sandbox service and sandbox tenant accept only a SANDBOX credential`() =
        run(Environment.SANDBOX, Environment.SANDBOX, Environment.SANDBOX)
}
