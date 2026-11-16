package com.dynokore.gst.payload.adapters.packs

import com.dynokore.gst.payload.adapters.PayloadDb
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * INV-AC-23580 against the real database. Rows are seeded and read as the superuser, which row security does not
 * filter, so only the tenant predicate of the source separates the tenants.
 */
class JooqMdePackSourceDbTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()

    private fun seed(
        tenant: UUID,
        version: String,
        contents: String,
        active: Boolean,
    ) = PayloadDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into payload.mde_pack_replica(tenant_id, pack_identifier, pack_version, contents, active) " +
                    "values (?, 'mde-core', ?, ?::jsonb, ?)",
            ).use {
                it.setObject(1, tenant)
                it.setString(2, version)
                it.setString(3, contents)
                it.setBoolean(4, active)
                it.executeUpdate()
            }
    }

    private fun read(tenant: UUID) =
        PayloadDb.superuser().use { c -> JooqMdePackSource(DSL.using(c, SQLDialect.POSTGRES)) { tenant }.active() }

    private fun stored(
        tenant: UUID,
        version: String,
    ): String =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select contents::text from payload.mde_pack_replica where tenant_id = ? and pack_version = ?",
                ).use {
                    it.setObject(1, tenant)
                    it.setString(2, version)
                    it.executeQuery().use { r ->
                        r.next()
                        r.getString(1)
                    }
                }
        }

    @Test
    fun `INV-AC-23580 active row gives version and contents`() {
        seed(t1, "2.0.0", """{"rows": [1, 2]}""", true)
        val pack = read(t1)!!
        pack.version shouldBe "2.0.0"
        pack.contents shouldBe stored(t1, "2.0.0")
        pack.contents.isEmpty() shouldBe false
    }

    @Test
    fun `INV-AC-23580 no active row gives null`() {
        seed(t1, "1.0.0", """{"a": 1}""", false)
        read(t1) shouldBe null
        read(UUID.randomUUID()) shouldBe null
    }

    @Test
    fun `INV-AC-23580 inactive version is not read`() {
        seed(t1, "1.0.0", """{"old": true}""", false)
        seed(t1, "2.0.0", """{"new": true}""", true)
        val pack = read(t1)!!
        pack.version shouldBe "2.0.0"
        pack.contents shouldBe stored(t1, "2.0.0")
    }

    @Test
    fun `INV-AC-23580 other tenant row is not read`() {
        seed(t1, "1.0.0", """{"t": 1}""", true)
        seed(t2, "9.0.0", """{"t": 2}""", true)
        read(t1)!!.version shouldBe "1.0.0"
        read(t2)!!.version shouldBe "9.0.0"
        read(t2)!!.contents shouldBe stored(t2, "9.0.0")
    }
}
