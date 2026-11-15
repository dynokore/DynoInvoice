package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.submission.adapters.persistence.JooqAcknowledgementInputs
import com.dynokore.gst.submission.adapters.persistence.JooqFilingDueDates
import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionStore
import com.dynokore.gst.submission.adapters.policy.PolicySafetyMargins
import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.application.pack.PackDefaultMargin
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.pack.PackMarginAnswer
import com.dynokore.gst.submission.application.queue.AcknowledgementBasis
import com.dynokore.gst.submission.application.queue.NewReadySubmission
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The acknowledgement deadline written once and the inputs adapter, as `submission_runtime` (INV-AC-22761). */
class AcknowledgementDeadlineStoreDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val store = JooqSubmissionStore(dsl)
    private var bound: UUID = UUID.randomUUID()
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(bound)

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val margin = checkNotNull(PackMargin.of(10, "DAYS"))
    private val asked = mutableListOf<Triple<String, String, LocalDate>>()
    private val reader =
        object : PolicyReader {
            override fun resolve(
                key: VariationPointKey,
                scope: PolicyScope,
                at: LocalDate,
            ): ResolvedSetting {
                asked += Triple(key.value, checkNotNull(scope.entity), at)
                return resolved(key.value, at, "LARGER", "10")
            }
        }

    // ResolvedSetting has no public constructor; built by reflection as PolicySafetyMarginsTest does.
    private fun resolved(
        key: String,
        at: LocalDate,
        option: String,
        valueJson: String,
    ): ResolvedSetting =
        ResolvedSetting::class.java.declaredConstructors
            .first {
                it.parameterCount == 5 &&
                    it.parameterTypes.none { t -> t.simpleName == "DefaultConstructorMarker" }
            }.also { it.isAccessible = true }
            .newInstance(
                key,
                at,
                listOf(SelectedOption(option, valueJson)),
                PolicySource.CatalogueDefault,
                emptyList<Any>(),
            ).let(ResolvedSetting::class.java::cast)

    private val packDefault =
        PackDefaultMargin { _, _ -> PackMarginAnswer.Known(checkNotNull(PackMargin.of(7, "DAYS"))) }
    private val margins = PolicySafetyMargins(reader, packDefault)
    private val inputs = JooqAcknowledgementInputs(dsl, JooqFilingDueDates(dsl, tenants), margins)

    private companion object {
        val VP_18_ID: String = VariationPoint.VP_18.id
    }

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound = tenant
        SignedBindings.bind(connection, tenant)
        return block().also { connection.commit() }
    }

    private fun insert(
        tenant: UUID,
        doc: String,
    ) = asTenant(tenant) {
        store.insertReady(
            TenantId(tenant),
            NewReadySubmission(doc, 1, "E", null, byteArrayOf(1), "sha-$doc", SubmissionKind.TYPE_1A),
        )
    }

    private fun row(
        tenant: UUID,
        doc: String,
    ): Pair<Instant?, String> =
        asTenant(tenant) {
            val r =
                dsl.fetchOne(
                    "select acknowledgement_deadline, (to_jsonb(s) - 'acknowledgement_deadline')::text " +
                        "from submission.submission s where doc_uuid = ?",
                    doc,
                )!!
            r.get(0, java.time.OffsetDateTime::class.java)?.toInstant() to r.get(1, String::class.java)
        }

    private fun register(
        tenant: UUID,
        doc: String,
        entity: String,
        period: String,
    ) = asTenant(tenant) {
        dsl.execute(
            "insert into submission.obligation_register_entry (tenant_id, doc_uuid, entity_ref, period_ref, " +
                "due_date, in_scope, kind) values (?, ?, ?, ?, date '2026-03-31', true, 'INVOICE')",
            tenant,
            doc,
            entity,
            period,
        )
    }

    private fun dueDate(
        tenant: UUID,
        entity: String,
        period: String,
        date: String,
    ) = asTenant(tenant) {
        dsl.execute(
            "insert into submission.period_due_date values (?, ?, ?, ?::date, 1)",
            tenant,
            entity,
            period,
            date,
        )
    }

    @Test
    fun `INV-AC-22761 the first call stores the deadline and the second call leaves it, no other column changes`() {
        val t = UUID.randomUUID()
        val doc = "d-${UUID.randomUUID()}"
        insert(t, doc) shouldBe 1
        val before = row(t, doc)
        before.first shouldBe null
        val first = Instant.parse("2026-03-01T10:00:00Z")
        asTenant(t) { store.recordAcknowledgementDeadline(TenantId(t), doc, 1, first) }
        row(t, doc) shouldBe (first to before.second)
        asTenant(t) { store.recordAcknowledgementDeadline(TenantId(t), doc, 1, Instant.parse("2026-03-02T11:30:00Z")) }
        row(t, doc) shouldBe (first to before.second)
    }

    @Test
    fun `INV-AC-22761 a missing version or another tenant fails with a message naming the document and version`() {
        val t = UUID.randomUUID()
        val doc = "d-${UUID.randomUUID()}"
        insert(t, doc)
        val at = Instant.parse("2026-03-01T10:00:00Z")
        val e =
            shouldThrow<IllegalStateException> {
                asTenant(
                    t,
                ) { store.recordAcknowledgementDeadline(TenantId(t), doc, 2, at) }
            }
        e.message shouldBe "the submission row $doc version 2 was not updated"
        e.message!! shouldContain doc
        connection.rollback()
        val other = UUID.randomUUID()
        shouldThrow<IllegalStateException> {
            asTenant(other) { store.recordAcknowledgementDeadline(TenantId(other), doc, 1, at) }
        }
        connection.rollback()
        row(t, doc).first shouldBe null
    }

    @Test
    fun `INV-AC-22761 the adapter returns the due date and the margin of the document`() {
        val t = UUID.randomUUID()
        val doc = "d-${UUID.randomUUID()}"
        register(t, doc, "E", "P1")
        dueDate(t, "E", "P1", "2026-03-31")
        dueDate(t, "E", "P2", "2026-06-30")
        val basis = asTenant(t) { inputs.of(TenantId(t), doc) }
        basis shouldBe AcknowledgementBasis(LocalDate.parse("2026-03-31"), SafetyMarginAnswer.Known(margin))
        asked.single() shouldBe Triple(VP_18_ID, "E", LocalDate.parse("2026-03-31"))
        basis.margin shouldBe margins.effective(TenantId(t), "E", LocalDate.parse("2026-03-31"))
    }

    @Test
    fun `INV-AC-22761 no register entry and no due date row give a null due date and Unavailable`() {
        val t = UUID.randomUUID()
        val none = AcknowledgementBasis(null, SafetyMarginAnswer.Unavailable)
        asTenant(t) { inputs.of(TenantId(t), "missing-${UUID.randomUUID()}") } shouldBe none
        val doc = "d-${UUID.randomUUID()}"
        register(t, doc, "E", "P9")
        dueDate(t, "E", "P1", "2026-03-31")
        asTenant(t) { inputs.of(TenantId(t), doc) } shouldBe none
        // a document of another tenant is not visible
        val other = UUID.randomUUID()
        asTenant(other) { inputs.of(TenantId(other), doc) } shouldBe none
        asked.size shouldBe 0
    }

    @Test
    fun `INV-AC-22761 the tenant condition of the deadline write holds without row level security`() {
        val bypassRole = "submission_test_bypass"
        val bypassPassword = "submission_test_bypass_pw"
        SubmissionDb.superuser().use { su ->
            su.createStatement().use { st ->
                st.execute(
                    "do \$\$ begin if not exists (select 1 from pg_roles where rolname = '$bypassRole') then " +
                        "create role $bypassRole login nosuperuser bypassrls password '$bypassPassword'; " +
                        "end if; end \$\$",
                )
                st.execute("grant submission_runtime to $bypassRole")
            }
        }
        val tenantA = UUID.randomUUID()
        val tenantB = UUID.randomUUID()
        val docB = "b-${UUID.randomUUID()}"
        val at = Instant.parse("2026-03-01T10:00:00Z")
        SubmissionDb.connect(bypassRole, bypassPassword).use { conn ->
            val bypassDsl = DSL.using(conn, SQLDialect.POSTGRES)
            val bypassStore = JooqSubmissionStore(bypassDsl)
            bypassStore.insertReady(
                TenantId(tenantB),
                NewReadySubmission(docB, 1, "E", null, byteArrayOf(2), "sha-b", SubmissionKind.TYPE_2),
            )
            shouldThrow<IllegalStateException> {
                bypassStore.recordAcknowledgementDeadline(
                    TenantId(tenantA),
                    docB,
                    1,
                    at,
                )
            }
            deadlineOf(bypassDsl, docB) shouldBe null
            bypassStore.recordAcknowledgementDeadline(TenantId(tenantB), docB, 1, at)
            deadlineOf(bypassDsl, docB) shouldBe at
        }
    }

    private fun deadlineOf(
        d: org.jooq.DSLContext,
        doc: String,
    ): Instant? =
        d
            .fetchOne("select acknowledgement_deadline from submission.submission where doc_uuid = ?", doc)!!
            .get(0, java.time.OffsetDateTime::class.java)
            ?.toInstant()
}
