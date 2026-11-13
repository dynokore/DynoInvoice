package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.persistence.JooqLatestDocumentVersions
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.application.version.LatestVersionFacts
import com.dynokore.gst.payload.domain.version.VersionFacts
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** The latest-version store against the real database as `payload_runtime` with a signed binding (INV-AC-21135). */
class JooqLatestDocumentVersionsTest {
    private val t = UUID.randomUUID()
    private val u = UUID.randomUUID()
    private val d = UUID.randomUUID()
    private val y = UUID.randomUUID()

    private fun <T> bound(
        tenant: UUID,
        block: (JooqVersionFactsStore, JooqLatestDocumentVersions) -> T,
    ): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                val dsl = DSL.using(c, SQLDialect.POSTGRES)
                block(JooqVersionFactsStore(dsl) { tenant }, JooqLatestDocumentVersions(dsl) { tenant })
            } finally {
                c.commit()
            }
        }

    private fun record(
        tenant: UUID,
        doc: UUID,
        version: Int,
        total: BigDecimal? = BigDecimal("10.00"),
    ) = bound(tenant) { facts, _ -> facts.record(doc, version, VersionFacts("E-$version", total)) }

    private fun latest(
        tenant: UUID,
        doc: UUID,
    ) = bound(tenant) { _, store -> store.latest(doc) }

    @Test
    fun `INV-AC-21135 the maximum version is answered for rows 1, 2, 4`() {
        listOf(1, 2, 4).forEach { record(t, d, it) }
        latest(t, d) shouldBe LatestVersionFacts(4, "E-4")
    }

    @Test
    fun `INV-AC-21135 the maximum version is answered also when rows are inserted in the order 4, 1, 2`() {
        listOf(4, 1, 2).forEach { record(t, d, it) }
        latest(t, d) shouldBe LatestVersionFacts(4, "E-4")
    }

    @Test
    fun `INV-AC-21135 a document without a row has no latest version`() {
        record(t, d, 1)
        latest(t, UUID.randomUUID()) shouldBe null
    }

    @Test
    fun `INV-AC-21135 the row of another tenant is invisible`() {
        record(u, y, 9)
        latest(t, y) shouldBe null
        latest(u, y) shouldBe LatestVersionFacts(9, "E-9")
    }

    @Test
    fun `INV-AC-21135 a stored version without a total is still the latest version`() {
        record(t, d, 1)
        record(t, d, 2, total = null)
        latest(t, d) shouldBe LatestVersionFacts(2, "E-2")
    }

    @Test
    fun `INV-AC-21135 the tenant predicate hides the row of another tenant also when row security shows it`() {
        record(u, y, 9)
        // the session is bound to tenant u (row security lets u's rows through) but the request's tenant is t
        val answer =
            PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                SignedBindings.bind(c, u)
                try {
                    JooqLatestDocumentVersions(DSL.using(c, SQLDialect.POSTGRES)) { t }.latest(y)
                } finally {
                    c.commit()
                }
            }
        answer shouldBe null
    }
}
