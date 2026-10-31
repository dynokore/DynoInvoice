package com.dynokore.gst.controlPlane.adapters.persistence

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.GATE_RUN
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_RELEASE_RECORD
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.RELEASE_TRAIN
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.SUPPORT_WINDOW
import com.dynokore.gst.controlPlane.application.release.GateRunRepository
import com.dynokore.gst.controlPlane.application.release.PackReleaseRecords
import com.dynokore.gst.controlPlane.application.release.SupportWindowRepository
import com.dynokore.gst.controlPlane.application.release.TrainRepository
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.GateRun
import com.dynokore.gst.controlPlane.domain.release.GateRunKey
import com.dynokore.gst.controlPlane.domain.release.GateRunRecord
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.SupportWindow
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import org.jooq.DSLContext
import org.jooq.impl.DSL
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.records.GateRunRecord as GateRunRow

/** The gate runs of a release record as JSON text: one object per gate with candidate and released-version results. */
internal object RunsJson {
    private val mapper = JsonMapper.builder().build()

    fun write(runs: List<GateRun>): String =
        mapper.writeValueAsString(
            runs.map {
                linkedMapOf(
                    "gate" to it.gate.name,
                    "candidateResult" to it.candidate.name,
                    "findings" to it.findings.sorted(),
                    "baselineResult" to it.baseline?.name,
                    "baselineFindings" to it.baselineFindings.sorted(),
                )
            },
        )

    fun read(text: String): List<GateRun> =
        mapper.readTree(text).values().map { node ->
            GateRun(
                Gate.valueOf(node.get("gate").asString()),
                GateOutcome.valueOf(node.get("candidateResult").asString()),
                node
                    .get("findings")
                    .values()
                    .map { it.asString() }
                    .toSet(),
                node.get("baselineResult")?.takeUnless { it.isNull }?.let { GateOutcome.valueOf(it.asString()) },
                node
                    .get("baselineFindings")
                    .values()
                    .map { it.asString() }
                    .toSet(),
            )
        }
}

/** Release trains in the `controlplane` schema through jOOQ (TPL-11). */
class TrainRepositoryJooq(
    private val dsl: DSLContext,
) : TrainRepository {
    override fun find(version: TrainVersion): ReleaseTrain? =
        dsl.selectFrom(RELEASE_TRAIN).where(RELEASE_TRAIN.TRAIN_VERSION.eq(version.value)).fetchOne()?.let { row ->
            ReleaseTrain(
                TrainVersion(checkNotNull(row.trainVersion)),
                TrainKind.valueOf(checkNotNull(row.kind)),
                checkNotNull(row.exposureReducing),
                ReleaseRecord(
                    RunsJson.read(checkNotNull(row.runs)),
                    checkNotNull(row.exposureReducing),
                    ActorId(checkNotNull(row.classifiedBy)),
                    checkNotNull(row.recordedAt).toInstant(),
                ),
                AccreditationCheck(checkNotNull(row.needsReaccreditation), row.alertRef, row.taskRef),
            )
        }

    override fun save(train: ReleaseTrain) {
        dsl
            .insertInto(RELEASE_TRAIN)
            .set(RELEASE_TRAIN.TRAIN_VERSION, train.version.value)
            .set(RELEASE_TRAIN.KIND, train.kind.name)
            .set(RELEASE_TRAIN.EXPOSURE_REDUCING, train.exposureReducing)
            .set(RELEASE_TRAIN.NEEDS_REACCREDITATION, train.accreditation.assessmentNeeded)
            .set(RELEASE_TRAIN.RUNS, RunsJson.write(train.record.runs))
            .set(RELEASE_TRAIN.CLASSIFIED_BY, train.record.classifiedBy.value)
            .set(RELEASE_TRAIN.ALERT_REF, train.accreditation.alertRef)
            .set(RELEASE_TRAIN.TASK_REF, train.accreditation.taskRef)
            .set(RELEASE_TRAIN.RECORDED_AT, train.record.recordedAt.atOffset(ZoneOffset.UTC))
            .onConflict(RELEASE_TRAIN.TRAIN_VERSION)
            .doNothing()
            .execute()
    }
}

class SupportWindowRepositoryJooq(
    private val dsl: DSLContext,
    private val clock: java.time.Clock,
) : SupportWindowRepository {
    override fun find(windowVersion: String): SupportWindow? =
        dsl.selectFrom(SUPPORT_WINDOW).where(SUPPORT_WINDOW.WINDOW_VERSION.eq(windowVersion)).fetchOne()?.let { row ->
            SupportWindow(
                checkNotNull(row.windowVersion),
                checkNotNull(row.trainVersions).map { TrainVersion(checkNotNull(it)) },
            )
        }

    override fun save(window: SupportWindow) {
        dsl
            .insertInto(SUPPORT_WINDOW)
            .set(SUPPORT_WINDOW.WINDOW_VERSION, window.windowVersion)
            .set(SUPPORT_WINDOW.TRAIN_VERSIONS, window.trainVersions.map { it.value }.toTypedArray())
            .set(SUPPORT_WINDOW.RECORDED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .onConflict(SUPPORT_WINDOW.WINDOW_VERSION)
            .doNothing()
            .execute()
    }
}

/** The release record of a pack version: written at signing (corpus run), extended at publication (staging run). */
class PackReleaseRecordsJooq(
    private val dsl: DSLContext,
) : PackReleaseRecords {
    override fun find(ref: PackRef): ReleaseRecord? =
        dsl.selectFrom(PACK_RELEASE_RECORD).where(PACK_RELEASE_RECORD.PACK_REF.eq(ref.value)).fetchOne()?.let { row ->
            ReleaseRecord(
                RunsJson.read(checkNotNull(row.runs)),
                checkNotNull(row.exposureReducing),
                ActorId(checkNotNull(row.classifiedBy)),
                checkNotNull(row.recordedAt).toInstant(),
            )
        }

    override fun save(
        ref: PackRef,
        record: ReleaseRecord,
    ) {
        val runs = RunsJson.write(record.runs)
        dsl
            .insertInto(PACK_RELEASE_RECORD)
            .set(PACK_RELEASE_RECORD.PACK_REF, ref.value)
            .set(PACK_RELEASE_RECORD.EXPOSURE_REDUCING, record.exposureReducing)
            .set(PACK_RELEASE_RECORD.RUNS, runs)
            .set(PACK_RELEASE_RECORD.CLASSIFIED_BY, record.classifiedBy.value)
            .set(PACK_RELEASE_RECORD.RECORDED_AT, record.recordedAt.atOffset(ZoneOffset.UTC))
            .onConflict(PACK_RELEASE_RECORD.PACK_REF)
            .doUpdate()
            .set(PACK_RELEASE_RECORD.RUNS, runs)
            .execute()
    }
}

/** The recorded gate results of release trains: append-only, the train version matched whole (INV-T-8749). */
class GateRunRepositoryJooq(
    private val dsl: DSLContext,
) : GateRunRepository {
    override fun find(key: GateRunKey): GateRunRecord? =
        dsl
            .selectFrom(GATE_RUN)
            .where(GATE_RUN.TRAIN_VERSION.eq(key.trainVersion.value))
            .and(GATE_RUN.GATE.eq(key.gate.name))
            .and(DSL.coalesce(GATE_RUN.ENVIRONMENT, ABSENT).eq(key.environment?.name ?: ABSENT))
            .fetchOne()
            ?.let(::recordOf)

    override fun insert(record: GateRunRecord): Boolean =
        dsl
            .insertInto(GATE_RUN)
            .set(GATE_RUN.ID, UUID.randomUUID())
            .set(GATE_RUN.TRAIN_VERSION, record.trainVersion.value)
            .set(GATE_RUN.GATE, record.gate.name)
            .set(GATE_RUN.ENVIRONMENT, record.environment?.name)
            .set(GATE_RUN.CANDIDATE_RESULT, record.candidate.name)
            .set(GATE_RUN.BASELINE_RESULT, record.baseline?.name)
            .set(GATE_RUN.CANDIDATE_FINDINGS, record.candidateFindings.sorted().toTypedArray())
            .set(GATE_RUN.BASELINE_FINDINGS, record.baselineFindings.sorted().toTypedArray())
            .set(GATE_RUN.EVIDENCE_REF, record.evidenceRef)
            .set(GATE_RUN.RECORDED_BY, record.recordedBy.value)
            .set(GATE_RUN.RECORDED_AT, record.recordedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1

    override fun forTrain(version: TrainVersion): List<GateRunRecord> =
        dsl
            .selectFrom(GATE_RUN)
            .where(GATE_RUN.TRAIN_VERSION.eq(version.value))
            .fetch()
            .map(::recordOf)

    private fun recordOf(row: GateRunRow) =
        GateRunRecord(
            TrainVersion(checkNotNull(row.trainVersion)),
            Gate.valueOf(checkNotNull(row.gate)),
            row.environment?.let { ReferenceEnvironment.valueOf(it) },
            GateOutcome.valueOf(checkNotNull(row.candidateResult)),
            row.baselineResult?.let { GateOutcome.valueOf(it) },
            checkNotNull(row.candidateFindings).filterNotNull().toSet(),
            checkNotNull(row.baselineFindings).filterNotNull().toSet(),
            row.evidenceRef,
            ActorId(checkNotNull(row.recordedBy)),
            checkNotNull(row.recordedAt).toInstant(),
        )

    private companion object {
        const val ABSENT = "-"
    }
}
