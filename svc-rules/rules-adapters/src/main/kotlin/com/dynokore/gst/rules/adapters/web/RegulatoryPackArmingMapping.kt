package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.packs.CompiledPackSchematron
import com.dynokore.gst.rules.adapters.packs.SchematronPackCompiler
import com.dynokore.gst.rules.adapters.persistence.PersistedPackRegistry
import com.dynokore.gst.rules.application.AcknowledgePackDeltaResult
import com.dynokore.gst.rules.application.ArmingStore
import com.dynokore.gst.rules.application.ArmingWrite
import com.dynokore.platform.integrationcanon.schematron.CompilationListener
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.fence.CompileOutcome
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.gst.rules.application.ArmRegulatoryPackResult
import com.dynokore.gst.rules.contract.model.RegulatoryPack
import com.dynokore.platform.rulepackkernel.fence.FenceOutcome
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import java.time.LocalDate

/**
 * The answers of `armRegulatoryPack` and `acknowledgePackDelta` against the pinned contract (INV-T-4977): 200
 * `RegulatoryPack` for an arming (ARMED) and for an acknowledgement (ACKNOWLEDGED), 403 SRT-004 with a fixed detail,
 * 404 SRT-005, 409 SRT-008 for every precondition that does not hold (including the private form's `NoGraceWindow`,
 * which the real private form never reaches because its key has no PRIVATE cell), 422 RUL-005 for an abandoned
 * activation. No detail names a person. The 503 SRT-013 is [unavailable], raised by the permission ports.
 */
object RegulatoryPackArmingMapping {
    private const val UNPROCESSABLE = 422
    private const val DENIED_DETAIL = "the operation is not permitted"
    private const val NO_WINDOW_DETAIL = "the deployment has no grace window to acknowledge in"

    fun refusalOf(result: ArmRegulatoryPackResult): MappingRefusal? =
        when (result) {
            ArmRegulatoryPackResult.Armed -> null

            ArmRegulatoryPackResult.Denied -> closed(ClosedErrorCode.ACCESS_DENIED, DENIED_DETAIL)

            ArmRegulatoryPackResult.NotFound -> closed(ClosedErrorCode.NOT_FOUND, null)

            is ArmRegulatoryPackResult.NotArmable -> closed(ClosedErrorCode.STALE_AGGREGATE, "the pack is not APPROVED")

            is ArmRegulatoryPackResult.EffectiveDateMismatch -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "the effective date differs from the staged effective date")
            }

            is ArmRegulatoryPackResult.WindowOpen -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "a tenant's grace window is still open")
            }

            is ArmRegulatoryPackResult.AcknowledgementsPending -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "instances have not acknowledged the pack yet")
            }

            is ArmRegulatoryPackResult.Aborted -> {
                MappingRefusal(
                    UNPROCESSABLE,
                    FenceOutcome.ERROR_CODE,
                    "Pack activation abandoned",
                    "the activation was abandoned; no instance is armed",
                )
            }
        }

    fun refusalOf(result: AcknowledgePackDeltaResult): MappingRefusal? =
        when (result) {
            is AcknowledgePackDeltaResult.Acknowledged -> null

            AcknowledgePackDeltaResult.Denied -> closed(ClosedErrorCode.ACCESS_DENIED, DENIED_DETAIL)

            AcknowledgePackDeltaResult.NotFound -> closed(ClosedErrorCode.NOT_FOUND, null)

            is AcknowledgePackDeltaResult.NotOpen -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "the pack is neither STAGED nor APPROVED")
            }

            AcknowledgePackDeltaResult.NoCompleteDelta -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "no completed dry-run delta exists for the tenant")
            }

            AcknowledgePackDeltaResult.NoGraceWindow -> closed(ClosedErrorCode.STALE_AGGREGATE, NO_WINDOW_DETAIL)

            is AcknowledgePackDeltaResult.WindowClosed -> closed(ClosedErrorCode.STALE_AGGREGATE, "the grace window has ended")
        }

    fun armed(
        packType: String,
        version: String,
        effectiveFrom: LocalDate,
    ) = RegulatoryPack(
        packType = packType,
        version = version,
        status = RegulatoryPack.Status.ARMED,
        effectiveFrom = effectiveFrom,
    )

    fun acknowledged(
        packType: String,
        version: String,
        result: AcknowledgePackDeltaResult.Acknowledged,
    ) = RegulatoryPack(
        packType = packType,
        version = version,
        status = RegulatoryPack.Status.ACKNOWLEDGED,
        effectiveFrom = result.effectiveFrom,
        graceWindowEnd = result.graceWindowEnd,
    )

    private fun closed(
        code: ClosedErrorCode,
        detail: String?,
    ) = MappingRefusal(code.httpStatus, code.code, code.title, detail)
}

/** Told once for every stylesheet compiled at activation. */
fun interface CompileObserver {
    fun compiled()
}

/** The compiled stylesheets of the armed Schematron packs, by pack version; shared and immutable once stored. */
class CompiledPackHold {
    private val held = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, CompiledPackSchematron>()

    fun put(
        packType: String,
        version: String,
        compiled: CompiledPackSchematron,
    ) {
        held[packType to version] = compiled
    }

    fun find(
        packType: String,
        version: String,
    ): CompiledPackSchematron? = held[packType to version]

    /** Validates [document] with every held stylesheet of the pack version; the number of stylesheets run, or null. */
    fun validateAll(
        packType: String,
        version: String,
        document: ByteArray,
    ): Int? = held[packType to version]?.stylesheets?.values?.onEach { it.validate(document) }?.size

    /** The file names of the held stylesheets of the pack version, or null when none is held. */
    fun stylesheetNames(
        packType: String,
        version: String,
    ): Set<String>? = held[packType to version]?.stylesheets?.keys
}

/** Compiles a Schematron pack before delegating the arming write; other pack types pass through unchanged. */
class CompilingArmingStore(
    private val delegate: ArmingStore,
    private val registry: PersistedPackRegistry,
    observer: CompileObserver,
    private val hold: CompiledPackHold,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
) : ArmingStore {
    private val compiler = SchematronPackCompiler(CompilationListener { observer.compiled() })

    override fun arm(write: ArmingWrite) {
        if (write.packType == "SCHEMATRON") {
            val artefact =
                platform.bindPlatform("pack-compile") {
                    transactions.inTransaction {
                        registry
                            .resolve(PackType("SCHEMATRON"), null, write.effectiveFrom)
                            ?.takeIf { it.manifest.version.value == write.version }
                            ?.let { it.manifest to registry.artefactOf(it) }
                    }
                }
            val (manifest, contents) =
                checkNotNull(artefact?.takeIf { it.second != null }) { "no staged artefact for the pack to compile" }
            val outcome = compiler.compile(manifest, checkNotNull(contents).contents)
            check(outcome is CompileOutcome.Compiled<*>) { "the pack does not compile" }
            @Suppress("UNCHECKED_CAST")
            hold.put(write.packType, write.version, (outcome as CompileOutcome.Compiled<CompiledPackSchematron>).compiled)
        }
        delegate.arm(write)
    }
}
