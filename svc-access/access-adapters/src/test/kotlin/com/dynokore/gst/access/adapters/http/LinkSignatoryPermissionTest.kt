package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.link.SignedLinks
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.link.LinkRecipient
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.link.LinkSource
import com.dynokore.gst.access.domain.link.LinkStatus
import com.dynokore.gst.access.domain.link.SignedLink
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
 * INV-AC-2224 (INV-Q-9222 A): a link principal sets VP-04 and VP-10 at CLIENT_ENTITY by `vp-NN.set`, decided from the
 * service's own link store and matrix; every look-alike is false.
 */
class LinkSignatoryPermissionTest {
    private val now = Instant.parse("2026-11-07T00:00:00Z")
    private val tenant = TenantId(UUID.randomUUID())
    private val otherTenant = TenantId(UUID.randomUUID())
    private val matrix =
        PermissionMatrix(
            listOf("vp-04.set", "vp-10.set", "vp-30.set", "vp-45.set").map {
                PermissionDefinition(Permission(it), PermissionKind.WRITE)
            },
            mapOf(
                Role.CLIENT_SIGNATORY to
                    mapOf(
                        Permission("vp-10.set") to Allowance.WITHIN_SCOPE,
                        Permission("vp-04.set") to Allowance.PERMITTED,
                    ),
                Role.TENANT_ADMIN to mapOf(Permission("vp-30.set") to Allowance.PERMITTED),
                Role.SECURITY_OFFICER to mapOf(Permission("vp-45.set") to Allowance.PERMITTED),
            ),
        )

    private fun link(
        ref: String = "LNK-771A",
        client: String? = "CLI-2201",
        status: LinkStatus = LinkStatus.ACTIVE,
        expiresAt: Instant = now.plusSeconds(60),
    ) = SignedLink(
        LinkRef(ref),
        "CONFIRM_FILING",
        client?.let { EntityRef(it) },
        client?.let { ConsentRef("CNS-1") },
        EntityRef(client ?: "CLI-2201"),
        null,
        LinkRecipient("s@client.example", null),
        expiresAt,
        "digest",
        status,
        0,
        null,
        null,
        if (client == null) LinkSource.EVENT else LinkSource.CALL,
        if (client == null) "evt-1" else null,
        0,
    )

    private fun permission(
        stored: SignedLink?,
        failing: Boolean = false,
        at: Instant = now,
        storedIn: TenantId = tenant,
    ): LiveSetterPermission {
        var bound: TenantId? = null
        val tenants =
            object : TenantContext {
                override fun current() = checkNotNull(bound)

                override fun currentActor(): String? = null

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T {
                    bound = tenant
                    return block()
                }
            }
        val tx =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        val links =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SignedLinks::class.java)) { _, method, args ->
                check(method.name.startsWith("find-")) { "unexpected ${method.name}" }
                if (failing) error("database down")
                // the database policy restricts the rows to the bound tenant
                stored?.takeIf { bound == storedIn && it.ref.value == args[0] }
            } as SignedLinks
        val inner = LinkSignatoryPermission(tenants, tx, links, matrix, Clock.fixed(at, ZoneOffset.UTC))
        val live = LiveTenantGrants(tenants, tx, noGrants(), Clock.fixed(at, ZoneOffset.UTC))
        return LiveSetterPermission(live, matrix, InstallationRoleDirectory(emptyList()), null, inner)
    }

    private fun noGrants() =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.dynokore.gst.access.application.grant.GrantRepository::class.java),
        ) { _, _, _ -> emptyList<Any>() } as com.dynokore.gst.access.application.grant.GrantRepository

    private fun LiveSetterPermission.at(
        key: String = "vp-10.set",
        actor: String = "link:LNK-771A",
        inTenant: TenantId = tenant,
        level: ScopeLevel = ScopeLevel.CLIENT_ENTITY,
        scopeRef: String? = "CLI-2201",
        entityRef: String? = "CLI-2201",
    ) = holds(Actor(actor), inTenant, level, scopeRef, entityRef, key)

    @Test
    fun `INV-AC-2224 a live signatory link holds vp-10 set and vp-04 set at its client entity only`() {
        val p = permission(link())
        assertThat(p.at("vp-10.set")).isTrue()
        assertThat(p.at("vp-04.set")).isTrue()
        assertThat(p.at("vp-30.set")).isFalse()
        assertThat(p.at("vp-45.set")).isFalse()
    }

    @Test
    fun `INV-AC-2224 look-alike actors are false`() {
        val p = permission(link())
        listOf("link:LNK-771A ", "LNK-771A", "link:lnk-771a", "link:LNK-771B", "link:", "LINK:LNK-771A").forEach {
            assertThat(p.at(actor = it)).describedAs("[$it]").isFalse()
        }
    }

    @Test
    fun `INV-AC-2224 another entity, scope or tenant is false`() {
        val p = permission(link())
        assertThat(p.at(entityRef = "CLI-2202", scopeRef = "CLI-2202")).isFalse()
        assertThat(p.at(entityRef = "CLI-2202")).isFalse()
        assertThat(p.at(scopeRef = "CLI-2202")).isFalse()
        assertThat(p.at(scopeRef = null)).isFalse()
        assertThat(p.at(entityRef = null)).isFalse()
        assertThat(p.at(inTenant = otherTenant)).isFalse()
        assertThat(p.at(inTenant = TenantId.PLATFORM, scopeRef = "CLI-2201")).isFalse()
    }

    @Test
    fun `INV-AC-2224 the expiry instant and later, an invalidated, a revoked and a counterparty link are false`() {
        assertThat(permission(link(expiresAt = now.plusSeconds(1))).at()).isTrue()
        assertThat(permission(link(expiresAt = now)).at()).isFalse()
        assertThat(permission(link(expiresAt = now.minusSeconds(1))).at()).isFalse()
        assertThat(permission(link(status = LinkStatus.INVALIDATED)).at()).isFalse()
        assertThat(permission(link(status = LinkStatus.REVOKED)).at()).isFalse()
        assertThat(permission(link(client = null)).at()).isFalse()
        assertThat(permission(null).at()).isFalse()
        assertThat(permission(link(), storedIn = otherTenant).at()).isFalse()
    }

    @Test
    fun `INV-AC-2224 other levels, look-alike keys, a failed read and an exception are false`() {
        val p = permission(link())
        assertThat(p.at(level = ScopeLevel.ENTITY)).isFalse()
        assertThat(p.at(level = ScopeLevel.TENANT, scopeRef = tenant.toString(), entityRef = null)).isFalse()
        listOf("vp-10.set ", "VP-10.set", "vp-10", "client.signatory", "", " ").forEach {
            assertThat(p.at(it)).describedAs("[$it]").isFalse()
        }
        assertThat(permission(link(), failing = true).at()).isFalse()
    }

    @Test
    fun `INV-AC-2224 no role name is written as a literal in the production code of the link check`() {
        val dir =
            java.nio.file.Path
                .of("src/main/kotlin/com/dynokore/gst/access/adapters/http")
        listOf("LinkSignatoryPermission.kt", "LiveSetterPermission.kt").forEach { file ->
            val code =
                java.nio.file.Files
                    .readString(dir.resolve(file))
                    .lines()
                    .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
                    .joinToString("\n")
            assertThat(code).describedAs(file).doesNotContain("client.signatory")
        }
    }
}
