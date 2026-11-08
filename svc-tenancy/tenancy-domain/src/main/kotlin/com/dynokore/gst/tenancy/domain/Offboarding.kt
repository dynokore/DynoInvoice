package com.dynokore.gst.tenancy.domain

/** The scope of an offboarding (INV-REQ-877): one entity's exit or the whole tenant's. */
enum class OffboardingScope { ENTITY, TENANT }

/** The state of an offboarding record; only COMPLETED ends it (INV-REQ-877). */
enum class OffboardingState { REQUESTED, COMPLETED, FAILED }

/** The state of the export a record carries. */
enum class ExportState { REQUESTED, READY, FAILED }

/** The six members an export must list before the record may be COMPLETED (INV-REQ-877 behavior 2). */
enum class ExportContent {
    TRANSMITTED_XML,
    ACKNOWLEDGEMENTS,
    DECISION_TRACES,
    RECONCILIATION_WORK_PAPERS,
    SOURCE_DOCUMENT_REFERENCES,
    AUDIT_LOG,
}

/** The named retention custody holder (INV-REQ-956); [contactMobile] is optional and stored unchanged (INV-Q-5020). */
data class RetentionCustodyHolder(
    val name: String,
    val contactEmail: String,
    val contactMobile: String? = null,
)

/** The recorded acknowledgement that overrides the block on unsubmitted in-scope documents (INV-REQ-956). */
data class UnsubmittedOverride(
    val acknowledgedBy: String,
    val statement: String,
)

/** The manifest the export port answers. */
data class ExportManifest(
    val exportRef: String,
    val state: ExportState,
    val contents: List<ExportContent>,
)

/** The rules of offboarding that need no port (INV-REQ-877, INV-REQ-956). */
object OffboardingRules {
    const val HANDOVER_REF_MAX = 200
    const val HOLDER_NAME_MAX = 200
    const val HOLDER_EMAIL_MAX = 254

    /** TNC-009: the holder's name or contact email is blank after trimming. */
    fun holderIsBlank(holder: RetentionCustodyHolder): Boolean = holder.name.isBlank() || holder.contactEmail.isBlank()

    /** COMPLETED only for a READY export listing all six members; a FAILED export fails the record. */
    fun recordState(manifest: ExportManifest): OffboardingState =
        when {
            manifest.state == ExportState.FAILED -> {
                OffboardingState.FAILED
            }

            manifest.state == ExportState.READY && manifest.contents.toSet().containsAll(ExportContent.entries) -> {
                OffboardingState.COMPLETED
            }

            else -> {
                OffboardingState.REQUESTED
            }
        }

    /** A record in one of these states blocks a new request for the same tenant, scope and entity (TNC-010). */
    fun blocksNewRequest(state: OffboardingState): Boolean = state != OffboardingState.FAILED
}
