package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.persistence.JooqBindingTableInspector
import com.dynokore.gst.payload.adapters.persistence.JooqCapabilityRegistry
import com.dynokore.gst.payload.application.CapabilityService
import com.dynokore.gst.payload.domain.BindingTableRef
import com.dynokore.gst.payload.domain.CapabilityKey
import com.dynokore.gst.payload.domain.CapabilityState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/**
 * The serialisation guard (INV-REQ-481) at the database, over the `payload_runtime` login: the capability registry of
 * schema `payload` and `markSpecified(SERIALISATION)` against scratch binding tables. The registry state is reset
 * through the superuser connection before each case, so the cases do not depend on their order.
 */
class CapabilityGuardTest {
    private val key = CapabilityKey.SERIALISATION

    private fun superuser(sql: String) = PayloadDb.superuser().use { it.createStatement().use { s -> s.execute(sql) } }

    private fun reset() = superuser("update payload.capability set state = 'NOT_SPECIFIED'")

    private fun scratchSchema(): String = "scratch_" + UUID.randomUUID().toString().replace("-", "")

    private fun ref(
        schema: String,
        table: String = "mde_binding_table",
    ) = BindingTableRef(schema, table, "business_term_id", "pint_sg_cardinality", "iras_mde_status")

    private fun createBindingTable(
        schema: String,
        table: String,
        vararg rows: String,
    ) {
        superuser("create schema $schema")
        superuser(
            "create table $schema.$table (business_term_id text, pint_sg_cardinality text, iras_mde_status text)",
        )
        rows.forEach { superuser("insert into $schema.$table values $it") }
        superuser("grant usage on schema $schema to ${PayloadDb.RUNTIME}")
        superuser("grant select on $schema.$table to ${PayloadDb.RUNTIME}")
    }

    private fun <T> service(
        table: BindingTableRef,
        block: (CapabilityService, JooqCapabilityRegistry) -> T,
    ): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { connection: Connection ->
            val dsl = DSL.using(connection, SQLDialect.POSTGRES)
            val registry = JooqCapabilityRegistry(dsl)
            block(CapabilityService(registry, JooqBindingTableInspector(dsl), table), registry)
        }

    private fun refused(
        table: BindingTableRef,
        expectedInMessage: String? = null,
    ) = service(table) { guard, registry ->
        val failure = shouldThrow<IllegalStateException> { guard.markSpecified(key) }
        expectedInMessage?.let { failure.message shouldContain it }
        registry.state(key) shouldBe CapabilityState.NOT_SPECIFIED
    }

    @Test
    fun `INV-AC-7841 the capability table of schema payload holds SERIALISATION in state NOT_SPECIFIED`() {
        reset()
        // look-alike: a `capability` table in another schema holding a different state does not satisfy the test
        val other = scratchSchema()
        superuser("create schema $other")
        superuser("create table $other.capability (key text, state text)")
        superuser("insert into $other.capability values ('SERIALISATION', 'SPECIFIED')")
        PayloadDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select table_schema, table_name, key, state from information_schema.tables t, " +
                        "lateral (select key, state from payload.capability) c " +
                        "where t.table_schema = 'payload' and t.table_name = 'capability'",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next() shouldBe true
                        rows.getString(1) shouldBe "payload"
                        rows.getString(2) shouldBe "capability"
                        rows.getString(3) shouldBe "SERIALISATION"
                        rows.getString(4) shouldBe "NOT_SPECIFIED"
                        rows.next() shouldBe false
                    }
                }
        }
    }

    @Test
    fun `INV-AC-7842 markSpecified is refused naming the missing table when no binding table exists`() {
        reset()
        refused(ref(scratchSchema()), "does not exist")
    }

    @Test
    fun `INV-AC-7842 markSpecified is refused for a binding table with one complete and one incomplete row`() {
        reset()
        val schema = scratchSchema()
        createBindingTable(schema, "mde_binding_table", "('BT-1', '1..1', 'MANDATORY')", "('BT-2', '0..1', null)")
        refused(ref(schema), "1 row(s)")
    }

    @Test
    fun `INV-AC-7842 a blank cardinality makes the row incomplete`() {
        reset()
        val schema = scratchSchema()
        createBindingTable(schema, "mde_binding_table", "('BT-1', '1..1', 'MANDATORY')", "('BT-2', '  ', 'MANDATORY')")
        refused(ref(schema))
    }

    @Test
    fun `INV-AC-7842 a row missing its Business Term ID or its IRAS MDE status is incomplete`() {
        reset()
        val schema = scratchSchema()
        createBindingTable(schema, "mde_binding_table", "(null, '1..1', 'MANDATORY')")
        refused(ref(schema))
        val second = scratchSchema()
        createBindingTable(second, "mde_binding_table", "('BT-1', '1..1', '')")
        refused(ref(second))
    }

    @Test
    fun `INV-AC-7842 a look-alike table name does not satisfy the guard`() {
        reset()
        val schema = scratchSchema()
        createBindingTable(schema, "mde_binding_tables", "('BT-1', '1..1', 'MANDATORY')")
        refused(ref(schema), "does not exist")
    }

    @Test
    fun `INV-AC-7842 an empty binding table does not satisfy the guard`() {
        reset()
        val schema = scratchSchema()
        createBindingTable(schema, "mde_binding_table")
        refused(ref(schema), "holds no row")
    }

    @Test
    fun `INV-AC-7843 markSpecified succeeds and changes the state only when every row is complete`() {
        reset()
        val schema = scratchSchema()
        createBindingTable(schema, "mde_binding_table", "('BT-1', '1..1', 'MANDATORY')", "('BT-2', '0..1', 'NOT_MDE')")
        service(ref(schema)) { guard, registry ->
            registry.state(key) shouldBe CapabilityState.NOT_SPECIFIED
            guard.markSpecified(key)
            registry.state(key) shouldBe CapabilityState.SPECIFIED
        }
        reset()
    }
}
