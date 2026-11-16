package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.ORPHAN_ALERT_STATE
import com.dynokore.gst.access.application.alert.ActiveApprovers
import com.dynokore.gst.access.application.alert.HeldApprover
import com.dynokore.gst.access.application.alert.OrphanAlertState
import com.dynokore.gst.access.application.alert.OrphanedApprovers
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.domain.grant.EntityMembership
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The rows, helpers and counts the two ACC-001 test classes share. */
internal object OrphanEnv {
    private const val ALERTS_SQL =
        "select data::text from access.outbox where tenant_id = ? and type like ? order by seq"
    private const val OPEN_SQL =
        "insert into access.orphan_alert_state (tenant_id, entity_ref, opened_at) values (?, ?, now())"
    private val alertTypeLike = "%AccessAlertRaised%"

    private fun column(rows: java.sql.ResultSet) = generateSequence { if (rows.next()) rows.getString(1) else null }

    fun lapsed(
        identity: String,
        scope: Scope,
    ) = SeedSpec(identity, Role.TAX_APPROVER, scope, window = { ended(it) })

    fun entity(
        context: AssertableWebApplicationContext,
        tenant: TenantId,
        ref: String,
    ) = context.getBean(StructureReplica::class.java).accept(
        OrganisationStructureChanged(tenant, ref, StructureChange.CREATED, BoundaryFixture.entityNode()),
    )

    fun alerts(tenant: TenantId): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement(ALERTS_SQL).use {
                it.setObject(1, tenant.value)
                it.setString(2, alertTypeLike)
                it.executeQuery().use { rows -> column(rows).toList() }
            }
        }

    fun openRows(tenant: TenantId): Set<String> =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select entity_ref from access.orphan_alert_state where tenant_id = ?").use {
                it.setObject(1, tenant.value)
                it.executeQuery().use { rows -> column(rows).toSet() }
            }
        }

    fun open(
        tenant: TenantId,
        entity: String,
    ) = BoundaryFixture.asSuperuser { c ->
        c.prepareStatement(OPEN_SQL).use {
            it.setObject(1, tenant.value)
            it.setString(2, entity)
            it.executeUpdate()
        }
    }

    fun ended(now: Instant) = ValidityWindow(now.minus(Duration.ofDays(10)), now.minus(Duration.ofDays(1)))
}

/**
 * ACC-001 scheduled scan, persistence and interval (INV-T-23857; INV-REQ-895): the stored rows, the claim function,
 * the adapters and the scan over the real database and outbox.
 */
class OrphanedApproverScanTest {
    private val yaml = File("src/main/resources/application.yaml")

    private fun grantTenant(
        context: AssertableWebApplicationContext,
        tenant: TenantId,
    ) = Seeds.grant(context, tenant, SeedSpec("user:admin", Role.TENANT_ADMIN))

    @Test
    fun `INV-AC-23855 the scan raises once for the lapsed approver, nothing for a never-held entity, and settles`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val (a, b) = TenantId(UUID.randomUUID()) to TenantId(UUID.randomUUID())
            val clock = context.getBean(java.time.Clock::class.java)
            listOf("E1", "E2", "E3").forEach { OrphanEnv.entity(context, a, it) }
            OrphanEnv.entity(context, b, "E9")
            grantTenant(context, a)
            grantTenant(context, b)
            Seeds.grant(context, a, OrphanEnv.lapsed("user:old", Scope.Entity(EntityRef("E1"))))
            Seeds.grant(context, a, SeedSpec("user:cur", Role.TAX_APPROVER, Scope.Entity(EntityRef("E2"))))
            val scan = context.getBean(OrphanedApproverScan::class.java)
            val tenants = context.getBean(TenantContext::class.java)

            // a scan of tenant A runs while tenant B is bound by another caller
            tenants.bind(b, "other-caller") { assertThat(scan.runOnce()).isEqualTo(0) }
            val first = OrphanEnv.alerts(a)
            assertThat(first).hasSize(1)
            assertThat(first.single()).contains("ACC-001").contains("entity:E1")
            assertThat(first.single()).doesNotContain("entity:E3").doesNotContain("entity:E2")
            assertThat(OrphanEnv.alerts(b)).isEmpty()
            assertThat(OrphanEnv.openRows(a)).containsExactly("E1")

            scan.runOnce()
            assertThat(OrphanEnv.alerts(a)).hasSize(1)

            Seeds.grant(context, a, SeedSpec("user:new", Role.TAX_APPROVER, Scope.Entity(EntityRef("E1"))))
            scan.runOnce()
            assertThat(OrphanEnv.openRows(a)).isEmpty()
            assertThat(OrphanEnv.alerts(a)).hasSize(1)

            OrphanEnv.open(a, "E2")
            scan.runOnce()
            assertThat(OrphanEnv.openRows(a)).isEmpty()
            assertThat(clock.instant()).isNotNull()

            // every state row of tenant A is invisible while tenant B is bound
            OrphanEnv.open(a, "E1")
            val seen =
                tenants.bind(b, "other-caller") {
                    context.getBean(TransactionRunner::class.java).inTransaction {
                        context
                            .getBean(DSLContext::class.java)
                            .selectFrom(ORPHAN_ALERT_STATE)
                            .fetch()
                            .size
                    }
                }
            assertThat(seen).isEqualTo(0)
        }
    }

    @Test
    fun `INV-AC-23855 a failure in one tenant is counted and the next tenant is still scanned`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val (bad, good) = TenantId(UUID.randomUUID()) to TenantId(UUID.randomUUID())
            val tenants = context.getBean(TenantContext::class.java)
            OrphanEnv.entity(context, good, "E1")
            OrphanEnv.entity(context, bad, "E1")
            listOf(bad, good).forEach {
                grantTenant(context, it)
                Seeds.grant(context, it, OrphanEnv.lapsed("user:old", Scope.Entity(EntityRef("E1"))))
            }
            val real = context.getBean(EntityDirectory::class.java)
            val failing =
                object : EntityDirectory {
                    override fun membership(entity: EntityRef): EntityMembership? = real.membership(entity)

                    override fun entitiesCoveredBy(scope: Scope): Set<EntityRef> {
                        check(tenants.current() != bad) { "evaluation fails for the bad tenant" }
                        return real.entitiesCoveredBy(scope)
                    }
                }
            val scan =
                OrphanedApproverScan(
                    { listOf(bad, good) },
                    context.getBean(OrphanedApprovers::class.java),
                    failing,
                    tenants,
                    context.getBean(PlatformTenantBinding::class.java),
                    context.getBean(TransactionRunner::class.java),
                )
            assertThat(scan.runOnce()).isEqualTo(1)
            assertThat(OrphanEnv.alerts(good)).hasSize(1)
            assertThat(OrphanEnv.alerts(bad)).isEmpty()
        }
    }

    @Test
    fun `INV-AC-23855 a second insert for one tenant and entity is refused by the key, and tryOpen answers once`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val tenant = TenantId(UUID.randomUUID())
            OrphanEnv.open(tenant, "E1")
            assertThatThrownBy { OrphanEnv.open(tenant, "E1") }.isInstanceOf(SQLException::class.java)
            val state = context.getBean(OrphanAlertState::class.java)
            val tenants = context.getBean(TenantContext::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            val results =
                (1..2)
                    .map {
                        java.util.concurrent.CompletableFuture.supplyAsync {
                            tenants.bind(tenant, "t") { tx.inTransaction { state.tryOpen(EntityRef("E2")) } }
                        }
                    }.map { it.get() }
            assertThat(results.count { it }).isEqualTo(1)
        }
    }

    @Test
    fun `INV-AC-23855 the claim returns a tenant holding only a tenant admin grant and omits one holding none`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val (held, none) = TenantId(UUID.randomUUID()) to TenantId(UUID.randomUUID())
            grantTenant(context, held)
            val claimed =
                context.getBean(PlatformTenantBinding::class.java).bindPlatform("test") {
                    com.dynokore.gst.access.adapters.alert
                        .JooqOrphanScanTenants(
                            context.getBean(DSLContext::class.java),
                            context.getBean(TransactionRunner::class.java),
                        ).invoke()
                }
            assertThat(claimed).contains(held).doesNotContain(none)
        }
    }

    @Test
    fun `INV-AC-23855 a read under another tenant binding returns zero state rows`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val (a, b) = TenantId(UUID.randomUUID()) to TenantId(UUID.randomUUID())
            OrphanEnv.open(a, "E1")
            BoundaryFixture.asRuntime { c ->
                c.createStatement().use { s ->
                    s.executeQuery("select count(*) from access.orphan_alert_state").use {
                        it.next()
                        assertThat(it.getInt(1)).isEqualTo(0)
                    }
                }
            }
            assertThat(OrphanEnv.openRows(b)).isEmpty()
        }
    }

    @Test
    fun `INV-AC-23855 adapters count a tenant-wide approver, skip ended windows and other roles`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val tenant = TenantId(UUID.randomUUID())
            val tenants = context.getBean(TenantContext::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            val active = context.getBean(ActiveApprovers::class.java)
            val held = context.getBean(HeldApprover::class.java)
            listOf("E1", "E2", "E3", "E4").forEach { OrphanEnv.entity(context, tenant, it) }
            val open = { n: Instant -> ValidityWindow(n.minus(Duration.ofDays(1)), null) }
            Seeds.grant(context, tenant, SeedSpec("user:wide", Role.TAX_APPROVER, window = open))
            Seeds.grant(context, tenant, SeedSpec("user:op", Role.TAX_OPERATOR, Scope.Entity(EntityRef("E1"))))
            val now = context.getBean(java.time.Clock::class.java).instant()
            tenants.bind(tenant, "t") {
                tx.inTransaction {
                    // a tenant-wide approver counts for every entity of the tenant; a grant of another role does not
                    listOf("E1", "E2", "E3", "E4").forEach {
                        assertThat(active.countFor(EntityRef(it), now)).isEqualTo(1)
                    }
                    // before its window opens and after it no approver counts
                    assertThat(active.countFor(EntityRef("E1"), now.minus(Duration.ofDays(5)))).isEqualTo(0)
                }
            }
            val other = TenantId(UUID.randomUUID())
            OrphanEnv.entity(context, other, "E1")
            Seeds.grant(context, other, SeedSpec("user:op", Role.TAX_OPERATOR, Scope.Entity(EntityRef("E1"))))
            Seeds.grant(context, other, OrphanEnv.lapsed("user:old", Scope.Entity(EntityRef("E1"))))
            tenants.bind(other, "t") {
                tx.inTransaction {
                    assertThat(active.countFor(EntityRef("E1"), now)).isEqualTo(0)
                    assertThat(held.everHeld(EntityRef("E1"))).isTrue()
                    assertThat(held.everHeld(EntityRef("E7"))).isFalse()
                }
            }
        }
    }

    @Test
    fun `INV-AC-23855 an entity covered by no tax approver row of any status gives false`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val tenant = TenantId(UUID.randomUUID())
            OrphanEnv.entity(context, tenant, "E3")
            grantTenant(context, tenant)
            val held = context.getBean(HeldApprover::class.java)
            val tenants = context.getBean(TenantContext::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            assertThat(tenants.bind(tenant, "t") { tx.inTransaction { held.everHeld(EntityRef("E3")) } }).isFalse()
        }
    }

    // ---- the interval -----------------------------------------------------------------------------------------

    private fun bind(values: Map<String, String>) =
        Binder(MapConfigurationPropertySource(values))
            .bindOrCreate("access.orphaned-approver", OrphanedApproverProperties::class.java)

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrphanedApproverProperties::class)
    class PropertiesOnly

    private fun startup(vararg values: String) =
        ApplicationContextRunner().withUserConfiguration(PropertiesOnly::class.java).withPropertyValues(*values)

    @Test
    fun `INV-AC-23855 a context without the interval, with PT0S or with a negative one fails to start`() {
        startup().run { assertThat(it.startupFailure).isNotNull() }
        startup("access.orphaned-approver.interval=PT0S").run { assertThat(it.startupFailure).isNotNull() }
        startup("access.orphaned-approver.interval=-PT1M").run { assertThat(it.startupFailure).isNotNull() }
        startup("access.orphaned-approver.interval=PT15M").run { assertThat(it.startupFailure).isNull() }
        assertThatThrownBy { bind(emptyMap()) }.isInstanceOf(Exception::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `INV-AC-23855 the interval read from application yaml equals PT15M`() {
        val root = Yaml().load<Map<String, Any?>>(yaml.readText())
        val section = (root["access"] as Map<String, Any?>)["orphaned-approver"] as Map<String, Any?>
        assertThat(Duration.parse(section["interval"] as String)).isEqualTo(Duration.ofMinutes(15))
    }
}
