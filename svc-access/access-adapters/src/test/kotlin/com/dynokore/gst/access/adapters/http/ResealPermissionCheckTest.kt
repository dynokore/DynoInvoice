package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** The caller check of `resealBreakGlass` decides by `break-glass.reseal`, never by a role name (INV-Q-16100 A). */
class ResealPermissionCheckTest {
    private val reseal = Permission("break-glass.reseal")
    private val unlock = Permission("break-glass.unlock")

    private fun matrix(rows: Map<Role, Map<Permission, Allowance>>) =
        PermissionMatrix(
            listOf(
                PermissionDefinition(reseal, PermissionKind.WRITE),
                PermissionDefinition(unlock, PermissionKind.WRITE),
            ),
            rows,
        )

    private fun check(
        installationRoles: Set<String>,
        matrix: PermissionMatrix,
        actor: String? = "user:caller",
    ): Boolean {
        val tenants =
            object : TenantContext {
                override fun current() = TenantId(UUID.randomUUID())

                override fun currentActor() = actor

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T = block()
            }
        val roles =
            PolicyRoleResolver { _, _, level, _, _ ->
                if (level == ScopeLevel.INSTALLATION) installationRoles else emptySet()
            }
        return ResolvedResealPermissionCheck(tenants, roles, matrix).mayReseal()
    }

    @Test
    fun `a role that holds the permission is admitted whatever its name`() {
        val granted = matrix(mapOf(Role.PLATFORM_ENGINEER to mapOf(reseal to Allowance.PERMITTED)))
        assertThat(check(setOf("platform.engineer"), granted)).isTrue()
    }

    @Test
    fun `deployment operator holding only the unlock key is refused`() {
        val unlockOnly = matrix(mapOf(Role.DEPLOYMENT_OPERATOR to mapOf(unlock to Allowance.PERMITTED)))
        assertThat(check(setOf("deployment.operator"), unlockOnly)).isFalse()
    }

    @Test
    fun `a platform engineer is refused in the private matrix and a look-alike key does not count`() {
        val private =
            matrix(mapOf(Role.DEPLOYMENT_OPERATOR to mapOf(reseal to Allowance.PERMITTED)))
        assertThat(check(setOf("platform.engineer"), private)).isFalse()
        assertThat(check(setOf("deployment.operator"), private)).isTrue()
        listOf("break-glass.reseal ", "break-glass.resealed").forEach { key ->
            val other = Permission(key)
            val lookAlike =
                PermissionMatrix(
                    listOf(PermissionDefinition(other, PermissionKind.WRITE)),
                    mapOf(Role.DEPLOYMENT_OPERATOR to mapOf(other to Allowance.PERMITTED)),
                )
            assertThat(check(setOf("deployment.operator"), lookAlike)).describedAs(key).isFalse()
        }
    }

    @Test
    fun `no role, an unknown role name, no actor and a failing resolver all refuse`() {
        val granted = matrix(mapOf(Role.DEPLOYMENT_OPERATOR to mapOf(reseal to Allowance.PERMITTED)))
        assertThat(check(emptySet(), granted)).isFalse()
        assertThat(check(setOf("Deployment.Operator", ""), granted)).isFalse()
        assertThat(check(setOf("deployment.operator"), granted, actor = null)).isFalse()
    }

    @Test
    fun `neither the check nor the controller names the role`() {
        val dir = Path.of("src/main/kotlin/com/dynokore/gst/access/adapters/http")
        listOf("ResealPermissionCheck.kt", "BreakGlassController.kt").forEach { file ->
            val text = Files.readString(dir.resolve(file))
            assertThat(text)
                .describedAs(file)
                .doesNotContain("deployment.operator")
                .doesNotContain("DEPLOYMENT_OPERATOR")
        }
    }
}
