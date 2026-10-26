package com.dynokore.gst.ingestion.domain.recording

import java.util.UUID

/**
 * What canonical-svc answered to its `recordDocument` operation, reduced to the facts the mapping reads
 * (platform-contracts canonical/openapi.yaml): `201` is a recorded version, `200` the idempotent replay, `202` a
 * held or blocked document and a rejection carries its HTTP status and error-catalogue code (INV-Q-11018).
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

    /** Canonical `202`: recorded and held or blocked; [reasons] keep canonical's order. */
    data class Held(
        val docUuid: UUID,
        val version: Long,
        val reasons: List<String>,
    ) : CanonicalAnswer

    data class Rejected(
        val errorCode: String?,
        val httpStatus: Int,
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

    /** Canonical `202` (INV-REQ-807): recorded, held or blocked; the same docUuid, version and reasons are answered. */
    data class Held(
        val docUuid: UUID,
        val version: Long,
        val reasons: List<String>,
    ) : RecordingOutcome {
        override val httpStatus: Int = HTTP_ACCEPTED
    }

    /** A canonical rejection from the relay set, answered with canonical's status and code as received. */
    data class Relayed(
        override val httpStatus: Int,
        val errorCode: String,
    ) : RecordingOutcome

    /** Any other canonical answer: 500 SRT-010 with no detail and nothing stored. */
    data object Failed : RecordingOutcome {
        const val ERROR_CODE = "SRT-010"
        override val httpStatus: Int = HTTP_INTERNAL_ERROR
    }

    private companion object {
        const val HTTP_ACCEPTED = 202
        const val HTTP_INTERNAL_ERROR = 500
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_CONFLICT = 409
    }
}

object RecordingMapping {
    private const val FIRST_VERSION = 1L
    private val CLIENT_ERRORS = 400..499

    /** The canonical rejection codes relayed as received (INV-Q-11018); exact equality, no case folding or trimming. */
    val RELAY_CODES: Set<String> = setOf("ING-005", "ING-011", "ING-012", "ING-016", "ING-019")

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

            is CanonicalAnswer.Held -> {
                RecordingOutcome.Held(answer.docUuid, answer.version, answer.reasons)
            }

            is CanonicalAnswer.Rejected -> {
                mapRejected(answer)
            }
        }

    private fun mapRejected(answer: CanonicalAnswer.Rejected): RecordingOutcome {
        val code = answer.errorCode
        return when {
            code == RecordingOutcome.Stale.ERROR_CODE -> {
                RecordingOutcome.Stale
            }

            code != null && code in RELAY_CODES && answer.httpStatus in CLIENT_ERRORS -> {
                RecordingOutcome.Relayed(
                    answer.httpStatus,
                    code,
                )
            }

            else -> {
                RecordingOutcome.Failed
            }
        }
    }
}
