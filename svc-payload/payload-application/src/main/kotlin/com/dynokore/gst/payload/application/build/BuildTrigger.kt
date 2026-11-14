package com.dynokore.gst.payload.application.build

import java.util.UUID

/** The canonical document of a version, stored when the version is consumed. */
fun interface CanonicalInputs {
    /** True when a row was newly stored; the first stored document of a version wins. */
    fun store(
        docUuid: UUID,
        version: Int,
        canonicalDocument: String,
    ): Boolean
}

/** The stored canonical inputs, read side. */
fun interface BuildInputs {
    fun exists(
        docUuid: UUID,
        version: Int,
    ): Boolean
}

/** The stored submission type decisions, read side. */
fun interface TypeDecisions {
    fun exists(
        docUuid: UUID,
        version: Int,
    ): Boolean
}

/** The build queue. */
fun interface BuildJobs {
    /** True when a job was newly enqueued; the key is `build:<docUuid>:<version>`, so a repeat enqueues nothing. */
    fun enqueueOnce(
        docUuid: UUID,
        version: Int,
    ): Boolean
}

/**
 * Enqueues the build job of a version once both its canonical input and its type decision are stored, in either order.
 * It opens no transaction: it runs in the transaction of the caller that stored the fact.
 */
class BuildTrigger(
    private val inputs: BuildInputs,
    private val decisions: TypeDecisions,
    private val jobs: BuildJobs,
) {
    /** Called after the canonical input is stored; true when a job was newly enqueued. */
    fun onInputStored(
        docUuid: UUID,
        version: Int,
    ): Boolean = enqueueWhenComplete(docUuid, version)

    /** Called after the type decision is stored; true when a job was newly enqueued. */
    fun onDecisionStored(
        docUuid: UUID,
        version: Int,
    ): Boolean = enqueueWhenComplete(docUuid, version)

    private fun enqueueWhenComplete(
        docUuid: UUID,
        version: Int,
    ): Boolean =
        inputs.exists(docUuid, version) && decisions.exists(docUuid, version) && jobs.enqueueOnce(docUuid, version)
}
