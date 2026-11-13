package com.dynokore.gst.consoleBff.adapters.override

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/** INV-AC-21202 (read half): one exception row of the bound tenant by id, through forced row level security. */
class JooqOverrideTargetsTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val pay = UUID.randomUUID()
    private val per = UUID.randomUUID()
    private val resolved = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun insert(
        tenant: UUID,
        id: UUID,
        code: String,
        state: String,
        period: String,
    ) = ConsoleDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into console_bff.exception (tenant_id, exception_id, entity_ref, doc_uuid, code, state, " +
                    "raised_at, remediation_owner, disposition, period_ref, docsequence) " +
                    "values (?, ?, ?, ?, ?, ?, now(), 'OWNER', 'OVERRIDABLE', ?, 1)",
            ).use {
                it.setObject(1, tenant)
                it.setObject(2, id)
                it.setString(3, "entity-$id")
                it.setString(4, "doc-$id")
                it.setString(5, code)
                it.setString(6, state)
                it.setString(7, period)
                it.executeUpdate()
            }
    }

    private fun seed() {
        insert(t1, pay, "PAY-006", "OPEN", "")
        insert(t1, per, "PER-001", "OPEN", "2026-Q3")
        insert(t1, resolved, "PAY-011", "RESOLVED", "")
        insert(t2, other, "PAY-006", "OPEN", "")
    }

    private fun <T> asTenant(
        tenant: UUID,
        block: (JooqOverrideTargets) -> T,
    ): T =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c: Connection ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val result = block(JooqOverrideTargets(DSL.using(c, SQLDialect.POSTGRES)))
            c.commit()
            result
        }

    @Test
    fun `INV-AC-21202 find returns the stored columns of a row of the bound tenant`() {
        seed()
        asTenant(t1) { targets ->
            val a = checkNotNull(targets.find(pay.toString()))
            a.exceptionId shouldBe pay.toString()
            a.entityRef shouldBe "entity-$pay"
            a.docUuid shouldBe "doc-$pay"
            a.periodRef shouldBe ""
            a.code shouldBe "PAY-006"
            a.state shouldBe "OPEN"
            val b = checkNotNull(targets.find(per.toString()))
            b.periodRef shouldBe "2026-Q3"
            b.code shouldBe "PER-001"
            checkNotNull(targets.find(resolved.toString())).state shouldBe "RESOLVED"
        }
    }

    @Test
    fun `INV-AC-21202 find returns null for a row of another tenant and for an unknown id`() {
        seed()
        asTenant(t1) { targets ->
            targets.find(other.toString()) shouldBe null
            targets.find(UUID.randomUUID().toString()) shouldBe null
            targets.find("not-a-uuid") shouldBe null
        }
        asTenant(t2) { targets ->
            targets.find(pay.toString()) shouldBe null
            checkNotNull(targets.find(other.toString())).code shouldBe "PAY-006"
        }
    }

    @Test
    fun `INV-AC-21202 find runs one SELECT and no write per call`() {
        val statements = mutableListOf<String>()
        val provider =
            MockDataProvider { ctx ->
                statements += ctx.sql()
                arrayOf(MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult()))
            }
        JooqOverrideTargets(DSL.using(MockConnection(provider), SQLDialect.POSTGRES)).find(per.toString())
        statements.size shouldBe 1
        val sql = statements.single().trim().lowercase()
        sql.startsWith("select") shouldBe true
        listOf("insert ", "update ", "delete ").none { sql.contains(it) } shouldBe true
        sql.contains("\"exception_id\" = ") shouldBe true
    }
}
