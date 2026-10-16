package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * The statements of [JdbcConfigPurge] and [JdbcResolutionRecorder] as tenancy-jooq's DSLContext renders them (INV-T-5303),
 * against jOOQ's mock JDBC driver: the real tenancy listeners accept every statement (a refused one throws), every
 * identifier is a quoted `DSL.name` of the schema and every value a bind value. The database behaviour is the
 * `*PostgresIT` classes'.
 */
class ConfigJooqStatementsTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val statements = mutableListOf<Pair<String, List<Any?>>>()
    private val tenants = TenantContextImpl()
    private val create = DSL.using(SQLDialect.POSTGRES)
    private val runner: MockRunner
    private val dsl: DSLContext

    init {
        val provider =
            MockDataProvider { call ->
                val sql = call.sql()
                if (!sql.startsWith("select set_config") &&
                    !sql.startsWith("RESET")
                ) {
                    statements += sql.replace(Regex("\\s+"), " ").trim() to call.bindings().toList()
                }
                when {
                    sql.startsWith("insert") -> {
                        arrayOf(MockResult(1))
                    }

                    sql.contains("config_purge_subject") -> {
                        rows(listOf(3L), "n")
                    }

                    sql.contains("config_purge_tenant") -> {
                        composite("(4,5,6)")
                    }

                    sql.startsWith("select") -> {
                        arrayOf(MockResult(0, create.newResult(*recordedFields())))
                    }

                    else -> {
                        arrayOf(MockResult(0))
                    }
                }
            }
        // no database: the runner's current connection is jOOQ's mock connection, so only the statements are proved
        runner = MockRunner(MockConnection(provider))
        dsl = TenancyJooq.dslContext(ConfigTables.declared("svc"), tenants, runner)
    }

    /** The eleven columns the recorder selects, in no row. */
    private fun recordedFields() =
        listOf(
            "vp_key",
            "transaction_date",
            "option_keys",
            "option_values",
            "source",
            "source_level",
            "source_scope_id",
            "source_setting_id",
            "profile_id",
            "profile_version",
            "capped_by",
        ).map { DSL.field(DSL.name(it), SQLDataType.CLOB) }.toTypedArray()

    /** One row of one record-typed column, as PostgreSQL returns a function that has a composite result. */
    private fun composite(text: String): Array<MockResult> {
        val field = DSL.field(DSL.name("purge_tenant"), SQLDataType.CLOB)
        val result = create.newResult(field)
        result.add(create.newRecord(field).also { it.set(field, text) })
        return arrayOf(MockResult(1, result))
    }

    /** One result row of bigint columns [names] holding [values]. */
    private fun rows(
        values: List<Long>,
        vararg names: String,
    ): Array<MockResult> {
        val result = create.newResult(*names.map { DSL.field(DSL.name(it), SQLDataType.BIGINT) }.toTypedArray())
        result.add(create.newRecord(*result.fields()).also { it.fromArray(*values.toTypedArray()) })
        return arrayOf(MockResult(1, result))
    }

    private fun <T> inTenant(block: () -> T): T = tenants.bind(tenant, "user-1") { runner.inTransaction(block) }

    @Test
    fun `the purge calls both functions as quoted routine calls with bind values`() {
        val purge = JdbcConfigPurge(dsl, "svc")
        assertEquals(3, inTenant { purge.purgeSubject(ResolutionSubject("submission", "doc-1/v1")) })
        assertEquals(PurgedTenant(4, 5, 6), inTenant { purge.purgeTenant(tenant) })
        assertEquals("""select "svc"."config_purge_subject"(?, ?)""", statements[0].first)
        assertEquals(listOf<Any?>("submission", "doc-1/v1"), statements[0].second)
        val purgeTenant = statements[1].first
        assertTrue(purgeTenant.contains(""""svc"."config_purge_tenant"(cast(? as uuid))"""), purgeTenant)
        assertEquals(listOf<Any?>(tenant.value), statements[1].second)
    }

    @Test
    fun `the recorder inserts with bind values and reads under the framework tenant predicate`() {
        val recorder = JdbcResolutionRecorder(dsl, "svc", ConfigHarness.CLOCK)
        val subject = ResolutionSubject("submission", "doc-1/v1")
        val resolved =
            ResolvedSetting(
                VariationPointKey("VP-11"),
                LocalDate.of(2026, 3, 15),
                listOf(SelectedOption("A", """{"hours":720}""")),
                PolicySource.CatalogueDefault,
            )
        inTenant { runCatching { recorder.record(subject, resolved, PolicyScope.tenant(tenant)) } }
        inTenant { recorder.recorded(subject) }
        val insert = statements[0].first
        assertTrue(insert.startsWith("""insert into "svc"."config_resolved_value" ("""), insert)
        val conflict = """on conflict ("tenant_id", "subject_type", "subject_ref", "vp_key") do nothing"""
        assertTrue(insert.contains(conflict), insert)
        assertEquals(17, statements[0].second.size)
        assertEquals(tenant.value, statements[0].second[1])
        val read = statements.last().first
        // the predicate tenancy-jooq injects, as a bind value
        assertTrue(read.contains(""""config_resolved_value"."tenant_id" = cast(? as uuid)"""), read)
        assertEquals(listOf<Any?>("submission", "doc-1/v1", tenant.value).toSet(), statements.last().second.toSet())
    }
}

/** A runner whose open transaction is one fixed connection (tenancy-jooq needs a runner that is a [CurrentConnection]). */
private class MockRunner(
    private val connection: Connection,
) : TransactionRunner,
    CurrentConnection {
    override fun <T> inTransaction(block: () -> T): T = block()

    override fun require(): Connection = connection
}
