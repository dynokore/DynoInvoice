package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.events.ScopePackReader
import com.dynokore.gst.canonical.adapters.persistence.JooqScopePackReplica
import com.dynokore.gst.canonical.adapters.persistence.ScopePackActivation
import com.dynokore.gst.canonical.adapters.persistence.ScopePackRow
import com.dynokore.gst.canonical.adapters.persistence.StagedScopePack
import com.dynokore.gst.canonical.domain.derivation.UpstreamFunction
import com.dynokore.gst.canonical.domain.derivation.UpstreamFunction.ADJUSTMENT
import com.dynokore.gst.canonical.domain.derivation.UpstreamFunction.BILL_FOR_PAYMENT
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** INV-AC-25429: the scope pack replica over PostgreSQL, as the `canonical_runtime` login (INV-REQ-772). */
class JooqScopePackReplicaTest {
    // The clock date is 2026-11-20 (UTC); every reading advances the instant by a second, so recorded_at is increasing.
    private val clock =
        object : Clock() {
            private var next = Instant.parse("2026-11-20T10:00:00Z")

            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?): Clock = this

            override fun instant(): Instant = next.also { next = next.plusSeconds(1) }
        }
    private val invoice = Integer.parseInt("380")
    private val creditNote = Integer.parseInt("381")
    private val today = LocalDate.of(2026, 11, 20)

    @BeforeEach
    fun clean() {
        CanonicalDb.superuser().use { c ->
            c.createStatement().use {
                it.execute(
                    "truncate canonical.scope_pack_function, canonical.scope_pack_activation, " +
                        "canonical.scope_pack_version",
                )
            }
        }
    }

    private fun replica(block: (JooqScopePackReplica) -> Unit) =
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { c ->
            block(JooqScopePackReplica(DSL.using(c, SQLDialect.POSTGRES), clock))
        }

    private fun count(table: String): Int =
        CanonicalDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("select count(*) from canonical.$table").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun pack(
        id: String = "gst-scope",
        version: String = "1",
        vararg rows: Pair<Int, UpstreamFunction> = arrayOf(invoice to BILL_FOR_PAYMENT, creditNote to ADJUSTMENT),
    ) = StagedScopePack(id, version, rows.map { ScopePackRow(it.first, it.second) }.toSet())

    private fun JooqScopePackReplica.activated(
        id: String,
        version: String,
        from: LocalDate,
    ) = activate(ScopePackActivation(id, version, from))

    @Test
    fun `INV-AC-25429 1 staged and activated on the clock date the pack answers each code`() =
        replica { r ->
            r.stage(pack()) shouldBe true
            r.activated("gst-scope", "1", today) shouldBe true
            r.functionOf(invoice) shouldBe BILL_FOR_PAYMENT
            r.functionOf(creditNote) shouldBe ADJUSTMENT
        }

    @Test
    fun `INV-AC-25429 2 a code absent from the rows answers null`() =
        replica { r ->
            r.stage(pack(rows = arrayOf(invoice to BILL_FOR_PAYMENT)))
            r.activated("gst-scope", "1", today)
            r.functionOf(creditNote) shouldBe null
            r.functionOf(Integer.parseInt("382")) shouldBe null
        }

    @Test
    fun `INV-AC-25429 3 rows that give one code two functions answer null`() =
        replica { r ->
            r.stage(pack(rows = arrayOf(invoice to BILL_FOR_PAYMENT, invoice to ADJUSTMENT, creditNote to ADJUSTMENT)))
            r.activated("gst-scope", "1", today)
            r.functionOf(invoice) shouldBe null
            r.functionOf(creditNote) shouldBe ADJUSTMENT
        }

    @Test
    fun `INV-AC-25429 4 an activation dated after the clock date is not in force`() =
        replica { r ->
            r.stage(pack(version = "1", rows = arrayOf(invoice to BILL_FOR_PAYMENT)))
            r.stage(pack(version = "2", rows = arrayOf(invoice to ADJUSTMENT)))
            r.activated("gst-scope", "1", today.minusDays(30))
            r.activated("gst-scope", "2", today.plusDays(1))
            r.functionOf(invoice) shouldBe BILL_FOR_PAYMENT
            // the greatest date wins, not the latest recorded row: version 2 is recorded last, version 1 is older
            r.activated("gst-scope", "3", today.minusDays(60))
            r.stage(pack(version = "3", rows = arrayOf(invoice to ADJUSTMENT)))
            r.functionOf(invoice) shouldBe BILL_FOR_PAYMENT
        }

    @Test
    fun `INV-AC-25429 5 no activation answers null for every code`() =
        replica { r ->
            r.stage(pack())
            r.functionOf(invoice) shouldBe null
            r.functionOf(creditNote) shouldBe null
        }

    @Test
    fun `INV-AC-25429 6 activations of two pack identifiers on the same greatest date answer null`() =
        replica { r ->
            r.stage(pack(id = "pack-a"))
            r.stage(pack(id = "pack-b"))
            r.activated("pack-a", "1", today)
            r.functionOf(invoice) shouldBe BILL_FOR_PAYMENT
            r.activated("pack-b", "1", today)
            r.functionOf(invoice) shouldBe null
        }

    @Test
    fun `INV-AC-25429 6 activations of two versions of one pack identifier on the same greatest date answer null`() =
        replica { r ->
            r.stage(pack(version = "1.0.0", rows = arrayOf(invoice to BILL_FOR_PAYMENT)))
            r.stage(pack(version = "1.1.0", rows = arrayOf(invoice to ADJUSTMENT)))
            r.activated("gst-scope", "1.0.0", today)
            r.functionOf(invoice) shouldBe BILL_FOR_PAYMENT
            r.activated("gst-scope", "1.1.0", today)
            r.functionOf(invoice) shouldBe null
        }

    @Test
    fun `INV-AC-25429 7 a pack of another type leaves the tables empty and a redelivered version changes nothing`() =
        replica { r ->
            val reader = ScopePackReader()
            val calendar =
                "{\"packIdentifier\":\"gst-calendar\",\"packType\":\"CALENDAR\",\"version\":\"1\"," +
                    "\"artefact\":{\"contents\":{\"holidays\":[]}}}"
            reader.readStaged(calendar, TenantId.PLATFORM) shouldBe null
            count("scope_pack_version") shouldBe 0
            count("scope_pack_function") shouldBe 0
            count("scope_pack_activation") shouldBe 0

            r.stage(pack()) shouldBe true
            r.stage(pack(rows = arrayOf(invoice to ADJUSTMENT))) shouldBe false
            count("scope_pack_version") shouldBe 1
            count("scope_pack_function") shouldBe 2
            r.activated("gst-scope", "1", today) shouldBe true
            r.activated("gst-scope", "1", today.minusDays(1)) shouldBe false
            count("scope_pack_activation") shouldBe 1
            r.functionOf(invoice) shouldBe BILL_FOR_PAYMENT
        }

    @Test
    fun `INV-AC-25429 8 an activation that arrives before its staged event counts only after the staged row exists`() =
        replica { r ->
            r.activated("gst-scope", "2", today) shouldBe true
            r.stage(pack(version = "1", rows = arrayOf(invoice to BILL_FOR_PAYMENT)))
            r.activated("gst-scope", "1", today.minusDays(10))
            // version 2 is activated and later, but not staged: version 1 stays in force
            r.functionOf(invoice) shouldBe BILL_FOR_PAYMENT
            r.stage(pack(version = "2", rows = arrayOf(invoice to ADJUSTMENT)))
            r.functionOf(invoice) shouldBe ADJUSTMENT
        }
}
