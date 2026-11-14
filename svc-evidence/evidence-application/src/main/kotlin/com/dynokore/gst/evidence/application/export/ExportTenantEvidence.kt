package com.dynokore.gst.evidence.application.export

import com.dynokore.platform.evidencestore.exportpackage.AssemblyResult
import com.dynokore.platform.evidencestore.exportpackage.AuditPackAssembler
import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageFiles
import com.dynokore.platform.evidencestore.exportpackage.PackageScope
import com.dynokore.platform.evidencestore.exportpackage.PackageSource
import com.dynokore.platform.evidencestore.exportpackage.PackageVerification
import com.dynokore.platform.evidencestore.exportpackage.PackageVerifier
import java.time.Clock

/**
 * Builds, verifies and records the whole-tenant export of one request (INV-REQ-877). It runs in the caller's
 * transaction. Fail closed (OWASP ASVS V1): any problem is a FAILED job with a reason and one FAILED result,
 * never a partial success. A job already READY or FAILED is returned unchanged and publishes nothing (one result
 * per request).
 */
class ExportTenantEvidence(
    private val sources: ExportClassSources,
    private val jobs: ExportJobs,
    private val store: ExportPackageStore,
    private val integrity: ExportChainIntegrity,
    private val publisher: ExportResultPublisher,
    private val clock: Clock,
) {
    fun execute(request: ExportRequest): ExportJob {
        val job = jobs.find(request.tenant, request.offboardingRef) ?: jobs.register(request)
        return if (job.state != ExportState.REQUESTED) job else run(job)
    }

    private fun run(job: ExportJob): ExportJob {
        val outcome =
            unbound()
                ?: integrityRefusal(job)
                ?: build(job)
        return when (outcome) {
            is Outcome.Failed -> fail(job, outcome.reason)
            is Outcome.Stored -> ready(job, outcome)
        }
    }

    private fun unbound(): Outcome.Failed? =
        PackageContentClass.entries
            .filter { sources.source(it) == null }
            .takeIf { it.isNotEmpty() }
            ?.let { Outcome.Failed("no source for ${it.joinToString(", ") { c -> c.name }}") }

    private fun integrityRefusal(job: ExportJob): Outcome.Failed? =
        try {
            if (port {
                    integrity.allowsExport(job.tenant)
                }
            ) {
                null
            } else {
                Outcome.Failed("chain integrity does not allow the export (DEP-007)")
            }
        } catch (stop: Stop) {
            Outcome.Failed(stop.reason)
        }

    private fun build(job: ExportJob): Outcome =
        try {
            val assembled = assemble(job, candidates(job))
            val verification = verify(assembled.files)
            if (verification is PackageVerification.Invalid) {
                val codes = verification.problems.take(PROBLEMS_SHOWN).joinToString(", ") { it.code.name }
                throw Stop("package invalid: $codes")
            }
            val manifestRef = port { store.store(job.tenant, job.offboardingRef, assembled.files) }
            Outcome.Stored(manifestRef, assembled.included)
        } catch (stop: Stop) {
            Outcome.Failed(stop.reason)
        }

    private fun candidates(job: ExportJob): List<PackageCandidate> =
        PackageContentClass.entries.flatMap { contentClass ->
            val source = sources.source(contentClass) ?: throw Stop("no source for ${contentClass.name}")
            port { source.candidates(job.tenant) }
        }

    private fun assemble(
        job: ExportJob,
        candidates: List<PackageCandidate>,
    ): AssemblyResult.Assembled =
        when (
            val result =
                AuditPackAssembler.assemble(
                    PackageScope.WholeTenant(job.tenant),
                    candidates,
                    clock.instant(),
                )
        ) {
            is AssemblyResult.Refused -> throw Stop("assembly refused: ${result.reason} ${result.itemName}")
            is AssemblyResult.Assembled -> result
        }

    /** Calls a port; any exception stops the export with the exception class name only. */
    private fun <T> port(block: () -> T): T = guarded(block).getOrElse { throw Stop(named(it)) }

    private class Stop(
        val reason: String,
    ) : RuntimeException(reason)

    private fun ready(
        job: ExportJob,
        stored: Outcome.Stored,
    ): ExportJob {
        val contents = PackageContentClass.entries.map { it.name }
        val done = jobs.markReady(job, stored.manifestRef, stored.itemCount, contents)
        publisher.publish(result(done, ExportState.READY, contents, null))
        return done
    }

    private fun fail(
        job: ExportJob,
        reason: String,
    ): ExportJob {
        val cut = reason.take(MAX_REASON)
        val done = jobs.markFailed(job, cut)
        publisher.publish(result(done, ExportState.FAILED, emptyList(), cut))
        return done
    }

    private sealed interface Outcome {
        data class Failed(
            val reason: String,
        ) : Outcome

        data class Stored(
            val manifestRef: String,
            val itemCount: Int,
        ) : Outcome
    }

    private companion object {
        const val MAX_REASON = 500
        const val PROBLEMS_SHOWN = 5
    }
}

private fun verify(files: PackageFiles): PackageVerification =
    PackageVerifier.verify(
        object : PackageSource {
            override fun paths(): Set<String> = files.paths.toSet()

            override fun read(path: String): ByteArray = files.bytes(path) ?: error("missing $path")
        },
    )

private fun result(
    job: ExportJob,
    state: ExportState,
    contents: List<String>,
    reason: String?,
) = ExportResult(job.tenant.toString(), job.offboardingRef, job.exportRef, state, contents, reason)

@Suppress("TooGenericExceptionCaught") // a failing port is a FAILED job, never a rethrow (fail closed)
private fun <T> guarded(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(e)
    }

private fun named(e: Throwable): String = e.javaClass.name
