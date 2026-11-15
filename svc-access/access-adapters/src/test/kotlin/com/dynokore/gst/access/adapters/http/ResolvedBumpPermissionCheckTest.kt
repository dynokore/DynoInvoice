package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito

/**
 * The caller check of `bumpAuthorityIncarnation` (INV-AC-22070, INV-Q-10142 A, INV-Q-10027): the key
 * `authority-incarnation.bump`, decided from the configured installation roles and the matrix of the deployed form,
 * never by a role name, with no replica.
 */
class ResolvedBumpPermissionCheckTest {
    private val key = Permission(AUTHORITY_INCARNATION_BUMP)
    private val others = listOf("backup.manage", "tenant.admin", "release.manager").map(::Permission)

    private fun matrix(rows: Map<Role, Set<Permission>>) =
        PermissionMatrix(
            (listOf(key) + others).map { PermissionDefinition(it, PermissionKind.WRITE) },
            rows.mapValues { (_, keys) -> keys.associateWith { Allowance.PERMITTED } },
        )

    private val saas = matrix(mapOf(Role.PLATFORM_ENGINEER to setOf(key, others[0])))
    private val private = matrix(mapOf(Role.DEPLOYMENT_OPERATOR to setOf(key, others[0])))

    private fun check(
        matrix: PermissionMatrix,
        configured: Map<String, Role>,
    ) = ResolvedBumpPermissionCheck(
        InstallationRoleDirectory(configured.map { (subject, role) -> InstallationRole(subject, role) }),
        matrix,
    )

    @Test
    fun `INV-AC-22070 platform engineer is allowed in SAAS and deployment operator in PRIVATE`() {
        assertThat(check(saas, mapOf("user:a" to Role.PLATFORM_ENGINEER)).mayBump("user:a")).isTrue()
        assertThat(check(private, mapOf("user:a" to Role.DEPLOYMENT_OPERATOR)).mayBump("user:a")).isTrue()
    }

    @Test
    fun `INV-AC-22070 deployment operator in SAAS and platform engineer in PRIVATE are refused`() {
        assertThat(check(saas, mapOf("user:a" to Role.DEPLOYMENT_OPERATOR)).mayBump("user:a")).isFalse()
        assertThat(check(private, mapOf("user:a" to Role.PLATFORM_ENGINEER)).mayBump("user:a")).isFalse()
        // control: the matching role is allowed
        assertThat(check(saas, mapOf("user:a" to Role.PLATFORM_ENGINEER)).mayBump("user:a")).isTrue()
    }

    @Test
    fun `INV-AC-22070 a caller holding only a look-alike key or another role is refused`() {
        val onlyBackup = matrix(mapOf(Role.PLATFORM_ENGINEER to setOf(others[0])))
        assertThat(check(onlyBackup, mapOf("user:a" to Role.PLATFORM_ENGINEER)).mayBump("user:a")).isFalse()
        // tenant.admin and release.manager cannot be configured as installation roles at all (refused at start)
        listOf("tenant.admin", "release.manager").forEach { name ->
            assertThrows<IllegalArgumentException>(name) { InstallationRole.of("user:a", name) }
        }
    }

    @Test
    fun `INV-AC-22070 a mistyped key is not in the catalogue and construction fails`() {
        listOf("authority-incarnation.bump ", "authority-incarnation.bumps").forEach { typo ->
            assertThrows<IllegalStateException> {
                ResolvedBumpPermissionCheck(InstallationRoleDirectory(emptyList()), saas, typo)
            }
        }
        // control: the real key constructs
        ResolvedBumpPermissionCheck(InstallationRoleDirectory(emptyList()), saas)
    }

    @Test
    fun `INV-AC-22070 an actor with no entry, a case or space variant, or a blank subject is refused`() {
        val check = check(saas, mapOf("user:a" to Role.PLATFORM_ENGINEER))
        assertThat(check.mayBump("user:a")).isTrue()
        listOf("user:b", "User:a", "user:a ", " user:a", "", " ").forEach { assertThat(check.mayBump(it)).isFalse() }
    }

    @Test
    fun `INV-AC-22070 a matrix that grants the key to a role the catalogue does not name admits it`() {
        // here the key is held by deployment.operator in a matrix that stands for SAAS: the verdict follows the matrix
        val swapped = matrix(mapOf(Role.DEPLOYMENT_OPERATOR to setOf(key)))
        assertThat(check(swapped, mapOf("user:a" to Role.DEPLOYMENT_OPERATOR)).mayBump("user:a")).isTrue()
        assertThat(check(swapped, mapOf("user:a" to Role.PLATFORM_ENGINEER)).mayBump("user:a")).isFalse()
    }

    @Test
    fun `INV-AC-22070 a matrix read that throws is refused`() {
        val throwing = Mockito.mock(PermissionMatrix::class.java)
        Mockito.`when`(throwing.kindOf(key)).thenReturn(PermissionKind.WRITE)
        Mockito.`when`(throwing.allowance(Role.PLATFORM_ENGINEER, key)).thenThrow(IllegalStateException("boom"))
        val roles = InstallationRoleDirectory(listOf(InstallationRole("user:a", Role.PLATFORM_ENGINEER)))
        assertThat(ResolvedBumpPermissionCheck(roles, throwing).mayBump("user:a")).isFalse()
        // control: the same roles over a healthy matrix admit
        assertThat(ResolvedBumpPermissionCheck(roles, saas).mayBump("user:a")).isTrue()
    }
}
