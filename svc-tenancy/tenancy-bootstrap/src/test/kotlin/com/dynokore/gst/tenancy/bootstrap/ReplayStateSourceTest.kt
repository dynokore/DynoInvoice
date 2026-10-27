package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.BUSINESS_UNIT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.application.structure.ChangeType
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureChange
import com.dynokore.gst.tenancy.application.structure.StructureChangePublisher
import com.dynokore.gst.tenancy.application.structure.StructureCommands
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.gst.tenancy.application.structure.StructureQueries
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.gst.tenancy.contract.model.CreateEntityRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.RegisterBusinessUnitRequest
import com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest
import com.dynokore.gst.tenancy.contract.model.UpdateBusinessUnitRequest
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.ReplayPage
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.outboxandqueue.ReplayedState
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.replica.Lookup
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

private const val STRUCTURE_TYPE = "com.dynokore.gst.tenancy.OrganisationStructureChanged.v1"
private const val PROFILE_TYPE = "com.dynokore.gst.tenancy.EntityProfileChanged.v1"

/** The last migration before the one under test. */
private const val BEFORE = "202610271000"

private class Env(
    val context: AssertableWebApplicationContext,
) {
    val profile = ProfileEnv(context)
    val dsl: DSLContext = context.getBean(DSLContext::class.java)
    val queries: StructureQueries = context.getBean(StructureQueries::class.java)
    val commands: StructureCommands = context.getBean(StructureCommands::class.java)
    val captured: CapturedEvents = context.getBean(CapturedEvents::class.java)
    private val sources = context.getBeansOfType(ReplayStateSource::class.java).values

    private val store: StructureStore = context.getBean(StructureStore::class.java)
    private val publisher: StructureChangePublisher = context.getBean(StructureChangePublisher::class.java)

    /** The fixture retires the entity (`retired` true) with a new structure version and its live RETIRED event. */
    fun retire(
        tenant: TenantId,
        ref: String,
    ) {
        val retired = store.entity(ref)!!.copy(retired = true, structureVersion = store.nextStructureVersion())
        dsl
            .update(ENTITY)
            .set(ENTITY.RETIRED, true)
            .set(ENTITY.STRUCTURE_VERSION, retired.structureVersion)
            .where(ENTITY.REF.eq(ref))
            .execute()
        val change =
            StructureChange(
                tenant.value,
                ref,
                NodeType.ENTITY,
                ref,
                ChangeType.RETIRED,
                retired.groupRef,
                null,
                entityNode(retired),
            )
        publisher.publish(change)
    }

    /** [source]'s tenants, as the responder reads them: under the platform binding. */
    fun tenantsOf(source: ReplayStateSource): List<TenantId> =
        (
            context.getBean(
                TenantContext::class.java,
            ) as PlatformTenantBinding
        ).bindPlatform("svc:tenancy-svc/job:replay") {
            profile.inTx { source.tenants() }
        }

    val structure: ReplayStateSource = sources.single { it.eventType == STRUCTURE_TYPE }
    val profiles: ReplayStateSource = sources.single { it.eventType == PROFILE_TYPE }

    fun pages(
        tenant: TenantId,
        source: ReplayStateSource,
        scope: ReplayScope? = null,
        limit: Int = 100,
    ): List<ReplayPage> {
        val out = ArrayList<ReplayPage>()
        var cursor: String? = null
        do {
            val page = profile.inTenant(tenant) { profile.inTx { source.page(scope, cursor, limit) } }
            out += page
            cursor = page.next
        } while (cursor != null)
        return out
    }

    fun states(
        tenant: TenantId,
        source: ReplayStateSource,
        scope: ReplayScope? = null,
        limit: Int = 100,
    ): List<ReplayedState> = pages(tenant, source, scope, limit).flatMap { it.items }

    fun keys(
        tenant: TenantId,
        source: ReplayStateSource,
        scope: ReplayScope?,
    ) = states(tenant, source, scope).map { it.aggregateKey }
}

private class Scenario(
    val tenant: TenantId,
    val group: String,
    val live1: String,
    val live2: String,
    val retired: String,
    val firstEntity: EntityResponse,
) {
    val refs get() = listOf(group, live1, live2, retired)
}

/** One of the three tables: its insert and its update, each forcing a stale `changed_at`, and the read-back. */
private class Stamped(
    val table: String,
    val insert: (OffsetDateTime) -> Unit,
    val update: (OffsetDateTime) -> Unit,
    val read: () -> OffsetDateTime,
)

private fun groupStamp(
    dsl: DSLContext,
    tenant: TenantId,
    suffix: String,
    group: UUID,
): Stamped =
    Stamped(
        "tenant_group",
        { at ->
            dsl
                .insertInto(TENANT_GROUP)
                .set(TENANT_GROUP.ID, group)
                .set(TENANT_GROUP.TENANT_ID, tenant.value)
                .set(TENANT_GROUP.REF, "g-$suffix")
                .set(TENANT_GROUP.NAME, "Group $suffix")
                .set(TENANT_GROUP.KIND, "DIVISIONAL")
                .set(TENANT_GROUP.STRUCTURE_VERSION, 1L)
                .set(TENANT_GROUP.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(TENANT_GROUP.CHANGED_AT, at)
                .execute()
        },
        { at ->
            dsl
                .update(TENANT_GROUP)
                .set(TENANT_GROUP.NAME, "Renamed $suffix")
                .set(TENANT_GROUP.CHANGED_AT, at)
                .where(TENANT_GROUP.ID.eq(group))
                .execute()
        },
        { changed(dsl, TENANT_GROUP, TENANT_GROUP.ID, TENANT_GROUP.CHANGED_AT, group) },
    )

private fun entityStamp(
    dsl: DSLContext,
    tenant: TenantId,
    suffix: String,
    group: UUID,
    entity: UUID,
): Stamped {
    val code = suffix.uppercase()
    return Stamped(
        "entity",
        { at ->
            dsl
                .insertInto(ENTITY)
                .set(ENTITY.ID, entity)
                .set(ENTITY.TENANT_ID, tenant.value)
                .set(ENTITY.REF, "e-$suffix")
                .set(ENTITY.GROUP_ID, group)
                .set(ENTITY.LEGAL_NAME, "Entity $suffix")
                .set(ENTITY.CONSTITUTION, "COMPANY")
                .set(ENTITY.CODE, "E$code")
                .set(ENTITY.LIFECYCLE_STATE, "DRAFT")
                .set(ENTITY.STRUCTURE_VERSION, 1L)
                .set(ENTITY.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(ENTITY.CHANGED_AT, at)
                .execute()
        },
        { at ->
            dsl
                .update(ENTITY)
                .set(ENTITY.LEGAL_NAME, "Renamed $suffix")
                .set(ENTITY.CHANGED_AT, at)
                .where(ENTITY.ID.eq(entity))
                .execute()
        },
        { changed(dsl, ENTITY, ENTITY.ID, ENTITY.CHANGED_AT, entity) },
    )
}

private fun unitStamp(
    dsl: DSLContext,
    tenant: TenantId,
    suffix: String,
    entity: UUID,
    unit: UUID,
): Stamped =
    Stamped(
        "business_unit",
        { at -> insertUnit(dsl, tenant, entity to unit, suffix, at) },
        { at ->
            dsl
                .update(BUSINESS_UNIT)
                .set(BUSINESS_UNIT.NAME, "Renamed $suffix")
                .set(BUSINESS_UNIT.CHANGED_AT, at)
                .where(BUSINESS_UNIT.ID.eq(unit))
                .execute()
        },
        { changed(dsl, BUSINESS_UNIT, BUSINESS_UNIT.ID, BUSINESS_UNIT.CHANGED_AT, unit) },
    )

private fun <R : Record> changed(
    dsl: DSLContext,
    table: Table<R>,
    id: TableField<R, UUID?>,
    changedAt: TableField<R, OffsetDateTime?>,
    key: UUID,
): OffsetDateTime =
    dsl
        .select(changedAt)
        .from(table)
        .where(id.eq(key))
        .fetchSingle()
        .value1()!!

private fun insertUnit(
    dsl: DSLContext,
    tenant: TenantId,
    entityAndUnit: Pair<UUID, UUID>,
    suffix: String,
    changedAt: OffsetDateTime,
) {
    val (entity, unit) = entityAndUnit
    dsl
        .insertInto(BUSINESS_UNIT)
        .set(BUSINESS_UNIT.ID, unit)
        .set(BUSINESS_UNIT.TENANT_ID, tenant.value)
        .set(BUSINESS_UNIT.REF, "u-$suffix")
        .set(BUSINESS_UNIT.ENTITY_ID, entity)
        .set(BUSINESS_UNIT.NAME, "Unit $suffix")
        .set(BUSINESS_UNIT.CODE, "U${suffix.uppercase()}")
        .set(BUSINESS_UNIT.STRUCTURE_VERSION, 1L)
        .set(BUSINESS_UNIT.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
        .set(BUSINESS_UNIT.CHANGED_AT, changedAt)
        .execute()
}

private object SeededMigration {
    fun bindingFunctions(): String =
        "CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;\n" +
            "CREATE SCHEMA IF NOT EXISTS tenancy AUTHORIZATION tenancy_sys;\n" +
            "SET ROLE tenancy_sys;\n" +
            BindingKeyMigration.ddl("tenancy", "tenancy_sys", "tenancy_runtime") +
            "\nRESET ROLE;"

    fun migrate(
        url: String,
        user: String,
        password: String,
        target: String?,
    ) {
        val directory = Path.of("..", "tenancy-adapters", "src", "main", "resources", "db", "migration")
        Flyway
            .configure()
            .dataSource(url, user, password)
            .schemas("tenancy")
            .defaultSchema("tenancy")
            .locations("filesystem:" + directory.toAbsolutePath().normalize())
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .also { if (target != null) it.target(target) }
            .load()
            .migrate()
    }

    /** One row of each of the four levels, created on distinct days of 2026-01. */
    val SEED =
        """
        insert into tenancy.tenant (tenant_id, ref, legal_name, plan_ref, contract_reference, structure_version,
                                    created_at)
          values ('00000000-0000-4000-8000-000000000001', 't', 'T', 'p', 'c', 1, '2026-01-01T00:00:00Z');
        insert into tenancy.tenant_group (id, tenant_id, ref, name, kind, structure_version, created_at)
          values ('00000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000001', 'g', 'G',
                  'DIVISIONAL', 2, '2026-01-02T00:00:00Z');
        insert into tenancy.entity (id, tenant_id, ref, group_id, legal_name, constitution, code, lifecycle_state,
                                    structure_version, created_at)
          values ('00000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000001', 'e',
                  '00000000-0000-4000-8000-000000000002', 'E', 'COMPANY', 'E1', 'DRAFT', 3, '2026-01-03T00:00:00Z');
        insert into tenancy.business_unit (id, tenant_id, ref, entity_id, name, code, structure_version, created_at)
          values ('00000000-0000-4000-8000-000000000004', '00000000-0000-4000-8000-000000000001', 'u',
                  '00000000-0000-4000-8000-000000000003', 'U', 'U1', 4, '2026-01-04T00:00:00Z');
        """.trimIndent()
}

private val json = JsonMapper.builder().build()

private fun nodeOf(dataJson: String): StructureNodeState {
    val node = json.readTree(dataJson)["node"]
    return StructureNodeState(
        NodeType.valueOf(node["kind"].asString()),
        node["parentRef"]?.asString(),
        node["retired"].asBoolean(),
        node["name"].asString(),
        node["code"]?.asString(),
        node["structureVersion"].asLong(),
        node["uen"]?.asString(),
        node["gstn"]?.asString(),
        node["profile"].toList().map {
            val date = LocalDate.parse(it["effectiveDate"].asString())
            ProfileEntry(it["attribute"].asString(), it["value"].asString(), date)
        },
    )
}

private fun lastLiveVersion(
    env: Env,
    ref: String,
): Long {
    val last = env.captured.envelopes.last { it.subject == ref }
    return json.readTree(last.dataJson)["node"]["structureVersion"].asLong()
}

private fun storedVersion(
    env: Env,
    ref: String,
): Long =
    env.profile.inTx {
        env.dsl
            .select(TENANT_GROUP.STRUCTURE_VERSION)
            .from(TENANT_GROUP)
            .where(TENANT_GROUP.REF.eq(ref))
            .fetchOne()
            ?.value1()
            ?: env.dsl
                .select(ENTITY.STRUCTURE_VERSION)
                .from(ENTITY)
                .where(ENTITY.REF.eq(ref))
                .fetchOne()
                ?.value1()
            ?: env.dsl
                .select(BUSINESS_UNIT.STRUCTURE_VERSION)
                .from(BUSINESS_UNIT)
                .where(BUSINESS_UNIT.REF.eq(ref))
                .fetchSingle()
                .value1()
    }!!

private fun changedAt(
    env: Env,
    ref: String,
): Instant =
    env.profile.inTx {
        (
            env.dsl
                .select(TENANT_GROUP.CHANGED_AT)
                .from(TENANT_GROUP)
                .where(TENANT_GROUP.REF.eq(ref))
                .fetchOne()
                ?.value1()
                ?: env.dsl
                    .select(ENTITY.CHANGED_AT)
                    .from(ENTITY)
                    .where(ENTITY.REF.eq(ref))
                    .fetchOne()
                    ?.value1()
                ?: env.dsl
                    .select(BUSINESS_UNIT.CHANGED_AT)
                    .from(BUSINESS_UNIT)
                    .where(BUSINESS_UNIT.REF.eq(ref))
                    .fetchSingle()
                    .value1()
        )!!.toInstant()
    }

private fun <T> runtimeLogin(block: (Connection) -> T): T =
    DriverManager
        .getConnection(BoundaryFixture.container.jdbcUrl, "tenancy_runtime", "runtime-password-for-tests")
        .use(block)

private fun count(
    c: Connection,
    sql: String,
): Long =
    c.createStatement().use { st ->
        st.executeQuery(sql).use {
            it.next()
            it.getLong(1)
        }
    }

private fun flag(
    c: Connection,
    sql: String,
): Boolean =
    c.createStatement().use { st ->
        st.executeQuery(sql).use {
            it.next()
            it.getBoolean(1)
        }
    }

private fun deadline() = Deadline.after(Duration.ofMillis(500), Clock.systemUTC())

private fun envelope(
    tenant: TenantId,
    state: ReplayedState,
) = Envelope(
    EventId(UUID.randomUUID().toString()),
    "/gst/tenancy",
    state.type,
    state.aggregateKey,
    Instant.now(),
    tenant,
    state.entity,
    null,
    null,
    state.docSequence,
    "replay-test",
    CorrelationId("replay-test"),
    CausationId("replay-test"),
    state.dataJson,
)

private fun view(lookup: Lookup<StructureNode>): Any =
    (lookup as? Lookup.Local<StructureNode>)?.value ?: lookup::class.simpleName!!

/**
 * The producer replay sources of tenancy-svc over a real PostgreSQL with forced row-level security (INV-T-9096,
 * INV-Q-11715 A, INV-Q-11717 A, INV-Q-11719 A): the replayed states are compared with the node of
 * `StructureQueries.node(ref)`, the stored `structure_version` and the live events of the same state. The tests carry
 * the key INV-AC-9089 (the replay half of the criterion; the cold-access half is proven in svc-access).
 */
class ReplayStateSourceTest {
    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    private fun exempt(
        from: String,
        value: Boolean,
    ) = SetEntityStatusRequest(
        SetEntityStatusRequest.StatusKind.PARTIAL_EXEMPTION,
        day(from),
        partiallyExempt = value,
        basis = "partial exemption review",
    )

    /**
     * 1 group, 2 live entities, 1 retired entity (made retired by the fixture, `retired` true) and the profile changes
     * given; [block] runs bound to the tenant.
     */
    private fun <T> scenario(
        env: Env,
        changes: List<SetEntityStatusRequest> = listOf(exempt("2026-07-01", true)),
        block: (Scenario) -> T,
    ): T =
        env.profile.withTenant { tenant ->
            env.captured.envelopes.clear()
            val group = env.profile.group(tenant)
            val first = env.profile.entity(tenant, group)
            val second = env.profile.entity(tenant, group)
            val third = env.profile.entity(tenant, group)
            changes.forEach { env.profile.set(first, it) }
            env.profile.inTx { env.retire(tenant, third.entityRef) }
            block(Scenario(tenant, group.groupRef, first.entityRef, second.entityRef, third.entityRef, first))
        }

    @Test
    fun `INV-AC-9089 a full sync replays exactly the current nodes`() {
        run { env ->
            scenario(env) { s ->
                val states = env.states(s.tenant, env.structure)
                assertThat(env.structure.eventType).isEqualTo(STRUCTURE_TYPE)
                val ordered = listOf(s.group) + listOf(s.live1, s.live2, s.retired).sorted()
                assertThat(states.map { it.aggregateKey }).containsExactlyElementsOf(ordered)
                for (state in states) {
                    val ref = state.aggregateKey
                    val replayed = nodeOf(state.dataJson)
                    assertThat(replayed).isEqualTo(env.profile.inTx { env.queries.node(ref)!!.node })
                    assertThat(replayed.structureVersion).isEqualTo(storedVersion(env, ref))
                    assertThat(replayed.structureVersion).isEqualTo(lastLiveVersion(env, ref))
                    assertThat(state.docSequence).isNull()
                    assertThat(state.type).isEqualTo(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED)
                    val payload = json.readTree(state.dataJson)
                    assertThat(payload["nodeRef"].asString()).isEqualTo(ref)
                    val changeType = if (ref == s.retired) "RETIRED" else "UPDATED"
                    assertThat(payload["changeType"].asString()).isEqualTo(changeType)
                    assertThat(payload.has("previousParentRef")).isFalse()
                }
                // the retired entity appears with retired true (the fixture made it so): it is replayed, not dropped
                assertThat(nodeOf(states.single { it.aggregateKey == s.retired }.dataJson).retired).isTrue()
                assertThat(states.count { nodeOf(it.dataJson).retired }).isEqualTo(1)
                assertThat(states.single { it.aggregateKey == s.group }.entity).isEqualTo(EntityRef(s.group))
            }
        }
    }

    @Test
    fun `INV-AC-9089 the profile source replays one state per entity with a status entry`() {
        run { env ->
            scenario(env, listOf(exempt("2026-07-01", true), exempt("2026-03-01", false))) { s ->
                assertThat(env.profiles.eventType).isEqualTo("com.dynokore.gst.tenancy.EntityProfileChanged.v1")
                val states = env.states(s.tenant, env.profiles)
                assertThat(states.map { it.aggregateKey }).containsExactly(s.live1)
                val state = states.single()
                val payload = json.readTree(state.dataJson)
                val node = nodeOf(state.dataJson)
                assertThat(node).isEqualTo(env.profile.inTx { env.queries.node(s.live1)!!.node })
                assertThat(node.profile).hasSize(2)
                val changed = payload["changedAttributes"].toList()
                assertThat(changed.map { it["attribute"].asString() to it["newValue"].asString() })
                    .containsExactlyElementsOf(node.profile.map { it.attribute to it.value })
                assertThat(changed.any { it.has("oldValue") }).isFalse()
                // 2026-03-01 was recorded last although its date is earlier
                assertThat(payload["effectiveDate"].asString()).isEqualTo("2026-03-01")
                assertThat(payload["entityRef"].asString()).isEqualTo(s.live1)
                assertThat(state.entity).isEqualTo(EntityRef(s.live1))
                assertThat(state.docSequence).isNull()
                assertThat(node.structureVersion).isEqualTo(storedVersion(env, s.live1))
                assertThat(node.structureVersion).isEqualTo(lastLiveVersion(env, s.live1))
            }
        }
    }

    @Test
    fun `INV-AC-9089 a replay by tenant scope lists no node of another tenant`() {
        run { env ->
            val look = "Look Alike Pte Ltd"

            fun lookAlike(
                tenant: TenantId,
                group: String,
            ) = env.profile.api
                .createEntity(
                    UUID.randomUUID(),
                    CreateEntityRequest(
                        tenant.value.toString(),
                        group,
                        look,
                        CreateEntityRequest.Constitution.PRIVATE_LIMITED,
                        "U${UUID.randomUUID().toString().take(8).uppercase()}",
                        null,
                        "LOOK1",
                    ),
                ).body!!
            val other =
                env.profile.withTenant { tenant ->
                    val group = env.profile.group(tenant)
                    val entity = lookAlike(tenant, group.groupRef)
                    env.profile.set(entity, exempt("2026-07-01", true))
                    tenant to listOf(group.groupRef, entity.entityRef)
                }
            scenario(env) { s ->
                val mine = lookAlike(s.tenant, s.group)
                for (scope in listOf(null, ReplayScope.Tenant(s.tenant))) {
                    val keys = env.keys(s.tenant, env.structure, scope)
                    assertThat(keys).doesNotContainAnyElementsOf(other.second)
                    assertThat(keys).contains(mine.entityRef)
                    val names = env.states(s.tenant, env.structure, scope).map { nodeOf(it.dataJson).name }
                    assertThat(names.count { it == look }).isEqualTo(1)
                    assertThat(env.keys(s.tenant, env.profiles, scope)).doesNotContainAnyElementsOf(other.second)
                }
                val foreign = ReplayScope.Entity(s.tenant, EntityRef(other.second[1]))
                assertThat(env.keys(s.tenant, env.structure, foreign)).isEmpty()
            }
        }
    }

    @Test
    fun `INV-AC-9089 pages follow each other without duplicates or gaps`() {
        run { env ->
            scenario(env) { s ->
                val whole = env.states(s.tenant, env.structure)
                assertThat(whole).hasSize(4)
                for (limit in listOf(1, 2, 4, 5)) {
                    val pages = env.pages(s.tenant, env.structure, limit = limit)
                    assertThat(pages.flatMap { it.items.map { state -> state.aggregateKey } })
                        .containsExactlyElementsOf(whole.map { it.aggregateKey })
                    assertThat(pages.dropLast(1).map { it.next }).doesNotContainNull()
                    assertThat(pages.last().next).isNull()
                    assertThat(pages).hasSize((4 + limit - 1) / limit)
                    assertThat(pages.all { it.items.size <= limit }).isTrue()
                }
                assertThat(env.pages(s.tenant, env.profiles, limit = 1)).hasSize(1)
                assertThat(env.pages(s.tenant, env.profiles, limit = 1).single().next).isNull()
            }
        }
    }

    @Test
    fun `INV-AC-9089 the entity scope and the period scope select by their definitions`() {
        run { env ->
            scenario(env) { s ->
                val request = RegisterBusinessUnitRequest(s.tenant.value.toString(), s.live1, "Outlet", "BU1")
                val unit =
                    env.profile.api
                        .registerBusinessUnit(UUID.randomUUID(), request)
                        .body!!
                        .businessUnitRef
                val entityScope = ReplayScope.Entity(s.tenant, EntityRef(s.live1))
                assertThat(env.keys(s.tenant, env.structure, entityScope)).containsExactly(s.live1, unit)
                assertThat(env.keys(s.tenant, env.profiles, entityScope)).containsExactly(s.live1)
                val groupScope = ReplayScope.Entity(s.tenant, EntityRef(s.group))
                assertThat(env.keys(s.tenant, env.structure, groupScope)).containsExactly(s.group)
                assertThat(env.keys(s.tenant, env.profiles, ReplayScope.Entity(s.tenant, EntityRef(s.live2)))).isEmpty()

                // the boundaries of the half-open range, with the instants read back from the table
                val at = changedAt(env, unit)
                val hour = Duration.ofHours(1)
                assertThat(env.keys(s.tenant, env.structure, ReplayScope.Period(at, at.plus(hour)))).contains(unit)
                val before = ReplayScope.Period(at.minus(hour), at)
                assertThat(env.keys(s.tenant, env.structure, before)).doesNotContain(unit)

                // each change moves only its own node into a period that starts after the previous change
                fun after(change: () -> Unit): Pair<Set<String>, Set<String>> {
                    val from = (s.refs + unit).maxOf { changedAt(env, it) }.plusNanos(1_000)
                    change()
                    val scope = ReplayScope.Period(from, from.plus(Duration.ofDays(1)))
                    val structure = env.keys(s.tenant, env.structure, scope).toSet()
                    return structure to env.keys(s.tenant, env.profiles, scope).toSet()
                }
                val renamed =
                    after {
                        val update = UpdateBusinessUnitRequest("Outlet 2", "OUT2")
                        env.profile.api.updateBusinessUnit(unit, UUID.randomUUID(), update)
                    }
                assertThat(renamed.first).containsExactly(unit)
                assertThat(renamed.second).isEmpty()
                val profiled = after { env.profile.set(s.firstEntity, exempt("2026-09-01", false)) }
                assertThat(profiled.first).containsExactly(s.live1)
                assertThat(profiled.second).containsExactly(s.live1)
                val recoded = after { env.commands.changeEntityCode(s.tenant, s.live2, "NEWC2") }
                assertThat(recoded.first).containsExactly(s.live2)
                assertThat(recoded.second).isEmpty()
            }
        }
    }

    @Test
    fun `INV-AC-9089 tenants lists every tenant through the definer function only`() {
        run { env ->
            val created =
                List(3) {
                    env.profile.withTenant { tenant -> tenant }
                }
            // the third is the look-alike: created and then retired, it still holds state and is listed
            BoundaryFixture.asSuperuser { c ->
                c.prepareStatement("update tenancy.tenant set retired = true where tenant_id = ?").use {
                    it.setObject(1, created[2].value)
                    check(it.executeUpdate() == 1)
                }
            }
            val all =
                BoundaryFixture.asSuperuser { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("select tenant_id from tenancy.tenant order by tenant_id").use { rs ->
                            val ids = ArrayList<TenantId>()
                            while (rs.next()) ids += TenantId(rs.getObject(1, UUID::class.java))
                            ids
                        }
                    }
                }
            for (source in listOf(env.structure, env.profiles)) {
                assertThat(env.tenantsOf(source)).containsExactlyElementsOf(all)
                assertThat(env.tenantsOf(source)).containsAll(created)
            }
            runtimeLogin { c ->
                // the runtime login without a binding sees no tenant row, whatever the source lists
                assertThat(count(c, "select count(*) from tenancy.tenant")).isZero()
                assertThat(count(c, "select count(*) from tenancy.list_tenant_ids()")).isEqualTo(all.size.toLong())
            }
            BoundaryFixture.asSuperuser { c ->
                val privilege = "select has_function_privilege('%s', 'tenancy.list_tenant_ids()', 'EXECUTE')"
                assertThat(flag(c, privilege.format(Locale.ROOT, "public"))).isFalse()
                assertThat(flag(c, privilege.format(Locale.ROOT, "tenancy_runtime"))).isTrue()
                assertThat(flag(c, "select prosecdef from pg_proc where proname = 'list_tenant_ids'")).isTrue()
            }
        }
    }

    @Test
    fun `INV-AC-9089 replayed into a fresh StructureReplica the membership reads equal those of a live-fed one`() {
        run { env ->
            scenario(env) { s ->
                val live = StructureReplica(Clock.systemUTC())
                val liveFeed = StructureFeedHandler(live)
                env.captured.envelopes
                    .filter { it.tenant == s.tenant }
                    .forEach { liveFeed.handle(it) }
                val replayed = env.states(s.tenant, env.structure).map { envelope(s.tenant, it) }
                val profiles = env.states(s.tenant, env.profiles).map { envelope(s.tenant, it) }
                val orders = listOf(replayed + profiles, profiles + replayed)
                val unknown = "no-such-node-${UUID.randomUUID()}"
                val refs = s.refs + unknown
                for (order in orders) {
                    val fresh = StructureReplica(Clock.systemUTC())
                    val feed = StructureFeedHandler(fresh)
                    order.forEach { feed.handle(it) }
                    val reads = List(20) { refs[it % refs.size] }
                    for (ref in reads) {
                        val expected = view(live.node(s.tenant, ref, deadline()))
                        assertThat(view(fresh.node(s.tenant, ref, deadline()))).isEqualTo(expected)
                    }
                    val present = reads.count { view(fresh.node(s.tenant, it, deadline())) is StructureNode }
                    assertThat(present).isEqualTo(16)
                }
            }
        }
    }

    @Test
    fun `INV-AC-9089 the stamp trigger sets changed_at on insert and update of each of the three tables`() {
        run { env ->
            env.profile.withTenant { tenant ->
                val forced = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                val suffix = UUID.randomUUID().toString().take(8)
                val (group, entity, unit) = Triple(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                val dsl = env.dsl
                val targets =
                    listOf(
                        groupStamp(dsl, tenant, suffix, group),
                        entityStamp(dsl, tenant, suffix, group, entity),
                        unitStamp(dsl, tenant, suffix, entity, unit),
                    )
                for (target in targets) {
                    val beforeInsert = OffsetDateTime.now(ZoneOffset.UTC)
                    env.profile.inTx { target.insert(forced) }
                    val inserted = env.profile.inTx(target.read)
                    assertThat(inserted).describedAs("${target.table} insert").isAfterOrEqualTo(beforeInsert)
                    val beforeUpdate = OffsetDateTime.now(ZoneOffset.UTC)
                    env.profile.inTx { target.update(forced) }
                    val updated = env.profile.inTx(target.read)
                    assertThat(updated).describedAs("${target.table} update").isAfterOrEqualTo(beforeUpdate)
                    assertThat(updated).describedAs("${target.table} moved").isAfter(inserted)
                }
            }
        }
    }

    @Test
    fun `INV-AC-9089 a row seeded before the migration has changed_at at or after its created_at`() {
        val container = BoundaryFixture.container
        val database = "seeded_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        BoundaryFixture.asSuperuser { c -> c.createStatement().use { it.execute("create database $database") } }
        try {
            val url = container.jdbcUrl.replace("/${container.databaseName}", "/$database")
            val connect = { DriverManager.getConnection(url, container.username, container.password) }
            connect().use { c -> c.createStatement().use { it.execute(SeededMigration.bindingFunctions()) } }
            SeededMigration.migrate(url, container.username, container.password, BEFORE)
            connect().use { c -> c.createStatement().use { it.execute(SeededMigration.SEED) } }
            SeededMigration.migrate(url, container.username, container.password, null)
            connect().use { c ->
                for (table in listOf("tenant_group", "entity", "business_unit")) {
                    val sql = "select count(*) from tenancy.$table where changed_at >= created_at"
                    assertThat(count(c, sql)).describedAs(table).isEqualTo(1L)
                }
            }
        } finally {
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { it.execute("drop database if exists $database with (force)") }
            }
        }
    }
}
