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
        if (guarded { integrity.allowsExport(job.tenant) }.getOrElse { return Outcome.Failed(named(it)) }) {
            null
        } else {
            Outcome.Failed("chain integrity does not allow the export (DEP-007)")
        }

    private fun build(job: ExportJob): Outcome {
        val candidates = mutableListOf<PackageCandidate>()
        for (contentClass in PackageContentClass.entries) {
            val source = sources.source(contentClass) ?: return Outcome.Failed("no source for ${contentClass.name}")
            candidates += guarded { source.candidates(job.tenant) }.getOrElse { return Outcome.Failed(named(it)) }
        }
        val assembled =
            when (
                val result =
                    AuditPackAssembler.assemble(
                        PackageScope.WholeTenant(job.tenant),
                        candidates,
                        clock.instant(),
                    )
            ) {
                is AssemblyResult.Refused -> return Outcome.Failed(
                    "assembly refused: ${result.reason} ${result.itemName}",
                )

                is AssemblyResult.Assembled -> result
            }
        val verification = verify(assembled.files)
        if (verification is PackageVerification.Invalid) {
            return Outcome.Failed(
                "package invalid: ${verification.problems.take(PROBLEMS_SHOWN).joinToString(", ") { it.code.name }}",
            )
        }
        val manifestRef =
            guarded { store.store(job.tenant, job.offboardingRef, assembled.files) }.getOrElse {
                return Outcome.Failed(named(it))
            }
        return Outcome.Stored(manifestRef, assembled.included)
    }

    private fun verify(files: PackageFiles): PackageVerification =
        PackageVerifier.verify(
            object : PackageSource {
                override fun paths(): Set<String> = files.paths.toSet()

                override fun read(path: String): ByteArray = files.bytes(path) ?: error("missing $path")
            },
        )

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
