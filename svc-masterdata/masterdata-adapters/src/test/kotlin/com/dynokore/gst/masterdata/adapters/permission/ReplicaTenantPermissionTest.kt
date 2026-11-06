package com.dynokore.gst.masterdata.adapters.permission

import com.dynokore.gst.masterdata.application.permission.MasterdataPermissions
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-Q-17215 A against a real [AuthorityReplica] fed grants as the svc-tenancy tests feed it. */
class ReplicaTenantPermissionTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val now = Instant.parse("2026-11-06T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val form = DeploymentForm.SAAS
    private val roleMap =
        OperationRoleMap { key -> OperationRoles.rolesFor(form, key).mapValues { CellAllowance.valueOf(it.value.name) } }
    private val replica = AuthorityReplica(clock, roleMap).also { it.onSnapshotComplete(0) }
    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor(): String? = "test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val permission = ReplicaTenantPermission(replica, tenants)
    private var version = 0L

    /** A role the generated permission matrix lists for [key] (read from the catalogue, never written here). */
    private fun roleOf(key: String): String = OperationRoles.rolesFor(form, key).keys.sorted().first()

    private fun holdRole(
        person: String,
        role: String,
        scope: GrantScope = GrantScope.TENANT,
        validUntil: Instant? = null,
    ) {
        replica.accept(
            AuthorityChanged(
                tenant,
                person,
                ++version,
                AuthorityRecord.RoleGrantRecord(
                    "g$version",
                    RoleGrantState.ACTIVE,
                    RoleGrant(role, scope, "S", now.minusSeconds(60), validUntil),
                ),
            ),
        )
        replica.observeHead(version)
    }

    private fun grant(
        person: String,
        key: String,
        scope: GrantScope = GrantScope.TENANT,
        validUntil: Instant? = null,
    ) = holdRole(person, roleOf(key), scope, validUntil)

    @Test
    fun `a tenant-scope grant of the key is served and of another key or entity scope is refused`() {
        grant("identity:P-VIEW", MasterdataPermissions.GAP_REPORT_VIEW)
        grant("identity:P-ENT", MasterdataPermissions.GAP_REPORT_VIEW, GrantScope.ENTITY)
        grant("identity:P-EXP", MasterdataPermissions.GAP_REPORT_EXPORT)
        assertTrue(permission.holds("identity:P-VIEW", MasterdataPermissions.GAP_REPORT_VIEW))
        assertFalse(permission.holds("identity:P-VIEW", MasterdataPermissions.GAP_REPORT_EXPORT))
        assertFalse(permission.holds("identity:P-ENT", MasterdataPermissions.GAP_REPORT_VIEW))
        assertFalse(permission.holds("identity:P-EXP", MasterdataPermissions.GAP_REPORT_VIEW))
        assertFalse(permission.holds("identity:NOBODY", MasterdataPermissions.GAP_REPORT_VIEW))
    }

    @Test
    fun `an expired grant, a role name and a look-alike key are refused`() {
        grant("identity:P-OLD", MasterdataPermissions.COUNTERPARTY_IMPORT_RUN, validUntil = now.minusSeconds(1))
        holdRole("identity:P-ROLE", "a-role-the-matrix-does-not-list")
        grant("identity:P-LIKE", MasterdataPermissions.COUNTERPARTY_IMPORT_RUN + "s")
        assertFalse(permission.holds("identity:P-OLD", MasterdataPermissions.COUNTERPARTY_IMPORT_RUN))
        assertFalse(permission.holds("identity:P-ROLE", MasterdataPermissions.COUNTERPARTY_IMPORT_RUN))
        assertFalse(permission.holds("identity:P-LIKE", MasterdataPermissions.COUNTERPARTY_IMPORT_RUN))
    }

    @Test
    fun `no replica bound refuses the person`() {
        assertFalse(
            ReplicaTenantPermission(null, tenants)
                .holds("identity:P-VIEW", MasterdataPermissions.MASTERDATA_EXCEPTION_RESOLVE),
        )
    }
}
