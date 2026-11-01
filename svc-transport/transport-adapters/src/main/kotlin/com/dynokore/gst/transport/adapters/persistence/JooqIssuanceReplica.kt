package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.IssuanceModeReplica.Companion.ISSUANCE_MODE_REPLICA
import com.dynokore.gst.transport.application.IssuanceReplica
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.IssuanceModeEntry
import com.dynokore.gst.transport.domain.IssuanceReplicaRow
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * The issuanceMode replica of the bound tenant: one row per (tenant, entity), replaced whole by [replace] (value and
 * effectiveDate entries, and the structureVersion; nothing else of the profile). Row-level security hides the rows of
 * other tenants, so a row of another tenant's entity with the same entity ref is never read.
 */
class JooqIssuanceReplica(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
) : IssuanceReplica {
    private val mapper = JsonMapper.builder().build()

    override fun find(entity: EntityRef): IssuanceReplicaRow? =
        runner.inTransaction {
            val tenant = tenants.current().value
            dsl
                .select(ISSUANCE_MODE_REPLICA.ENTRIES, ISSUANCE_MODE_REPLICA.STRUCTURE_VERSION)
                .from(ISSUANCE_MODE_REPLICA)
                .where(ISSUANCE_MODE_REPLICA.TENANT_ID.eq(tenant))
                .and(ISSUANCE_MODE_REPLICA.ENTITY_REF.eq(entity.value))
                .fetchOne()
                ?.let { row ->
                    IssuanceReplicaRow(
                        TenantRef(tenant.toString()),
                        mapper.readTree(checkNotNull(row.value1()).data()).values().map {
                            IssuanceModeEntry(
                                it.get("value").asString(),
                                LocalDate.parse(it.get("effectiveDate").asString()),
                            )
                        },
                        checkNotNull(row.value2()),
                    )
                }
        }

    override fun replace(
        entity: EntityRef,
        row: IssuanceReplicaRow,
    ) {
        runner.inTransaction {
            val tenant = tenants.current().value
            require(
                UUID.fromString(row.tenant.value) == tenant,
            ) { "a replica row is written for the bound tenant only" }
            val entries =
                DSL.cast(
                    DSL.`val`(
                        mapper.writeValueAsString(
                            row.entries.map {
                                mapOf(
                                    "value" to it.value,
                                    "effectiveDate" to it.effectiveDate.toString(),
                                )
                            },
                        ),
                    ),
                    SQLDataType.JSONB,
                )
            dsl
                .insertInto(ISSUANCE_MODE_REPLICA)
                .set(ISSUANCE_MODE_REPLICA.TENANT_ID, tenant)
                .set(ISSUANCE_MODE_REPLICA.ENTITY_REF, entity.value)
                .set(ISSUANCE_MODE_REPLICA.ENTRIES, entries)
                .set(ISSUANCE_MODE_REPLICA.STRUCTURE_VERSION, row.structureVersion)
                .onConflict(ISSUANCE_MODE_REPLICA.TENANT_ID, ISSUANCE_MODE_REPLICA.ENTITY_REF)
                .doUpdate()
                .set(ISSUANCE_MODE_REPLICA.ENTRIES, entries)
                .set(ISSUANCE_MODE_REPLICA.STRUCTURE_VERSION, row.structureVersion)
                .execute()
        }
    }
}
