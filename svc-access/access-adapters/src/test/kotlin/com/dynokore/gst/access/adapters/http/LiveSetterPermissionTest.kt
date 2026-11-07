package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.grant.EntityMembership
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantSource
import com.dynokore.gst.access.domain.grant.GrantState
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GroupRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-10161 and INV-AC-2224: `setPolicy` is allowed only to a holder of the key `vp-NN.set`; `LiveSetterPermission`
 * decides that key from the actor's live grants and the permission matrix, never from a role name.
 */
class LiveSetterPermissionTest {
    private val now = Instant.parse("2026-11-07T00:00:00Z")
    private val tenant = TenantId(UUID.randomUUID())
    private val otherTenant = TenantId(UUID.randomUUID())
    private val tenantRef = TenantRef(tenant.toString())
    private val admin = "vp-64.set"
    private val officer = "vp-44.set"
    private val installationKey = "vp-52.set"

    private fun matrix(withOperatorKey: Boolean): PermissionMatrix {
        val keys = listOf(admin, officer, installationKey).map { Permission(it) }
        val operator = if (withOperatorKey) mapOf(Permission(installationKey) to Allowance.PERMITTED) else emptyMap()
        return PermissionMatrix(
            keys.map { PermissionDefinition(it, PermissionKind.WRITE) },
            mapOf(
                Role.TENANT_ADMIN to mapOf(Permission(admin) to Allowance.PERMITTED),
                Role.SECURITY_OFFICER to mapOf(Permission(officer) to Allowance.PERMITTED),
                Role.ENTITY_ADMIN to mapOf(Permission(admin) to Allowance.WITHIN_SCOPE),
                Role.DEPLOYMENT_OPERATOR to operator,
            ),
        )
    }

    private val membership = EntityMembership(tenantRef, GroupRef("GRP-1"))

    private fun grant(
        role: Role,
        scope: Scope = Scope.Tenant(tenantRef),
        status: GrantStatus = GrantStatus.ACTIVE,
        from: Instant = now.minusSeconds(3600),
        until: Instant? = now.plusSeconds(3600),
    ) = Grant(
        GrantHeader(GrantRef(UUID.randomUUID().toString()), tenantRef, IdentityRef(ACTOR)),
        role,
        scope,
        ValidityWindow(from, until),
        GrantState(status, GrantSource.BY_HAND, 0),
    )

    private fun permission(
        grants: List<Grant>,
        installation: List<InstallationRole> = emptyList(),
        withOperatorKey: Boolean = true,
        failing: Boolean = false,
        known: Set<String> = setOf("ENT-1"),
    ): LiveSetterPermission {
        val tenants =
            object : TenantContext {
                override fun current() = tenant

                override fun currentActor(): String? = ACTOR

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T = block()
            }
        val tx =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        val repository =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(GrantRepository::class.java)) { _, method, _ ->
                check(method.name.startsWith("findByIdentity")) { "unexpected ${method.name}" }
                if (failing) error("database down")
                grants
            } as GrantRepository
        val directory =
            object : EntityDirectory {
                override fun membership(entity: EntityRef) = membership.takeIf { entity.value in known }

                override fun entitiesCoveredBy(scope: Scope) = emptySet<EntityRef>()
            }
        val live = LiveTenantGrants(tenants, tx, repository, Clock.fixed(now, ZoneOffset.UTC))
        return LiveSetterPermission(live, matrix(withOperatorKey), InstallationRoleDirectory(installation), directory)
    }

    private fun LiveSetterPermission.at(
        key: String,
        level: ScopeLevel = ScopeLevel.TENANT,
        scopeRef: String? = tenant.toString(),
        entityRef: String? = null,
    ) = holds(Actor(ACTOR), tenant, level, scopeRef, entityRef, key)

    private fun LiveSetterPermission.holdsFor(
        actor: String,
        inTenant: TenantId,
        key: String,
        level: ScopeLevel = ScopeLevel.TENANT,
        scopeRef: String? = inTenant.toString(),
    ) = holds(Actor(actor), inTenant, level, scopeRef, null, key)

    @Test
    fun `INV-AC-10161 a tenant admin holds vp-64 set and not vp-44 set, a security officer the reverse`() {
        val admins = permission(listOf(grant(Role.TENANT_ADMIN)))
        assertThat(admins.at(admin)).isTrue()
        assertThat(admins.at(officer)).isFalse()
        val officers = permission(listOf(grant(Role.SECURITY_OFFICER)))
        assertThat(officers.at(officer)).isTrue()
        assertThat(officers.at(admin)).isFalse()
    }

    @Test
    fun `INV-AC-10161 two live grants of which only one is a holder hold the key`() {
        assertThat(permission(listOf(grant(Role.SECURITY_OFFICER), grant(Role.TENANT_ADMIN))).at(admin)).isTrue()
        assertThat(permission(listOf(grant(Role.TENANT_ADMIN), grant(Role.SECURITY_OFFICER))).at(admin)).isTrue()
    }

    @Test
    fun `INV-AC-2224 an expired, a revoked and a not yet valid grant hold nothing`() {
        val expired = grant(Role.TENANT_ADMIN, from = now.minusSeconds(7200), until = now.minusSeconds(1))
        val revoked = grant(Role.TENANT_ADMIN, status = GrantStatus.REVOKED)
        val future = grant(Role.TENANT_ADMIN, from = now.plusSeconds(60), until = now.plusSeconds(3600))
        listOf(expired, revoked, future).forEach { assertThat(permission(listOf(it)).at(admin)).isFalse() }
    }

    @Test
    fun `INV-AC-2224 another tenant, an entity grant and the platform tenant hold nothing`() {
        val elsewhere = grant(Role.TENANT_ADMIN, Scope.Tenant(TenantRef("other")))
        assertThat(permission(listOf(elsewhere)).at(admin)).isFalse()
        val entityScoped = grant(Role.TENANT_ADMIN, Scope.Entity(EntityRef("ENT-1")))
        assertThat(permission(listOf(entityScoped)).at(admin)).isFalse()
        val held = permission(listOf(grant(Role.TENANT_ADMIN)))
        assertThat(held.holdsFor(ACTOR, TenantId.PLATFORM, admin)).isFalse()
        assertThat(held.holdsFor(ACTOR, otherTenant, admin, scopeRef = tenant.toString())).isFalse()
        assertThat(held.at(admin, scopeRef = null)).isFalse()
    }

    @Test
    fun `INV-AC-2224 a failed read and an exception are false`() {
        assertThat(permission(listOf(grant(Role.TENANT_ADMIN)), failing = true).at(admin)).isFalse()
        val failing = permission(listOf(grant(Role.TENANT_ADMIN)), failing = true)
        assertThat(failing.at(admin, ScopeLevel.ENTITY, "ENT-1", "ENT-1")).isFalse()
    }

    @Test
    fun `INV-AC-2224 look-alike and blank keys are false for every actor`() {
        val held = permission(listOf(grant(Role.TENANT_ADMIN)))
        listOf("vp-64.set ", "VP-64.set", "vp-64", "", " ", "vp-64.set".repeat(10)).forEach {
            assertThat(held.at(it)).describedAs("[$it]").isFalse()
        }
    }

    @Test
    fun `INV-AC-10161 a configured entry holds the key by exact subject when the matrix lists it`() {
        val entries = listOf(InstallationRole.of("user:op", "deployment.operator"))
        val installation = { p: LiveSetterPermission, actor: String ->
            p.holdsFor(actor, tenant, installationKey, ScopeLevel.INSTALLATION, null)
        }
        assertThat(installation(permission(emptyList(), entries), "user:op")).isTrue()
        assertThat(installation(permission(emptyList(), entries, withOperatorKey = false), "user:op")).isFalse()
        listOf("user:op ", "USER:OP", "user:o", "user:other").forEach {
            assertThat(installation(permission(emptyList(), entries), it)).describedAs("[$it]").isFalse()
        }
        val withRef = permission(emptyList(), entries)
        assertThat(
            withRef.holdsFor("user:op", tenant, installationKey, ScopeLevel.INSTALLATION, tenant.toString()),
        ).isFalse()
    }

    @Test
    fun `INV-AC-10161 at ENTITY and CLIENT_ENTITY the covering grant of a holder role holds the key`() {
        val covering = permission(listOf(grant(Role.ENTITY_ADMIN, Scope.Entity(EntityRef("ENT-1")))))
        assertThat(covering.at(admin, ScopeLevel.ENTITY, "ENT-1", "ENT-1")).isTrue()
        assertThat(covering.at(admin, ScopeLevel.CLIENT_ENTITY, "ENT-1", "ENT-1")).isTrue()
        assertThat(covering.at(admin, ScopeLevel.CLIENT_ENTITY, "ENT-2", "ENT-1")).isFalse()
        assertThat(covering.at(admin, ScopeLevel.ENTITY, "ENT-1", null)).isFalse()
        assertThat(covering.at(officer, ScopeLevel.ENTITY, "ENT-1", "ENT-1")).isFalse()
        val tenantWide = permission(listOf(grant(Role.TENANT_ADMIN)))
        assertThat(tenantWide.at(admin, ScopeLevel.ENTITY, "ENT-1", "ENT-1")).isTrue()
    }

    @Test
    fun `INV-AC-2224 at the entity levels outside, consent-derived, unknown and keyless hold nothing`() {
        val other = grant(Role.ENTITY_ADMIN, Scope.Entity(EntityRef("ENT-9")))
        val outside = permission(listOf(other), known = setOf("ENT-1", "ENT-9"))
        assertThat(outside.at(admin, ScopeLevel.ENTITY, "ENT-1", "ENT-1")).isFalse()
        val consent = grant(Role.TENANT_ADMIN, Scope.ConsentDerived(tenantRef, ConsentCheck { true }))
        assertThat(permission(listOf(consent)).at(admin, ScopeLevel.ENTITY, "ENT-1", "ENT-1")).isFalse()
        val unknown = permission(listOf(grant(Role.TENANT_ADMIN)))
        assertThat(unknown.at(admin, ScopeLevel.ENTITY, "ENT-404", "ENT-404")).isFalse()
        val noKey = permission(listOf(grant(Role.SECURITY_OFFICER, Scope.Entity(EntityRef("ENT-1")))))
        assertThat(noKey.at(admin, ScopeLevel.ENTITY, "ENT-1", "ENT-1")).isFalse()
    }

    @Test
    fun `INV-AC-2224 GROUP and every other level are false for every actor`() {
        val held = permission(listOf(grant(Role.TENANT_ADMIN)))
        val decided = setOf(ScopeLevel.TENANT, ScopeLevel.INSTALLATION, ScopeLevel.ENTITY, ScopeLevel.CLIENT_ENTITY)
        ScopeLevel
            .values()
            .filter { it !in decided }
            .forEach { level ->
                assertThat(held.at(admin, level, tenant.toString(), "ENT-1")).describedAs(level.name).isFalse()
                assertThat(held.at(admin, level, null)).describedAs(level.name).isFalse()
            }
    }

    @Test
    fun `no role name is compared in the new classes`() {
        val dir =
            java.nio.file.Path
                .of("src/main/kotlin/com/dynokore/gst/access/adapters/http")
        listOf("LiveSetterPermission.kt", "InstallationRoleDirectory.kt").forEach { file ->
            val text =
                java.nio.file.Files
                    .readString(dir.resolve(file))
                    .lines()
                    .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
                    .joinToString("\n")
            assertThat(text).describedAs(file).doesNotContain("Role.").doesNotContainPattern("\"[a-z]+\\.[a-z-]+\"")
        }
    }

    private companion object {
        const val ACTOR = "user:caller"
    }
}
