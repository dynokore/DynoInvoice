package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.gst.consoleBff.domain.session.SessionPolicy
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.LocalDate

/** The per-tenant console session timeouts (VP-84, INV-REQ-25100) over a [PolicyReader] double. */
class PolicyTenantSessionPolicyTest {
    private val tenantA = "11111111-1111-1111-1111-11111111aaaa"
    private val day = LocalDate.of(2026, 11, 20)
    private val installation = SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12))

    private class Reader(
        val selected: List<SelectedOption>,
    ) : PolicyReader {
        val calls = mutableListOf<Triple<VariationPointKey, PolicyScope, LocalDate>>()

        override fun resolve(
            key: VariationPointKey,
            scope: PolicyScope,
            at: LocalDate,
        ): ResolvedSetting {
            calls += Triple(key, scope, at)
            return ResolvedSetting(key, at, selected, PolicySource.CatalogueDefault)
        }

        override fun capToBounds(
            key: VariationPointKey,
            scope: PolicyScope,
            level: ScopeLevel,
            selected: List<SelectedOption>,
            at: LocalDate,
        ): ResolvedSetting = error("not used")
    }

    private class Tenants : TenantContext {
        var bound: TenantId? = null

        override fun current(): TenantId = checkNotNull(bound)

        override fun currentActor(): String? = null

        override fun <T> bind(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T {
            bound = tenant
            return try {
                block()
            } finally {
                bound = null
            }
        }
    }

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private fun policy(
        reader: Reader,
        inst: SessionPolicy = installation,
    ) = PolicyTenantSessionPolicy(reader, Tenants(), runner, inst)

    private fun shorter(json: String?) = Reader(listOf(SelectedOption("SHORTER", json)))

    private fun of(
        reader: Reader,
        inst: SessionPolicy = installation,
    ) = policy(reader, inst).of(tenantA, day)

    @Test
    fun `INV-AC-25103 the default option gives 30 minutes and 12 hours`() {
        val p = of(Reader(listOf(SelectedOption("DEFAULT_30M_12H"))))
        p.idle shouldBe Duration.ofMinutes(30)
        p.absolute shouldBe Duration.ofHours(12)
    }

    @Test
    fun `INV-AC-25103 SHORTER of 10 minutes and 2 hours gives them`() {
        val p = of(shorter("""{"idle":10,"absolute":2}"""))
        p.idle shouldBe Duration.ofMinutes(10)
        p.absolute shouldBe Duration.ofHours(2)
    }

    @Test
    fun `INV-AC-25103 SHORTER of 1 minute and 1 hour gives them`() {
        val p = of(shorter("""{"idle":1,"absolute":1}"""))
        p.idle shouldBe Duration.ofMinutes(1)
        p.absolute shouldBe Duration.ofHours(1)
    }

    @Test
    fun `INV-AC-25103 the installation values PT5M and PT1H win over the tenant's longer values`() {
        val inst = SessionPolicy(Duration.ofMinutes(5), Duration.ofHours(1))
        for (reader in listOf(
            Reader(listOf(SelectedOption("DEFAULT_30M_12H"))),
            shorter("""{"idle":10,"absolute":2}"""),
        )) {
            val p = of(reader, inst)
            p.idle shouldBe Duration.ofMinutes(5)
            p.absolute shouldBe Duration.ofHours(1)
        }
    }

    @Test
    fun `INV-AC-25103 idle and absolute are each the shorter of the two sources`() {
        val inst = SessionPolicy(Duration.ofMinutes(20), Duration.ofHours(1))
        val p = of(shorter("""{"idle":10,"absolute":2}"""), inst)
        p.idle shouldBe Duration.ofMinutes(10)
        p.absolute shouldBe Duration.ofHours(1)
    }

    @Test
    fun `INV-AC-25103 each unreadable value throws and is never clamped`() {
        val bad =
            listOf(
                Reader(listOf(SelectedOption("OTHER"))),
                shorter(null),
                shorter("""{"absolute":2}"""),
                shorter("""{"idle":10}"""),
                shorter("""{"idle":10.5,"absolute":2}"""),
                shorter("""{"idle":"10","absolute":2}"""),
                shorter("""{"idle":0,"absolute":2}"""),
                shorter("""{"idle":10,"absolute":0}"""),
                shorter("""{"idle":31,"absolute":2}"""),
                shorter("""{"idle":10,"absolute":13}"""),
                shorter("""not json"""),
                Reader(
                    listOf(SelectedOption("DEFAULT_30M_12H"), SelectedOption("SHORTER", """{"idle":1,"absolute":1}""")),
                ),
            )
        bad.forEach { reader -> assertThrows<RuntimeException> { of(reader) } }
    }

    @Test
    fun `INV-AC-25103 the reader sees the tenant scope of the row and the business date on every call`() {
        val reader = shorter("""{"idle":10,"absolute":2}""")
        val p = policy(reader)
        p.of(tenantA, day)
        val next = day.plusDays(1)
        p.of(tenantA, next)
        reader.calls.size shouldBe 2
        reader.calls.map { it.first } shouldBe listOf(VariationPointKey("VP-84"), VariationPointKey("VP-84"))
        reader.calls.map { it.third } shouldBe listOf(day, next)
        reader.calls.forEach {
            it.second.tenant shouldBe TenantId.of(tenantA)
            it.second.ref(ScopeLevel.TENANT)?.id shouldBe tenantA
            it.second.ref(ScopeLevel.ENTITY) shouldBe null
        }
    }
}
