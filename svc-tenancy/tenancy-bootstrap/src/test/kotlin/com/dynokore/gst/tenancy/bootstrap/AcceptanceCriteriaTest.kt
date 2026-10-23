package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.BUSINESS_UNIT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SHORT_CODE
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.application.installation.InstallationFact
import com.dynokore.gst.tenancy.application.installation.InstallationFactRepository
import com.dynokore.gst.tenancy.application.structure.ChangeType
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.StructureChange
import com.dynokore.gst.tenancy.application.structure.StructureChangePublisher
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.replica.Lookup
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.core.env.EnumerablePropertySource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The acceptance criteria of INV-T-4925 at the service boundary: the real application context over a real PostgreSQL
 * with forced row-level security and the signed binding. Test names carry the criterion key (acKeyCoverage).
 *
 * The storage-layer criteria are asserted here with rows written through jOOQ under a bound tenant; the operations
 * themselves (createTenant, createGroup, createEntity, business units, getEntityStructure) are tested in
 * OperationsTest.
 */
class AcceptanceCriteriaTest {
    private val other = TenantId(UUID.randomUUID())

    private fun run(block: (AssertableWebApplicationContext, Env) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(context, Env(context))
        }
    }

    private class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val tx: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val captured: CapturedEvents = context.getBean(CapturedEvents::class.java)
        val publisher: StructureChangePublisher = context.getBean(StructureChangePublisher::class.java)

        fun <T> inTenant(
            tenant: TenantId,
            block: () -> T,
        ): T = tenants.bind(tenant, "user:test") { tx.inTransaction(block) }

        /** A tenant with one group, one entity and one business unit, under the tenant's binding. */
        fun structure(tenant: TenantId = TenantId(UUID.randomUUID())): Pair<TenantId, Structure> {
            val suffix = UUID.randomUUID().toString().take(8)
            val code = suffix.take(5).uppercase().replace('-', 'X')
            return tenant to inTenant(tenant) { Rows.structure(dsl, tenant, suffix, code) }
        }

        /** A GST group with the given GSTN and two member entities with UENs UEN-A and UEN-B. */
        fun gstGroup(gstn: String): Pair<TenantId, Members> {
            val tenant = TenantId(UUID.randomUUID())
            val members = Members(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
            val suffix = UUID.randomUUID().toString().take(8)
            inTenant(tenant) {
                Rows.tenant(dsl, tenant, suffix)
                Rows.group(dsl, tenant, members.group, "group-$suffix", "GST_GROUP", gstn)
                Rows.entity(
                    dsl,
                    tenant,
                    members.group,
                    members.first,
                    "a-$suffix",
                    "A$suffix".take(10).uppercase(),
                    "LIVE",
                    null,
                    "UEN-A",
                )
                Rows.entity(
                    dsl,
                    tenant,
                    members.group,
                    members.second,
                    "b-$suffix",
                    "B$suffix".take(10).uppercase(),
                    "LIVE",
                    null,
                    "UEN-B",
                )
            }
            return tenant to members
        }

        /** The member's own UEN and the GSTN of its group. */
        fun memberIdentity(entity: UUID): Pair<String?, String?> =
            dsl
                .select(ENTITY.UEN, TENANT_GROUP.GSTN)
                .from(ENTITY)
                .join(TENANT_GROUP)
                .on(TENANT_GROUP.ID.eq(ENTITY.GROUP_ID))
                .where(ENTITY.ID.eq(entity))
                .fetchSingle()
                .let { it.value1() to it.value2() }

        fun change(
            tenant: TenantId,
            node: String,
            type: ChangeType,
            version: Long,
        ) = StructureChange(
            tenant.value,
            node,
            NodeType.ENTITY,
            node,
            type,
            null,
            null,
            StructureNodeState(NodeType.ENTITY, null, false, "Entity", "E1", version),
        )
    }

    private class Members(
        val group: UUID,
        val first: UUID,
        val second: UUID,
    )

    private fun unique() = UUID.randomUUID().toString().take(8)

    @Test
    fun `INV-AC-1714 a business unit row without a registered entity is rejected by the storage layer`() {
        run { _, env ->
            val (tenant, structure) = env.structure()
            val columns =
                BoundaryFixture.entityColumns()
            assertThat(columns).isNotEmpty().allSatisfy { assertThat(it.second).describedAs(it.first).isEqualTo("NO") }
            // the application session: a row without entity_id never reaches the table
            assertThatThrownBy {
                env.inTenant(tenant) {
                    env.dsl
                        .insertInto(BUSINESS_UNIT)
                        .set(BUSINESS_UNIT.ID, UUID.randomUUID())
                        .set(BUSINESS_UNIT.TENANT_ID, tenant.value)
                        .set(BUSINESS_UNIT.REF, "orphan-${unique()}")
                        .set(BUSINESS_UNIT.NAME, "Orphan")
                        .set(BUSINESS_UNIT.CODE, "ORPHAN")
                        .set(BUSINESS_UNIT.STRUCTURE_VERSION, 1L)
                        .set(BUSINESS_UNIT.CREATED_AT, OffsetDateTime.now())
                        .execute()
                }
            }.hasMessageContaining("entity_id")
            // the storage layer itself, as the superuser who bypasses row-level security
            assertThatThrownBy { BoundaryFixture.insertBusinessUnit(tenant.value, "ORPHAN2", null) }
                .hasMessageContaining("entity_id")
            // an entity of another tenant is no entity of this one
            assertThatThrownBy { BoundaryFixture.insertBusinessUnit(other.value, "CROSS", structure.entity) }
                .hasMessageContaining("violates foreign key")
        }
    }

    @Test
    fun `INV-AC-2041 the library-table exemption names only tables that config-resolver creates`() {
        val (exempt, created) = BoundaryFixture.exemptAndLibraryTables()
        assertThat(created).isNotEmpty()
        assertThat(created).containsAll(exempt)
    }

    @Test
    fun `INV-AC-2041 per-entity tables carry a non-nullable entity reference and none is keyed by group or tenant`() {
        val nullable =
            BoundaryFixture.nullableScopeColumns()
        assertThat(nullable).isEmpty()
        // keys that are exactly {tenant_id} or {group_id}: only the tenant table's own key may be one (exact equality)
        val keys =
            BoundaryFixture.keyColumns()
        assertThat(keys.filter { it.second == "tenant_id" || it.second == "group_id" })
            .containsExactly("tenant" to "tenant_id")
    }

    @Test
    fun `INV-AC-1900 a query without the tenant predicate returns no other tenant's rows`() {
        run { _, env ->
            val (one, _) = env.structure()
            val (two, _) = env.structure()
            val seen =
                env.inTenant(one) {
                    listOf(
                        env.dsl.selectFrom(TENANT).fetch(TENANT.TENANT_ID),
                        env.dsl.selectFrom(TENANT_GROUP).fetch(TENANT_GROUP.TENANT_ID),
                        env.dsl.selectFrom(ENTITY).fetch(ENTITY.TENANT_ID),
                        env.dsl.selectFrom(BUSINESS_UNIT).fetch(BUSINESS_UNIT.TENANT_ID),
                        env.dsl.selectFrom(SHORT_CODE).fetch(SHORT_CODE.TENANT_ID),
                    )
                }
            seen.forEach { assertThat(it.toSet()).isEqualTo(setOf(one.value)) }
            // a session without a tenant returns nothing, on every tenant table
            val counts = BoundaryFixture.unboundRowCounts()
            assertThat(counts.map { it.first }).hasSize(5)
            counts.forEach { assertThat(it.second).describedAs(it.first).isZero() }
            // and a row written for another tenant is refused
            assertThatThrownBy { env.inTenant(one) { Rows.structure(env.dsl, two, unique(), "ZZ1") } }
                .hasMessageContaining("not the bound tenant")
        }
    }

    @Test
    fun `INV-AC-2069 the installation runs tenant-scoped with no tenancy switch and a record carries the tenant`() {
        run { _, env ->
            // no property of the packaged configuration names tenancy except the reviewed list (exact names)
            val allowed =
                setOf(
                    "inv.tenancy.platform-scoped-tables[0]",
                    "inv.tenancy.platform-scoped-tables[1]",
                    "inv.tenancy.platform-scoped-tables[2]",
                )
            val names =
                env.context.environment.propertySources
                    .filterIsInstance<EnumerablePropertySource<*>>()
                    .filter { it.name.contains("application") }
                    .flatMap { it.propertyNames.toList() }
            assertThat(names).isNotEmpty()
            assertThat(names.filter { it.contains("tenan", ignoreCase = true) && it !in allowed }).isEmpty()
            assertThat(names.filter { it in allowed }).hasSameSizeAs(allowed)
            // the installation holds one tenant; its record carries the identifier and no other binding sees it
            val (only, _) = env.structure()
            assertThat(env.inTenant(only) { env.dsl.selectFrom(TENANT).fetch(TENANT.TENANT_ID) })
                .containsExactly(only.value)
            assertThat(env.inTenant(other) { env.dsl.selectFrom(ENTITY).fetch() }).isEmpty()
        }
    }

    @Test
    fun `INV-AC-1814 a group record holds the group GSTN and links two member entities with their own UENs`() {
        run { _, env ->
            val (tenant, group) = env.gstGroup("M2-1814-${unique()}")
            val read =
                env.inTenant(tenant) {
                    env.dsl
                        .selectFrom(ENTITY)
                        .where(ENTITY.GROUP_ID.eq(group.group))
                        .fetch()
                }
            assertThat(read.map { it.uen }).containsExactlyInAnyOrder("UEN-A", "UEN-B")
            assertThat(read.map { it.id }).containsExactlyInAnyOrder(group.first, group.second)
            val groupRows = env.inTenant(tenant) { env.dsl.fetchCount(TENANT_GROUP) }
            assertThat(groupRows).isEqualTo(1)
        }
    }

    @Test
    fun `INV-AC-346 a member of a group transmitting per entity is served with its own UEN and the group GSTN`() {
        run { _, env ->
            val gstn = "M2-346-${unique()}"
            val (tenant, group) = env.gstGroup(gstn)
            val row = env.inTenant(tenant) { env.memberIdentity(group.first) }
            assertThat(row).isEqualTo("UEN-A" to gstn)
        }
    }

    @Test
    fun `INV-AC-767 the members of a group are served with each member UEN and the one group GSTN`() {
        run { _, env ->
            val gstn = "M2-767-${unique()}"
            val (tenant, group) = env.gstGroup(gstn)
            val rows = env.inTenant(tenant) { listOf(group.first, group.second).map { env.memberIdentity(it) } }
            assertThat(rows).containsExactly("UEN-A" to gstn, "UEN-B" to gstn)
        }
    }

    @Test
    fun `INV-AC-347 a division carries the parent UEN and its own division GSTN`() {
        run { _, env ->
            val tenant = TenantId(UUID.randomUUID())
            val group = UUID.randomUUID()
            val parent = UUID.randomUUID()
            val division = UUID.randomUUID()
            val code = unique().take(5).uppercase().replace('-', 'X')
            env.inTenant(tenant) {
                Rows.tenant(env.dsl, tenant, unique())
                Rows.group(env.dsl, tenant, group, "div-${unique()}", "DIVISIONAL", null)
                Rows.entity(env.dsl, tenant, group, parent, "parent-${unique()}", code, "LIVE", null, "U-P", null)
                Rows.entity(
                    env.dsl,
                    tenant,
                    group,
                    division,
                    "division-${unique()}",
                    "${code}D",
                    "LIVE",
                    null,
                    "U-P",
                    "G-D",
                )
            }
            val read =
                env.inTenant(tenant) {
                    env.dsl
                        .selectFrom(ENTITY)
                        .where(ENTITY.ID.eq(division))
                        .fetchSingle()
                }
            assertThat(read.uen).isEqualTo("U-P")
            assertThat(read.gstn).isEqualTo("G-D")
        }
    }

    @Test
    fun `INV-AC-1898 entities of one tenant hold different go-live dates and lifecycle states`() {
        run { _, env ->
            val (tenant, structure) = env.structure()
            val trading = UUID.randomUUID()
            env.inTenant(tenant) {
                Rows.entity(env.dsl, tenant, structure.group, trading, "trading-${unique()}", "TRAD1", "DRAFT", null)
            }
            val states =
                env.inTenant(tenant) {
                    env.dsl
                        .selectFrom(ENTITY)
                        .fetch()
                        .associate { it.id to (it.lifecycleState to it.goLiveDate) }
                }
            assertThat(states[structure.entity]).isEqualTo("LIVE" to LocalDate.of(2028, 4, 1))
            assertThat(states[trading]).isEqualTo("DRAFT" to null)
        }
    }

    @Test
    fun `INV-AC-1896 the four levels are distinct records and a structure change reaches the replica`() {
        run { ctx, env ->
            val (tenant, structure) = env.structure()
            val ids = setOf(tenant.value, structure.group, structure.entity, structure.businessUnit)
            assertThat(ids).hasSize(4)
            val moved = structure.businessUnit.toString()
            env.captured.envelopes.clear()
            env.inTenant(tenant) { env.publisher.publish(env.change(tenant, moved, ChangeType.MOVED, 3)) }
            val envelope = env.captured.envelopes.single()
            assertThat(envelope.type).isEqualTo(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED)
            // the replica applies exactly what the service published
            ctx.getBean(StructureFeedHandler::class.java).handle(envelope)
            val lookup = ctx.getBean(StructureReplica::class.java).node(tenant, moved, deadline())
            assertThat(lookup).isInstanceOf(Lookup.Local::class.java)
            assertThat((lookup as Lookup.Local<StructureNode>).value.structureVersion).isEqualTo(3)
        }
    }

    @Test
    fun `an event published in a rolled-back command transaction leaves no outbox row`() {
        run { _, env ->
            val tenant = TenantId(UUID.randomUUID())
            val node = UUID.randomUUID().toString()
            assertThatThrownBy {
                env.inTenant(tenant) {
                    env.publisher.publish(env.change(tenant, node, ChangeType.CREATED, 1))
                    error("command failed")
                }
            }.hasMessageContaining("command failed")
            assertThat(BoundaryFixture.outboxRowsMentioning(node)).isZero()
        }
    }

    @Test
    fun `INV-TS-3328 a replica fed only by the events serves a read with no tenancy-svc call`() {
        run { _, env ->
            val tenant = TenantId(UUID.randomUUID())
            val node = UUID.randomUUID().toString()
            env.captured.envelopes.clear()
            env.inTenant(tenant) { env.publisher.publish(env.change(tenant, node, ChangeType.CREATED, 1)) }
            val replica = StructureReplica(Clock.systemUTC()) // no fallback: nothing can call tenancy-svc
            StructureFeedHandler(replica).handle(env.captured.envelopes.single())
            assertThat(replica.node(tenant, node, deadline())).isInstanceOf(Lookup.Local::class.java)
        }
    }

    @Test
    fun `an installation fact is written and read back through the repository`() {
        run { ctx, _ ->
            val repository = ctx.getBean(InstallationFactRepository::class.java)
            val key = "release-train-${unique()}"
            val at = Instant.parse("2026-10-19T08:00:00Z")
            repository.put(InstallationFact(key, "2026.4", at))
            assertThat(repository.find(key)).isEqualTo(InstallationFact(key, "2026.4", at))
            repository.put(InstallationFact(key, "2026.5", at))
            assertThat(repository.find(key)?.value).isEqualTo("2026.5")
            assertThat(repository.all().map { it.key }).contains(key)
            assertThat(repository.find("absent-${unique()}")).isNull()
        }
    }

    private fun deadline() = Deadline.after(Duration.ofMillis(500), Clock.systemUTC())
}
