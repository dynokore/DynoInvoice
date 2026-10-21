package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.persistence.JooqCapabilityRegistry
import com.dynokore.gst.payload.application.ActiveMdePack
import com.dynokore.gst.payload.application.BindingRowFacts
import com.dynokore.gst.payload.application.CapabilityService
import com.dynokore.gst.payload.application.MdePackSource
import com.dynokore.gst.payload.domain.CapabilityKey
import com.dynokore.gst.payload.domain.CapabilityState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The serialisation guard (INV-REQ-481) at the database, over the `payload_runtime` login: the capability registry of
 * schema `payload` and `markSpecified(SERIALISATION)` over a fixture [MdePackSource]. The state is reset through the
 * superuser connection before each case, so the cases do not depend on their order.
 */
class CapabilityGuardTest {
    private val key = CapabilityKey.SERIALISATION

    private fun superuser(sql: String) = PayloadDb.superuser().use { it.createStatement().use { s -> s.execute(sql) } }

    private fun reset() = superuser("update payload.capability set state = 'NOT_SPECIFIED'")

    private fun scratchSchema(): String = "scratch_" + UUID.randomUUID().toString().replace("-", "")

    private fun fixture(pack: ActiveMdePack?): MdePackSource {
        var calls = 0
        return object : MdePackSource {
            override fun active(): ActiveMdePack? {
                calls += 1
                check(calls == 1) { "active() must be called once per markSpecified" }
                return pack
            }
        }
    }

    private fun row(
        term: String? = "BT-1",
        status: String? = "MANDATORY",
        vararg cardinalities: String? = arrayOf("1..1"),
    ) = BindingRowFacts(term, status, cardinalities.toList())

    private fun pack(vararg rows: BindingRowFacts) = ActiveMdePack("1.0.0", rows.toList())

    /** Runs [block] in one transaction of the `payload_runtime` login bound to [tenant] with a signed binding. */
    private fun <T> bound(
        tenant: UUID,
        block: (JooqCapabilityRegistry) -> T,
    ): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            // commit even when the block throws: a write made before a refusal must not be rolled back out of sight
            try {
                block(JooqCapabilityRegistry(DSL.using(connection, SQLDialect.POSTGRES)))
            } finally {
                connection.commit()
            }
        }

    private fun state(): CapabilityState = bound(UUID.randomUUID()) { it.state(key) }

    private fun refused(source: MdePackSource) {
        val failure =
            shouldThrow<IllegalStateException> {
                bound(TenantId.PLATFORM.value) { CapabilityService(it, source).markSpecified(key) }
            }
        failure.message shouldContain "MDE pack (packType mde)"
        state() shouldBe CapabilityState.NOT_SPECIFIED
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
    fun `INV-AC-7842 markSpecified is refused when no MDE pack is active`() {
        reset()
        refused(fixture(null))
    }

    @Test
    fun `INV-AC-7842 markSpecified is refused for an active pack with no row`() {
        reset()
        refused(fixture(pack()))
    }

    @Test
    fun `INV-AC-7842 a blank or whitespace-only Business Term ID or IRAS status makes the row incomplete`() {
        reset()
        listOf(null, "", "   ").forEach { blank ->
            refused(fixture(pack(row(), row(term = blank))))
            refused(fixture(pack(row(), row(status = blank))))
        }
    }

    @Test
    fun `INV-AC-7842 an empty list of specification cardinalities makes the row incomplete`() {
        reset()
        refused(fixture(pack(row(), row(cardinalities = emptyArray()))))
    }

    @Test
    fun `INV-AC-7842 a blank cardinality of three spaces or a blank second cardinality makes the row incomplete`() {
        reset()
        refused(fixture(pack(row(cardinalities = arrayOf("   ")))))
        refused(fixture(pack(row(cardinalities = arrayOf("1..1", "   ")))))
        refused(fixture(pack(row(cardinalities = arrayOf("1..1", null)))))
        refused(fixture(pack(row(cardinalities = arrayOf("1..1", "")))))
    }

    @Test
    fun `INV-AC-7842 a look-alike table mde_binding_tables in schema payload leaves the refusal unchanged`() {
        reset()
        superuser("create table payload.mde_binding_tables (business_term_id text, iras_mde_status text)")
        try {
            superuser("insert into payload.mde_binding_tables values ('BT-1', 'MANDATORY')")
            refused(fixture(null))
            refused(fixture(pack()))
        } finally {
            superuser("drop table payload.mde_binding_tables")
        }
    }

    @Test
    fun `INV-AC-7843 markSpecified succeeds and sets SPECIFIED when every row is complete`() {
        reset()
        val complete = pack(row(), row(term = "BT-2", status = "NOT_MDE", cardinalities = arrayOf("0..1", "0..n")))
        bound(TenantId.PLATFORM.value) { registry ->
            registry.state(key) shouldBe CapabilityState.NOT_SPECIFIED
            CapabilityService(registry, fixture(complete)).markSpecified(key)
        }
        state() shouldBe CapabilityState.SPECIFIED
        reset()
        // the refusals still hold in the same database
        refused(fixture(null))
        refused(fixture(pack(row(), row(cardinalities = arrayOf("1..1", " ")))))
    }

    @Test
    fun `INV-AC-7843 a session bound to tenant A cannot change the state of capability`() {
        reset()
        val tenantA = UUID.randomUUID()
        shouldThrow<DataAccessException> { bound(tenantA) { it.markSpecified(key) } }
        state() shouldBe CapabilityState.NOT_SPECIFIED
        // and the runtime login holds no UPDATE of its own
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                val failure =
                    shouldThrow<java.sql.SQLException> {
                        statement.execute("update payload.capability set state = 'SPECIFIED'")
                    }
                failure.message shouldContain "permission denied"
            }
        }
        state() shouldBe CapabilityState.NOT_SPECIFIED
    }
}
