package com.dynokore.gst.payload.application.build

import com.dynokore.gst.payload.application.payload.PayloadReadyFacts
import com.dynokore.gst.payload.application.payload.PublishPayloadReady
import com.dynokore.gst.payload.domain.PayloadSizeCheck
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** The stored canonical input of a version; the last three members are null when the event carried none. */
data class BuildInput(
    val entityRef: String,
    val canonicalDocument: String,
    val aggregateId: String?,
    val direction: String?,
    val counterpartyOnNetwork: Boolean?,
)

/** The result of projecting a canonical document: the payload bytes, or a blocking validation trace. */
sealed interface Projection {
    class Built(
        val bytes: ByteArray,
    ) : Projection

    data class Blocked(
        val validationTrace: String,
    ) : Projection
}

/** The facts of one `PayloadRejected` event: a catalogue [code] (PAY-010) or the opaque [validationTrace]. */
data class PayloadRejectedFacts(
    val entityRef: String,
    val docUuid: UUID,
    val version: Int,
    val code: String?,
    val validationTrace: String?,
)

/** Stages a `PayloadRejected` event for release in the caller's transaction. */
fun interface PayloadRejectedPublisher {
    fun stage(facts: PayloadRejectedFacts)
}

fun interface BuildInputsReader {
    fun read(
        docUuid: UUID,
        version: Int,
    ): BuildInput?
}

fun interface TypeDecisionReader {
    /** TYPE_1A, TYPE_1B, TYPE_2 or TYPE_3; null when submission-svc has not decided. */
    fun typeOf(
        docUuid: UUID,
        version: Int,
    ): String?
}

fun interface PayloadProjector {
    fun project(
        input: BuildInput,
        submissionType: String,
    ): Projection
}

fun interface SpecificationFamilySource {
    fun familyOf(entityRef: String): String
}

fun interface BuildOutcomes {
    /** True exactly once per (docUuid, version): records that the version is being built. */
    fun firstTime(
        docUuid: UUID,
        version: Int,
    ): Boolean
}

/** The input or the type decision is not stored yet; the job is retried and nothing is staged. */
class BuildNotReady(
    message: String,
) : RuntimeException(message)

/** The submission types of model/architecture/submission-types.yaml: decision spelling and PayloadReady spelling. */
enum class SubmissionType(
    val decision: String,
    val payloadSpelling: String,
) {
    TYPE_1A("TYPE_1A", "1A"),
    TYPE_1B("TYPE_1B", "1B"),
    TYPE_2("TYPE_2", "2"),
    TYPE_3("TYPE_3", "3"),
    ;

    companion object {
        fun fromDecision(text: String): SubmissionType =
            entries.firstOrNull { it.decision == text } ?: error("unknown submission type: $text")
    }
}

/**
 * Builds the payload of one version and stages `PayloadReady` or `PayloadRejected`. It opens no transaction: the
 * claimed job runs it in one.
 */
@Suppress("LongParameterList") // the seven ports of the build
class BuildPayload(
    private val inputs: BuildInputsReader,
    private val decisions: TypeDecisionReader,
    private val projector: PayloadProjector,
    private val families: SpecificationFamilySource,
    private val ready: PublishPayloadReady,
    private val rejected: PayloadRejectedPublisher,
    private val outcomes: BuildOutcomes,
) {
    fun run(
        docUuid: UUID,
        version: Int,
    ) {
        val input = inputs.read(docUuid, version) ?: throw BuildNotReady("no canonical input for $docUuid:$version")
        val decision =
            decisions.typeOf(docUuid, version) ?: throw BuildNotReady("no type decision for $docUuid:$version")
        if (!outcomes.firstTime(docUuid, version)) return
        when (val projection = projector.project(input, decision)) {
            is Projection.Blocked -> {
                reject(input, docUuid, version, null, projection.validationTrace)
            }

            is Projection.Built -> {
                if (PayloadSizeCheck.check(projection.bytes.size.toLong(), 0).blocked) {
                    reject(input, docUuid, version, PayloadSizeCheck.PAY_010, null)
                } else {
                    publish(input, docUuid, version, decision, projection.bytes)
                }
            }
        }
    }

    private fun reject(
        input: BuildInput,
        docUuid: UUID,
        version: Int,
        code: String?,
        trace: String?,
    ) = rejected.stage(PayloadRejectedFacts(input.entityRef, docUuid, version, code, trace))

    private fun publish(
        input: BuildInput,
        docUuid: UUID,
        version: Int,
        decision: String,
        bytes: ByteArray,
    ) = ready.publish(
        PayloadReadyFacts(
            entityRef = input.entityRef,
            docUuid = docUuid,
            version = version,
            submissionType = SubmissionType.fromDecision(decision).payloadSpelling,
            specificationVersion = families.familyOf(input.entityRef),
            payloadBytes = bytes,
            payloadSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            aggregateId = input.aggregateId,
        ),
    )
}
