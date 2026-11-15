package com.dynokore.gst.evidence.application.export

import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageFiles
import com.dynokore.platform.tenancykernel.TenantId

/** The state of a whole-tenant export job (INV-REQ-877): it starts REQUESTED and ends READY or FAILED. */
enum class ExportState { REQUESTED, READY, FAILED }

/** What an export covers: the whole tenant, or one entity of it. */
enum class ExportScope { TENANT, ENTITY }

/**
 * What one export asks for: the tenant, the offboarding it belongs to, the export reference, the scope, the entity
 * reference (only for scope ENTITY) and the id of the event that requested it.
 */
data class ExportRequest(
    val tenant: TenantId,
    val offboardingRef: String,
    val exportRef: String,
    val scope: ExportScope,
    val entityRef: String?,
    val requestedEventId: String,
) {
    init {
        require(exportRef.isNotBlank()) { "exportRef must not be blank" }
        require(requestedEventId.isNotBlank()) { "requestedEventId must not be blank" }
        when (scope) {
            ExportScope.ENTITY -> require(!entityRef.isNullOrBlank()) { "scope ENTITY needs an entityRef" }
            ExportScope.TENANT -> require(entityRef == null) { "scope TENANT takes no entityRef" }
        }
    }
}

/** One export job. [manifestRef], [itemCount] and [contents] are set only in READY; [failureReason] only in FAILED. */
data class ExportJob(
    val tenant: TenantId,
    val offboardingRef: String,
    val exportRef: String,
    val state: ExportState,
    val manifestRef: String? = null,
    val itemCount: Int? = null,
    val contents: List<String> = emptyList(),
    val failureReason: String? = null,
    val finishedAt: java.time.Instant? = null,
)

/** The candidates of one content class for a tenant. Reads only; it never writes. */
fun interface ExportClassSource {
    fun candidates(tenant: TenantId): List<PackageCandidate>
}

/**
 * The source of each content class. A class with no entry, or a null entry, is unbound: the export does not
 * complete without a source for all six classes (INV-REQ-877).
 */
class ExportClassSources(
    private val sources: Map<PackageContentClass, ExportClassSource?>,
) {
    fun source(contentClass: PackageContentClass): ExportClassSource? = sources[contentClass]
}

/** The export jobs of the caller's tenant transaction. */
interface ExportJobs {
    /** The job of the offboarding, or null when none is registered. */
    fun find(
        tenant: TenantId,
        offboardingRef: String,
    ): ExportJob?

    /**
     * The newest job of the tenant for the scope by requested_at (whatever its state), or null when none.
     */
    fun findLatest(
        tenant: TenantId,
        scope: ExportScope,
    ): ExportJob?

    /** Registers a REQUESTED job and returns it; idempotent per offboarding. */
    fun register(request: ExportRequest): ExportJob

    fun markReady(
        job: ExportJob,
        manifestRef: String,
        itemCount: Int,
        contents: List<String>,
    ): ExportJob

    fun markFailed(
        job: ExportJob,
        reason: String,
    ): ExportJob
}

/** Stores a verified package and returns its manifest reference. */
fun interface ExportPackageStore {
    fun store(
        tenant: TenantId,
        offboardingRef: String,
        files: PackageFiles,
    ): String
}

/** The chain integrity gate of DEP-007: false refuses the export. */
fun interface ExportChainIntegrity {
    fun allowsExport(tenant: TenantId): Boolean
}

/** The outcome of one export, published once per request. */
data class ExportResult(
    val tenantRef: String,
    val offboardingRef: String,
    val exportRef: String,
    val state: ExportState,
    val contents: List<String>,
    val failureReason: String?,
)

fun interface ExportResultPublisher {
    fun publish(result: ExportResult)
}
