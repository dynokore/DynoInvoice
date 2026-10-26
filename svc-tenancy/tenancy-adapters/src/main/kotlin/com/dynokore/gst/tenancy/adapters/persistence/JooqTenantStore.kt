package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.application.tenant.TenantCreation
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.tenant.TenantRefused
import com.dynokore.gst.tenancy.application.tenant.TenantStore
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.SQLException
import java.util.UUID

/**
 * `tenancy.tenant` is written only by the definer function `tenancy.create_tenant` (INV-Q-7468 A), called through
 * jOOQ `DSL.function`; the function checks the binding and the arguments and answers one jsonb value.
 */
class JooqTenantStore(
    private val dsl: DSLContext,
    private val mapper: ObjectMapper,
) : TenantStore {
    override fun create(tenant: TenantRecord): TenantCreation {
        val call =
            DSL.function(
                DSL.name("tenancy", "create_tenant"),
                SQLDataType.JSONB,
                DSL.`val`(tenant.tenantId, SQLDataType.UUID),
                DSL.`val`(tenant.ref),
                DSL.`val`(tenant.legalName),
                DSL.`val`(tenant.planRef),
                DSL.`val`(tenant.contractReference),
                DSL.`val`(null, SQLDataType.UUID),
                DSL.`val`(tenant.dataRegion, SQLDataType.CLOB),
            )
        val answer: JSONB =
            refusedAsException {
                dsl
                    .select(call)
                    .fetchSingle()
                    .value1()
                    .column("create_tenant")
            }
        val json = mapper.readTree(answer.data())
        return when (val outcome = json.get("outcome").asString()) {
            "created" -> TenantCreation.Created(record(json))
            "repeat" -> TenantCreation.Repeated(record(json))
            "conflict" -> TenantCreation.Conflict
            else -> error("create_tenant answered the unknown outcome $outcome")
        }
    }

    override fun current(): TenantRecord? =
        dsl.selectFrom(TENANT).fetchOne()?.let {
            TenantRecord(
                it.tenantId.column("tenant_id"),
                it.ref.column("ref"),
                it.legalName.column("legal_name"),
                it.planRef.column("plan_ref"),
                it.contractReference.column("contract_reference"),
                it.structureVersion.column("structure_version"),
                it.retired.column("retired"),
                it.dataRegion,
            )
        }

    private fun record(json: JsonNode) =
        TenantRecord(
            UUID.fromString(json.get("tenantId").asString()),
            json.get("ref").asString(),
            json.get("legalName").asString(),
            json.get("planRef").asString(),
            json.get("contractReference").asString(),
            json.get("structureVersion").asLong(),
            dataRegion = json.get("dataRegion")?.asString(),
        )

    /** SQLSTATE 22023 (an argument is refused) and 42501 (the binding is refused) are the function's two refusals. */
    private fun <T> refusedAsException(block: () -> T): T =
        try {
            block()
        } catch (e: org.jooq.exception.DataAccessException) {
            val state =
                generateSequence<Throwable>(
                    e,
                ) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState
            when (state) {
                INVALID -> throw TenantRefused(invalid = true, "create_tenant refused its arguments")
                FORBIDDEN -> throw TenantRefused(invalid = false, "create_tenant refused the binding")
                else -> throw e
            }
        }

    private companion object {
        const val INVALID = "22023"
        const val FORBIDDEN = "42501"
    }
}
