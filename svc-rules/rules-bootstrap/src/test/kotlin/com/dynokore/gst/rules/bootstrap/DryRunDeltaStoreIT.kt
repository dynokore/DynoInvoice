package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.packs.EngineVersion
import com.dynokore.gst.rules.adapters.persistence.JooqDryRunDeltaPort
import com.dynokore.gst.rules.adapters.persistence.JooqDryRunDeltaStore
import com.dynokore.gst.rules.adapters.persistence.JooqMappingPackStore
import com.dynokore.gst.rules.application.DryRunStart
import com.dynokore.gst.rules.application.DryRunState
import com.dynokore.gst.rules.application.MappingPackDraft
import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.DryRunComputation
import com.dynokore.gst.rules.domain.dryrun.DryRunDelta
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Properties
import java.util.UUID

private const val COUNT_DOCUMENTS = "select count(*) from rules.dry_run_delta_document where delta_id = ?::uuid"
private const val COUNT_TOTALS = "select count(*) from rules.dry_run_delta_total where delta_id = ?::uuid"
private const val STATE = "select state from rules.dry_run_delta where id = ?::uuid"
private const val PACK_VERSION = "select pack_version from rules.pack_registry where tenant_id = ?::uuid and pack_id = ?"
private const val EFFECTIVE_FROM = "2026-02-01"

/**
 * The stored dry-run delta on real PostgreSQL (INV-T-19902, INV-AC-19904; INV-REQ-772): the jOOQ store writes the run,
 * its document rows and its total rows, reads the delta back unchanged, and another tenant sees nothing. The database
 * is the one PostgresRules builds from every migration; the probes read the tables as the tenant that owns the rows.
 */
@SpringBootTest(classes = [RulesApplication::class])
class DryRunDeltaStoreIT(
    @Autowired private val tx: TransactionRunner,
    @Autowired private val tenants: TenantContext,
    @Autowired private val db: DSLContext,
) {
    private val runtime: TenancyProbe = TenancyProbe.connect(pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
    private val tenant = TenantId(UUID.randomUUID())
    private val other = TenantId(UUID.randomUUID())
    private val store = JooqDryRunDeltaStore(db, tenants)

    private fun <T> asTenant(
        who: TenantId = tenant,
        block: () -> T,
    ): T = tenants.bind(who, "rules-actor") { tx.inTransaction { block() } }

    private fun start(
        id: String,
        version: String = "v-" + id.take(8),
    ) = DryRunStart(id, tenant, "MAPPING", "current-1", version, "user-a", Instant.now())

    private fun document(
        uuid: String,
        period: String,
        date: String,
        function: String,
        preceding: String?,
        flagged: Boolean,
    ) = SampledDocument(
        1, 1, uuid, period, "E-1", LocalDate.parse(date), "STD", function, preceding?.let(LocalDate::parse),
        "STANDARD", flagged,
        if (flagged) BigDecimal("0.00") else BigDecimal("100.00"),
        if (flagged) BigDecimal("0.00") else BigDecimal("9.00"),
    )

    /** Two changed documents in two periods: an ordinary one, and a flagged credit note that straddles the pack date. */
    private fun twoDocumentDelta(): DryRunDelta {
        val first = document(UUID.randomUUID().toString(), "2026-01", "2026-01-10", "INVOICE", null, false)
        val second = document(UUID.randomUUID().toString(), "2026-02", "2026-02-10", "ADJUSTMENT", "2026-01-20", true)
        return DryRunComputation.delta(
            listOf(
                Triple(first, DocumentOutcome("SR"), DocumentOutcome("ZR")),
                Triple(second, DocumentOutcome("SR"), DocumentOutcome("ERR-1", "BLOCK")),
            ),
            LocalDate.parse(EFFECTIVE_FROM),
        )
    }

    private fun count(
        sql: String,
        id: String,
        who: TenantId = tenant,
    ): String = runtime.inTenant(who) { fetchValue(sql, id) }.toString()

    private fun message(failure: Throwable): String =
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `INV-AC-19904 a stored delta read back with find equals the delta given to complete`() {
        val id = UUID.randomUUID().toString()
        val delta = twoDocumentDelta()
        asTenant { store.begin(start(id)) }
        asTenant { store.find(id)?.state } shouldBe DryRunState.RUNNING
        asTenant { store.complete(id, Instant.parse("2026-11-11T01:00:00Z"), delta) }
        val stored = asTenant { store.find(id) }!!
        stored.state shouldBe DryRunState.COMPLETE
        stored.startedBy shouldBe "user-a"
        stored.reason shouldBe null
        stored.delta shouldBe delta
        stored.delta!!.documents.map { it.docUuid } shouldBe delta.documents.map { it.docUuid }
        stored.delta!!.documents.map { it.periodRef } shouldBe listOf("2026-01", "2026-02")
        stored.delta!!.byPeriod shouldBe delta.byPeriod
        stored.delta!!.changeTypeTotals shouldBe delta.changeTypeTotals
        stored.delta!!.straddlingCreditNotes.size shouldBe 1
        count(COUNT_DOCUMENTS, id) shouldBe "2"
        runtime.inTenant(tenant) { fetchValue("select changed_count from rules.dry_run_delta where id = ?::uuid", id) } shouldBe 2
    }

    @Test
    fun `INV-AC-19904 probe - a second document row that violates a constraint leaves the run RUNNING with no rows`() {
        val id = UUID.randomUUID().toString()
        val delta = twoDocumentDelta()
        val duplicate = delta.copy(documents = delta.documents.map { it.copy(docUuid = delta.documents.first().docUuid) })
        asTenant { store.begin(start(id)) }
        val refused = shouldThrow<Throwable> { asTenant { store.complete(id, Instant.now(), duplicate) } }
        message(refused) shouldContain "dry_run_delta_document"
        count(STATE, id) shouldBe "RUNNING"
        count(COUNT_DOCUMENTS, id) shouldBe "0"
        count(COUNT_TOTALS, id) shouldBe "0"
        asTenant { store.find(id) }!!.delta shouldBe null
    }

    @Test
    fun `INV-AC-19904 probe - another tenant gets null from find and sees no row in the two tables`() {
        val id = UUID.randomUUID().toString()
        asTenant { store.begin(start(id)) }
        asTenant { store.complete(id, Instant.now(), twoDocumentDelta()) }
        asTenant(other) { store.find(id) } shouldBe null
        asTenant(other) { store.find(UUID.randomUUID().toString()) } shouldBe null
        count(COUNT_DOCUMENTS, id, other) shouldBe "0"
        count(COUNT_TOTALS, id, other) shouldBe "0"
        count(COUNT_DOCUMENTS, id) shouldBe "2"
        count(COUNT_TOTALS, id) shouldBe "5"
    }

    @Test
    fun `INV-AC-19904 probe - an update or a delete of a COMPLETE run is refused`() {
        val id = UUID.randomUUID().toString()
        asTenant { store.begin(start(id)) }
        asTenant { store.complete(id, Instant.now(), twoDocumentDelta()) }
        val update =
            shouldThrow<Throwable> {
                runtime.inTenant(tenant) {
                    fetchValue("update rules.dry_run_delta set started_by = 'user-b' where id = ?::uuid returning id", id)
                }
            }
        message(update) shouldContain "a complete dry-run delta is never updated or deleted"
        shouldThrow<Throwable> {
            runtime.inTenant(tenant) { fetchValue("delete from rules.dry_run_delta where id = ?::uuid returning id", id) }
        }
        shouldThrow<Throwable> { asTenant { store.complete(id, Instant.now(), twoDocumentDelta()) } }
        count(STATE, id) shouldBe "COMPLETE"
        asTenant { store.find(id) }!!.startedBy shouldBe "user-a"
    }

    @Test
    fun `INV-AC-19904 unavailable writes a non-blank reason and no row, and a blank reason is refused by the check`() {
        val id = UUID.randomUUID().toString()
        asTenant { store.begin(start(id)) }
        val refused = shouldThrow<Throwable> { asTenant { store.unavailable(id, "  ") } }
        message(refused) shouldContain "dry_run_delta_unavailable_reason_text"
        count(STATE, id) shouldBe "RUNNING"
        asTenant { store.unavailable(id, "page 2, item 1: periodRef is missing") }
        val stored = asTenant { store.find(id) }!!
        stored.state shouldBe DryRunState.SAMPLE_UNAVAILABLE
        stored.reason shouldBe "page 2, item 1: periodRef is missing"
        stored.delta shouldBe null
        count(COUNT_DOCUMENTS, id) shouldBe "0"
        count(COUNT_TOTALS, id) shouldBe "0"
    }

    @Test
    fun `INV-AC-19904 the qualifying port finds the COMPLETE run and not the SAMPLE_UNAVAILABLE run of the same version`() {
        val packId = "pack-" + UUID.randomUUID().toString().take(8)
        val packs =
            JooqMappingPackStore(
                db,
                tenants,
                EngineVersion(BuildProperties(Properties().apply { setProperty("version", "1.0.0") })),
                Clock.systemUTC(),
            )
        asTenant { packs.draft(MappingPackDraft(packId, tenant, "E-1", packId, "rules-actor", PackStatus.DRAFT)) }
        val version = runtime.inTenant(tenant) { fetchValue(PACK_VERSION, tenant.toString(), packId) } as String
        val port = JooqDryRunDeltaPort(db, tenants)
        val unavailable = UUID.randomUUID().toString()
        asTenant { store.begin(start(unavailable, version)) }
        asTenant { store.unavailable(unavailable, "sample incomplete") }
        asTenant { port.qualifying("MAPPING", version) } shouldBe null
        val complete = UUID.randomUUID().toString()
        asTenant { store.begin(start(complete, version)) }
        asTenant { store.complete(complete, Instant.now(), twoDocumentDelta()) }
        asTenant { port.qualifying("MAPPING", version) } shouldBe complete
    }

    companion object {
        private val pg get() = PostgresRules.pg

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = PostgresRules.register(registry)
    }
}
