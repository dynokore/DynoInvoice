package com.dynokore.gst.consoleBff.adapters.deferral

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.util.UUID

/** INV-AC-21162 (database half): open document and group targets for the bound tenant, through forced RLS. */
class DeferralTargetsDbTest {
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val d1 = UUID.randomUUID().toString()
    private val d2 = UUID.randomUUID().toString()
    private val d3 = UUID.randomUUID().toString()
    private val d4 = UUID.randomUUID().toString()
    private val d5 = UUID.randomUUID().toString()

    @Suppress("LongParameterList") // a fixture row names each stored column it varies
    private fun insert(
        tenant: UUID,
        entity: String,
        doc: String,
        code: String,
        state: String,
        owner: String,
    ) = ConsoleDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into console_bff.exception (tenant_id, exception_id, entity_ref, doc_uuid, code, state, " +
                    "raised_at, remediation_owner, disposition, period_ref, docsequence) " +
                    "values (?, ?, ?, ?, ?, ?, now(), ?, 'OVERRIDABLE', '', 1)",
            ).use {
                it.setObject(1, tenant)
                it.setObject(2, UUID.randomUUID())
                it.setString(3, entity)
                it.setString(4, doc)
                it.setString(5, code)
                it.setString(6, state)
                it.setString(7, owner)
                it.executeUpdate()
            }
    }

    private fun seed() {
        insert(tenantA, "E1", d1, "PAY-006", "OPEN", "owner-a")
        insert(tenantA, "E1", d2, "PAY-011", "RESOLVED", "owner-a")
        insert(tenantA, "E1", d3, "PAY-012", "OVERRIDDEN", "owner-a")
        insert(tenantA, "E2", d4, "PAY-006", "OPEN", "owner-a")
        insert(tenantB, "E1", d5, "PAY-006", "OPEN", "owner-b")
    }

    private fun <T> asTenant(
        tenant: UUID,
        block: (JooqDeferralTargets) -> T,
    ): T =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val result = block(JooqDeferralTargets(DSL.using(c, SQLDialect.POSTGRES)))
            c.commit()
            result
        }

    @Test
    fun `INV-AC-21162 openDocument is true for an OPEN document of the entity`() {
        seed()
        asTenant(tenantA) { it.openDocument("E1", d1) shouldBe true }
    }

    @Test
    fun `INV-AC-21162 openDocument is false for resolved, overridden, other entity, other tenant, absent`() {
        seed()
        asTenant(tenantA) {
            it.openDocument("E1", d2) shouldBe false
            it.openDocument("E1", d3) shouldBe false
            it.openDocument("E1", d4) shouldBe false
            it.openDocument("E1", d5) shouldBe false
            it.openDocument("E1", UUID.randomUUID().toString()) shouldBe false
        }
        asTenant(tenantB) { it.openDocument("E1", d5) shouldBe true }
    }

    @Test
    fun `INV-AC-21162 openGroup is true for the whole key owner slash code of an open row`() {
        seed()
        asTenant(tenantA) { it.openGroup("E1", "owner-a/PAY-006") shouldBe true }
    }

    @Test
    fun `INV-AC-21162 openGroup is false for another owner, another code, a key without slash and a resolved group`() {
        seed()
        asTenant(tenantA) {
            it.openGroup("E1", "owner-b/PAY-006") shouldBe false
            it.openGroup("E1", "owner-a/PAY-011") shouldBe false
            it.openGroup("E1", "owner-a") shouldBe false
            it.openGroup("E2", "owner-a/PAY-011") shouldBe false
        }
    }

    @Test
    fun `INV-AC-21162 no match is inferred from a prefix`() {
        seed()
        asTenant(tenantA) {
            it.openGroup("E1", "owner-a/PAY") shouldBe false
            it.openGroup("E1", "owner-a/PAY-0") shouldBe false
            it.openGroup("E1", "owner-") shouldBe false
            it.openDocument("E1", d1.substring(0, 8)) shouldBe false
            it.openGroup("E1", "owner-a/%") shouldBe false
        }
    }
}
