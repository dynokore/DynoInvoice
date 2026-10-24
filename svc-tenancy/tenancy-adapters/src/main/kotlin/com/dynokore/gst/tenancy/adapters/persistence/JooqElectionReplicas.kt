package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ACCESS_POINT_ROUTE
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SCOPE_PACK_ACTIVATION
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SCOPE_PACK_VERSION
import com.dynokore.gst.tenancy.application.election.AccessPointRouteStore
import com.dynokore.gst.tenancy.application.election.ApprovedSource
import com.dynokore.gst.tenancy.application.election.RouteLookup
import com.dynokore.gst.tenancy.application.election.RouteUpdate
import com.dynokore.gst.tenancy.application.election.ScopePackStore
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The route replica (jOOQ only, CS-18): one row per Access Point connection; forced RLS hides other tenants' rows. */
class JooqAccessPointRouteStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : AccessPointRouteStore {
    override fun apply(update: RouteUpdate): Boolean {
        val entityRefs: Array<String?> = update.entityRefs.toTypedArray<String?>()
        val recordedAt = Instant.now().atOffset(ZoneOffset.UTC)
        return dsl
            .insertInto(ACCESS_POINT_ROUTE)
            .set(ACCESS_POINT_ROUTE.TENANT_ID, tenants.current().value)
            .set(ACCESS_POINT_ROUTE.CONNECTION_REF, update.connectionRef)
            .set(ACCESS_POINT_ROUTE.ENTITY_REFS, entityRefs)
            .set(ACCESS_POINT_ROUTE.ROUTE, update.route)
            .set(ACCESS_POINT_ROUTE.VERSION, update.version)
            .set(ACCESS_POINT_ROUTE.RECORDED_AT, recordedAt)
            .onConflict(ACCESS_POINT_ROUTE.TENANT_ID, ACCESS_POINT_ROUTE.CONNECTION_REF)
            .doUpdate()
            .set(ACCESS_POINT_ROUTE.ENTITY_REFS, entityRefs)
            .set(ACCESS_POINT_ROUTE.ROUTE, update.route)
            .set(ACCESS_POINT_ROUTE.VERSION, update.version)
            .set(ACCESS_POINT_ROUTE.RECORDED_AT, recordedAt)
            .where(ACCESS_POINT_ROUTE.VERSION.lt(update.version))
            .execute() > 0
    }

    override fun lookup(entityRef: String): RouteLookup {
        val rows =
            dsl
                .selectFrom(ACCESS_POINT_ROUTE)
                .where(ACCESS_POINT_ROUTE.TENANT_ID.eq(tenants.current().value))
                .and(ACCESS_POINT_ROUTE.ENTITY_REFS.contains(arrayOf<String?>(entityRef)))
                .orderBy(ACCESS_POINT_ROUTE.CONNECTION_REF)
                .fetch()
        val elected = rows.firstOrNull { it.route != null }
        return when {
            rows.isEmpty() -> {
                RouteLookup.NoConnection
            }

            elected != null -> {
                RouteLookup.Elected(elected.connectionRef.column("connection_ref"), elected.route.column("route"))
            }

            else -> {
                RouteLookup.NoRoute(rows.first().connectionRef.column("connection_ref"))
            }
        }
    }
}

/** The scope pack replica: approved exchange-rate sources per staged version and the activations (INV-Q-9698). */
class JooqScopePackStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) : ScopePackStore {
    override fun stage(
        packIdentifier: String,
        version: String,
        sources: List<ApprovedSource>,
        recordedAt: Instant,
    ): Boolean {
        val json =
            mapper.writeValueAsString(
                sources.map { mapOf("sourceId" to it.sourceId, "bases" to it.bases.sorted()) },
            )
        return dsl
            .insertInto(SCOPE_PACK_VERSION)
            .set(SCOPE_PACK_VERSION.TENANT_ID, tenants.current().value)
            .set(SCOPE_PACK_VERSION.PACK_IDENTIFIER, packIdentifier)
            .set(SCOPE_PACK_VERSION.PACK_VERSION, version)
            .set(SCOPE_PACK_VERSION.EXCHANGE_RATE_SOURCES, json)
            .set(SCOPE_PACK_VERSION.RECORDED_AT, recordedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() > 0
    }

    override fun activate(
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
        recordedAt: Instant,
    ): Boolean {
        val latest =
            dsl
                .select(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM)
                .from(SCOPE_PACK_ACTIVATION)
                .where(SCOPE_PACK_ACTIVATION.TENANT_ID.eq(tenants.current().value))
                .and(SCOPE_PACK_ACTIVATION.PACK_IDENTIFIER.eq(packIdentifier))
                .orderBy(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM.desc())
                .limit(1)
                .fetchOne()
                ?.value1()
        if (latest != null && !effectiveFrom.isAfter(latest)) return false
        return dsl
            .insertInto(SCOPE_PACK_ACTIVATION)
            .set(SCOPE_PACK_ACTIVATION.TENANT_ID, tenants.current().value)
            .set(SCOPE_PACK_ACTIVATION.PACK_IDENTIFIER, packIdentifier)
            .set(SCOPE_PACK_ACTIVATION.PACK_VERSION, version)
            .set(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM, effectiveFrom)
            .set(SCOPE_PACK_ACTIVATION.RECORDED_AT, recordedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() > 0
    }

    override fun approvedOn(on: LocalDate): List<ApprovedSource>? {
        val tenant = tenants.current().value
        val activations =
            dsl
                .selectFrom(SCOPE_PACK_ACTIVATION)
                .where(SCOPE_PACK_ACTIVATION.TENANT_ID.eq(tenant))
                .and(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM.le(on))
                .orderBy(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM.desc())
                .fetch()
        val active = activations.distinctBy { it.packIdentifier }
        val lists =
            active.mapNotNull { activation ->
                dsl
                    .select(SCOPE_PACK_VERSION.EXCHANGE_RATE_SOURCES)
                    .from(SCOPE_PACK_VERSION)
                    .where(SCOPE_PACK_VERSION.TENANT_ID.eq(tenant))
                    .and(SCOPE_PACK_VERSION.PACK_IDENTIFIER.eq(activation.packIdentifier))
                    .and(SCOPE_PACK_VERSION.PACK_VERSION.eq(activation.packVersion))
                    .fetchOne()
                    ?.value1()
            }
        if (lists.isEmpty()) return null
        return lists.flatMap { json ->
            mapper.readTree(json).values().map { node ->
                ApprovedSource(node["sourceId"].asString(), node["bases"].values().map { it.asString() }.toSet())
            }
        }
    }
}
