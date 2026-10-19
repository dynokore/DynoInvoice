package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.persistence.PackRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.StagingEvidenceReaderJooq
import com.dynokore.gst.controlPlane.adapters.stage.StagingOutcome
import com.dynokore.gst.controlPlane.adapters.stage.StagingRecordWriter
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.ContentHash
import com.dynokore.gst.controlPlane.domain.pack.MinEngineVersion
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.pack.SignOutcome
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The registry tables, the signing trigger and the staging evidence write path, as the runtime login (CS-19). */
class RegistryPersistenceIT {
    private val clock = Clock.fixed(Instant.parse("2026-10-12T08:30:00Z"), ZoneOffset.UTC)
    private val hash = ContentHash("a".repeat(64))

    private fun newDraft(author: String = "alice"): RegulatoryPack =
        RegulatoryPack.draft(
            PackType("SCOPE"),
            PackVersion("v${UUID.randomUUID().toString().take(8)}"),
            listOf("IRAS e-Tax Guide"),
            ActorId(author),
        )

    private val dsl = PostgresDb.dsl()
    private val probe = PostgresDb.probe

    /** The SQLException at the root of a failed probe or statement. */
    private fun Throwable.sql(): SQLException = generateSequence(this) { it.cause }.filterIsInstance<SQLException>().first()

    private fun has(
        table: String,
        privilege: String,
    ) = probe.fetchValue("select has_table_privilege(current_user, ?, ?)", "controlplane.$table", privilege) == true

    @Test
    fun `a draft round-trips with its authors and a signature records the signer`() {
        val repository = PackRepositoryJooq(dsl, clock)
        val draft = newDraft()
        repository.save(draft)
        repository.save(draft.authoredBy(ActorId("pat")))
        val stored = checkNotNull(repository.find(draft.ref))
        stored.authors shouldBe setOf(ActorId("alice"), ActorId("pat"))
        stored.status shouldBe PackStatus.DRAFT
        val signed =
            (
                stored.sign(
                    ActorId("bob"),
                    "KEY-REG-01",
                    MinEngineVersion("2026.10.0"),
                    false,
                    clock.instant(),
                ) as SignOutcome.Signed
            ).pack
        repository.save(signed)
        val again = checkNotNull(repository.find(draft.ref))
        again.status shouldBe PackStatus.SIGNED
        again.signer shouldBe ActorId("bob")
        again.minEngineVersion shouldBe MinEngineVersion("2026.10.0")
        again.authors shouldBe setOf(ActorId("alice"), ActorId("pat"))
    }

    @Test
    fun `the database refuses a signature by an author of the version`() {
        val draft = newDraft("pat")
        PackRepositoryJooq(dsl, clock).save(draft)
        val error =
            shouldThrow<Exception> {
                probe.unbound {
                    fetchValue(
                        "insert into controlplane.signing_record (pack_ref, signer, signing_key_ref, signed_at) " +
                            "values (?, 'pat', 'KEY', now())",
                        draft.ref.value,
                    )
                }
            }.sql()
        error.sqlState shouldBe "42501"
    }

    @Test
    fun `a direct INSERT of a PASSED staging row as the runtime login is refused with permission denied`() {
        val draft = newDraft().also { PackRepositoryJooq(dsl, clock).save(it) }
        val error =
            shouldThrow<Exception> {
                probe.unbound {
                    fetchValue(
                        "insert into controlplane.pack_staging_record " +
                            "(pack_ref, result, pack_content_hash, recorded_by) values (?, 'PASSED', ?, 'runtime')",
                        draft.ref.value,
                        hash.hex,
                    )
                }
            }.sql()
        error.sqlState shouldBe "42501"
    }

    @Test
    fun `the runtime login holds no INSERT, UPDATE or DELETE on the staging table and no DELETE anywhere`() {
        listOf("INSERT", "UPDATE", "DELETE", "TRUNCATE").forEach { has("pack_staging_record", it) shouldBe false }
        listOf("pack_version", "pack_author", "signing_record", "pack_staging_record", "pack_release", "pack_feed_index")
            .forEach { has(it, "DELETE") shouldBe false }
        listOf("signing_record", "pack_author", "pack_release", "pack_feed_index").forEach {
            has(it, "UPDATE") shouldBe false
        }
    }

    @Test
    fun `the runtime login owns no table, is a member of no role and is neither superuser nor BYPASSRLS`() {
        probe.fetchValue("select count(*) from pg_tables where tableowner = current_user") shouldBe 0L
        probe.fetchValue(
            "select count(*) from pg_auth_members m join pg_roles r on r.oid = m.member where r.rolname = current_user",
        ) shouldBe 0L
    }

    @Test
    fun `the staging writer records a row through the sys-owned function and the reader returns it`() {
        val draft = newDraft().also { PackRepositoryJooq(dsl, clock).save(it) }
        val writer = StagingRecordWriter(dsl)
        writer.record(
            StagingOutcome(draft.ref, StagingResult.FAILED, hash, "SIMULATOR", "evidence/1", "2026-Q3", "stage"),
        )
        writer.record(
            StagingOutcome(draft.ref, StagingResult.PASSED, hash, "SIMULATOR", "evidence/2", "2026-Q3", "stage"),
        )
        StagingEvidenceReaderJooq(dsl).forPack(draft.ref).map { it.result } shouldBe
            listOf(StagingResult.FAILED, StagingResult.PASSED)
        StagingEvidenceReaderJooq(dsl).forPack(PackRef("PACK-NONE-1")) shouldBe emptyList()
    }

    @Test
    fun `the function refuses a record for an unknown pack version`() {
        val writer = StagingRecordWriter(dsl)
        shouldThrow<Exception> {
            writer.record(
                StagingOutcome(PackRef("PACK-NONE-1"), StagingResult.PASSED, hash, null, null, null, "stage"),
            )
        }
    }

    @Test
    fun `the function is owned by the sys role and is a definer function`() {
        probe.fetchValue(
            "select pg_get_userbyid(proowner) from pg_proc where proname = 'record_staging_result'",
        ) shouldBe "controlplane_sys"
        probe.fetchValue(
            "select count(*) from pg_proc where proname = 'record_staging_result' and prosecdef",
        ) shouldBe 1L
    }
}
