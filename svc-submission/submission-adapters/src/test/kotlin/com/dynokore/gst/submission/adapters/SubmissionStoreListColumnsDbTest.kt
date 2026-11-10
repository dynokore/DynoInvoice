package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionStore
import com.dynokore.gst.submission.application.queue.NewReadySubmission
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The list columns written by the store as `submission_runtime` with the tenant bound (INV-AC-19465). */
class SubmissionStoreListColumnsDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val store = JooqSubmissionStore(dsl)

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        return block().also { connection.commit() }
    }

    private fun insert(
        tenant: UUID,
        doc: String,
        kind: SubmissionKind,
    ) = asTenant(tenant) {
        store.insertReady(
            TenantId(tenant),
            NewReadySubmission(doc, 1, "e-$doc", null, byteArrayOf(1), "sha-$doc", kind),
        )
    }

    private fun column(
        tenant: UUID,
        doc: String,
        column: String,
    ): String? =
        asTenant(tenant) {
            dsl
                .fetchOne(
                    "select $column::text from submission.submission where doc_uuid = ?",
                    doc,
                )!!
                .get(0, String::class.java)
        }

    private fun instant(
        tenant: UUID,
        doc: String,
        column: String,
    ): Instant? =
        column(tenant, doc, column)?.let { s ->
            dsl.fetchOne("select ?::timestamptz", s)!!.get(0, java.time.OffsetDateTime::class.java).toInstant()
        }

    @Test
    fun `INV-AC-19465 insertReady stores the type name and recordAccepted stores submitted_at once`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val docA = "a-${UUID.randomUUID()}"
        val docB = "b-${UUID.randomUUID()}"
        insert(a, docA, SubmissionKind.TYPE_1B) shouldBe 1
        insert(b, docB, SubmissionKind.TYPE_3) shouldBe 1
        column(a, docA, "submission_type") shouldBe "TYPE_1B"
        column(a, docA, "submitted_at") shouldBe null
        asTenant(a) { store.markSent(TenantId(a), docA, 1, 1) }
        column(a, docA, "submitted_at") shouldBe null
        val first = Instant.parse("2026-11-10T01:02:03Z")
        val second = Instant.parse("2026-11-10T05:06:07Z")
        asTenant(a) { store.recordAccepted(TenantId(a), docA, 1, first) }
        asTenant(a) { store.recordAccepted(TenantId(a), docA, 1, second) }
        instant(a, docA, "submitted_at") shouldBe first
        instant(a, docA, "ack_timer_started_at") shouldBe first
        // a call bound to tenant A never reaches the row of tenant B
        shouldThrow<IllegalStateException> { asTenant(a) { store.recordAccepted(TenantId(a), docB, 1, first) } }
        connection.rollback()
        column(b, docB, "submitted_at") shouldBe null
        column(b, docB, "submission_type") shouldBe "TYPE_3"
    }

    @Test
    fun `INV-AC-19465 the tenant condition of recordAccepted holds without row level security`() {
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
        val docA = "a-${UUID.randomUUID()}"
        val docB = "b-${UUID.randomUUID()}"
        val at = Instant.parse("2026-11-10T01:02:03Z")
        SubmissionDb.connect(bypassRole, bypassPassword).use { conn ->
            val bypassDsl = DSL.using(conn, SQLDialect.POSTGRES)
            val bypassStore = JooqSubmissionStore(bypassDsl)

            fun read(
                doc: String,
                column: String,
            ): String? =
                bypassDsl
                    .fetchOne(
                        "select $column::text from submission.submission where doc_uuid = ?",
                        doc,
                    )!!
                    .get(0, String::class.java)
            bypassStore.insertReady(
                TenantId(tenantA),
                NewReadySubmission(docA, 1, "e-a", null, byteArrayOf(1), "sha-a", SubmissionKind.TYPE_1A),
            )
            bypassStore.insertReady(
                TenantId(tenantB),
                NewReadySubmission(docB, 1, "e-b", null, byteArrayOf(2), "sha-b", SubmissionKind.TYPE_2),
            )
            shouldThrow<IllegalStateException> { bypassStore.recordAccepted(TenantId(tenantA), docB, 1, at) }
            read(docB, "submitted_at") shouldBe null
            read(docB, "ack_timer_started_at") shouldBe null
            read(docB, "submission_type") shouldBe "TYPE_2"
            bypassStore.recordAccepted(TenantId(tenantA), docA, 1, at)
            read(docA, "submitted_at") shouldBe read(docA, "ack_timer_started_at")
            (read(docA, "submitted_at") != null) shouldBe true
            read(docB, "submitted_at") shouldBe null
        }
    }
}
