package com.dynokore.gst.ingestion.domain.recording

import java.util.UUID

/**
 * What canonical-svc answered to its `recordDocument` operation, reduced to the facts the mapping reads
 * (platform-contracts canonical/openapi.yaml): `201` is a recorded version, `200` the idempotent replay and a
 * rejection carries its error-catalogue code.
 */
sealed interface CanonicalAnswer {
    data class Created(
        val docUuid: UUID,
        val version: Long,
    ) : CanonicalAnswer

    data class Replay(
        val docUuid: UUID,
        val version: Long,
    ) : CanonicalAnswer

    data class Rejected(
        val errorCode: String,
    ) : CanonicalAnswer
}

/**
 * The outcome of the idempotency pair `sourceKey` + `sourceRevision` as ingestion-svc answers it (INV-REQ-806,
 * INV-AC-1861). The recording and de-duplication decision is canonical-svc's alone (INV-REQ-1191); ingestion-svc keeps
 * no record of received pairs and only maps the answer.
 */
sealed interface RecordingOutcome {
    val httpStatus: Int

    /** A new document: canonical `201` with `version` 1. */
    data class New(
        val docUuid: UUID,
    ) : RecordingOutcome {
        override val httpStatus: Int = HTTP_CREATED
    }

    /** The same pair again: canonical `200`, the original result and the original Doc UUID. */
    data class Replay(
        val docUuid: UUID,
    ) : RecordingOutcome {
        override val httpStatus: Int = HTTP_OK
    }

    /** A higher `sourceRevision` of a known `sourceKey`: canonical `201` with `version` above 1, the same Doc UUID. */
    data class Amend(
        val docUuid: UUID,
        val version: Long,
    ) : RecordingOutcome {
        override val httpStatus: Int = HTTP_OK
    }

    /** A lower `sourceRevision` than recorded: canonical rejection ING-002. */
    data object Stale : RecordingOutcome {
        const val ERROR_CODE = "ING-002"
        override val httpStatus: Int = HTTP_CONFLICT
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_CONFLICT = 409
    }
}

/** A canonical answer that no published mapping covers; a later slice rules it (nothing is guessed here). */
class UnmappedCanonicalAnswer(
    val answer: CanonicalAnswer,
) : RuntimeException("no mapping is published for the canonical answer $answer")

object RecordingMapping {
    private const val FIRST_VERSION = 1L

    fun map(answer: CanonicalAnswer): RecordingOutcome =
        when (answer) {
            is CanonicalAnswer.Replay -> {
                RecordingOutcome.Replay(answer.docUuid)
            }

            is CanonicalAnswer.Created -> {
                if (answer.version > FIRST_VERSION) {
                    RecordingOutcome.Amend(answer.docUuid, answer.version)
                } else {
                    RecordingOutcome.New(answer.docUuid)
                }
            }

            is CanonicalAnswer.Rejected -> {
                if (answer.errorCode == RecordingOutcome.Stale.ERROR_CODE) {
                    RecordingOutcome.Stale
                } else {
                    throw UnmappedCanonicalAnswer(answer)
                }
            }
        }
}
