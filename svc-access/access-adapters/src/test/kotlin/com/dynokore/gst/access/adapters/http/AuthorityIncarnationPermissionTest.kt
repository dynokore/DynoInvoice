package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The caller check of `bumpAuthorityIncarnation` (INV-AC-22070, INV-Q-10142 A, INV-Q-10027): the key
 * `authority-incarnation.bump`, decided by `AuthorityReplica.decide`, never by a role name. The replica is fed the
 * permission sets of the catalogue: the key held by one role per form (SAAS and PRIVATE).
 */
class AuthorityIncarnationPermissionTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-15T01:00:00Z"), ZoneOffset.UTC)
    private val key = AUTHORITY_INCARNATION_BUMP
    private val keyHolderSaas = Role.PLATFORM_ENGINEER.wire
    private val keyHolderPrivate = Role.DEPLOYMENT_OPERATOR.wire

    private fun matrix(vararg keys: String) =
        PermissionMatrix(
            keys.map { PermissionDefinition(Permission(it), PermissionKind.WRITE) },
            mapOf(Role.PLATFORM_ENGINEER to keys.associate { Permission(it) to Allowance.PERMITTED }),
        )

    private val catalogue = matrix(key, "backup.manage", "tenant.admin", "release.manager")

    /** The permission sets of one deployment form, by key. */
    private fun operations(form: String) =
        mapOf(
            key to mapOf((if (form == "SAAS") keyHolderSaas else keyHolderPrivate) to CellAllowance.PERMITTED),
            "backup.manage" to mapOf("backup.operator" to CellAllowance.PERMITTED),
            "tenant.admin" to mapOf(Role.TENANT_ADMIN.wire to CellAllowance.PERMITTED),
            "release.manager" to mapOf("release.engineer" to CellAllowance.PERMITTED),
        )

    private fun replica(
        form: String,
        vararg held: Pair<String, String>,
    ): AuthorityReplica {
        val map = operations(form)
        val replica = AuthorityReplica(clock, OperationRoleMap { map[it].orEmpty() }, fallback = null)
        held.forEachIndexed { index, (subject, role) ->
            val grant = RoleGrant(role, GrantScope.INSTALLATION, null, Instant.parse("2026-11-01T00:00:00Z"), null)
            val record = AuthorityRecord.RoleGrantRecord("G-$index", RoleGrantState.ACTIVE, grant)
            replica.accept(AuthorityChanged(TenantId.PLATFORM, subject, index + 1L, record))
        }
        replica.onSnapshotComplete(held.size.toLong())
        return replica
    }

    private fun check(
        replica: AuthorityReplica?,
        matrix: PermissionMatrix = catalogue,
        permission: String = key,
    ) = ReplicaBumpPermissionCheck(matrix, { replica }, clock, permission)

    @Test
    fun `INV-AC-22070 platform engineer in SAAS is allowed`() {
        assertThat(check(replica("SAAS", "user:eng" to keyHolderSaas)).mayBump("user:eng")).isTrue()
    }

    @Test
    fun `INV-AC-22070 deployment operator in PRIVATE is allowed`() {
        assertThat(check(replica("PRIVATE", "user:op" to keyHolderPrivate)).mayBump("user:op")).isTrue()
    }

    @Test
    fun `INV-AC-22070 deployment operator in SAAS is refused while the SAAS engineer is allowed`() {
        val replica = replica("SAAS", "user:op" to keyHolderPrivate, "user:eng" to keyHolderSaas)
        assertThat(check(replica).mayBump("user:eng")).isTrue()
        assertThat(check(replica).mayBump("user:op")).isFalse()
    }

    @Test
    fun `INV-AC-22070 platform engineer in PRIVATE is refused while the PRIVATE operator is allowed`() {
        val replica = replica("PRIVATE", "user:eng" to keyHolderSaas, "user:op" to keyHolderPrivate)
        assertThat(check(replica).mayBump("user:op")).isTrue()
        assertThat(check(replica).mayBump("user:eng")).isFalse()
    }

    @Test
    fun `INV-AC-22070 a caller holding only backup-manage is refused while the key holder is allowed`() {
        val replica = replica("SAAS", "user:b" to "backup.operator", "user:eng" to keyHolderSaas)
        assertThat(check(replica).mayBump("user:eng")).isTrue()
        assertThat(check(replica).mayBump("user:b")).isFalse()
    }

    @Test
    fun `INV-AC-22070 a caller holding only tenant-admin is refused while the key holder is allowed`() {
        val replica = replica("SAAS", "user:t" to Role.TENANT_ADMIN.wire, "user:eng" to keyHolderSaas)
        assertThat(check(replica).mayBump("user:eng")).isTrue()
        assertThat(check(replica).mayBump("user:t")).isFalse()
    }

    @Test
    fun `INV-AC-22070 a caller holding only release-manager is refused while the key holder is allowed`() {
        val replica = replica("SAAS", "user:r" to "release.engineer", "user:eng" to keyHolderSaas)
        assertThat(check(replica).mayBump("user:eng")).isTrue()
        assertThat(check(replica).mayBump("user:r")).isFalse()
    }

    @Test
    fun `INV-AC-22070 no replica, an unknown caller and a throwing replica are refused`() {
        assertThat(check(null).mayBump("user:eng")).isFalse()
        assertThat(check(replica("SAAS", "user:eng" to keyHolderSaas)).mayBump("user:nobody")).isFalse()
        val throwing = ReplicaBumpPermissionCheck(catalogue, { error("replica unavailable") }, clock, key)
        assertThat(throwing.mayBump("user:eng")).isFalse()
        // a lag beyond the bound with no fallback never allows
        val lagging = AuthorityReplica(clock, OperationRoleMap { operations("SAAS")[it].orEmpty() }, fallback = null)
        assertThat(check(lagging).mayBump("user:eng")).isFalse()
    }

    @Test
    fun `INV-AC-22070 the key with a trailing space is not in the catalogue and start-up fails`() {
        val lookAlike = "$key "
        assertThrows<IllegalStateException> { check(null, matrix(lookAlike, "backup.manage"), key) }
        // control: the exact key in the catalogue starts
        check(null, matrix(key), key)
    }

    @Test
    fun `INV-AC-22070 the key bumps is not in the catalogue and start-up fails`() {
        assertThrows<IllegalStateException> { check(null, matrix("${key}s"), key) }
        assertThrows<IllegalStateException> { check(null, matrix("backup.manage"), key) }
    }
}
