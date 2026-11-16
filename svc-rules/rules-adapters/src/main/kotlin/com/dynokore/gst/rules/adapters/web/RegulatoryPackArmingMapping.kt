package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.packs.CompiledPackSchematron
import com.dynokore.gst.rules.adapters.packs.SchematronPackCompiler
import com.dynokore.gst.rules.adapters.persistence.PersistedPackRegistry
import com.dynokore.gst.rules.application.AcknowledgePackDeltaResult
import com.dynokore.gst.rules.application.ArmRegulatoryPackResult
import com.dynokore.gst.rules.application.ArmingStore
import com.dynokore.gst.rules.application.ArmingWrite
import com.dynokore.gst.rules.contract.model.RegulatoryPack
import com.dynokore.gst.rules.domain.pack.GraceDays
import com.dynokore.gst.rules.domain.pack.ReviewPolicy
import com.dynokore.platform.integrationcanon.schematron.CompilationListener
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.fence.CompileOutcome
import com.dynokore.platform.rulepackkernel.fence.FenceOutcome
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
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
            ArmRegulatoryPackResult.Armed -> {
                null
            }

            ArmRegulatoryPackResult.Denied -> {
                closed(ClosedErrorCode.ACCESS_DENIED, DENIED_DETAIL)
            }

            ArmRegulatoryPackResult.NotFound -> {
                closed(ClosedErrorCode.NOT_FOUND, null)
            }

            is ArmRegulatoryPackResult.NotArmable -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "the pack is not APPROVED")
            }

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
            is AcknowledgePackDeltaResult.Acknowledged -> {
                null
            }

            AcknowledgePackDeltaResult.Denied -> {
                closed(ClosedErrorCode.ACCESS_DENIED, DENIED_DETAIL)
            }

            AcknowledgePackDeltaResult.NotFound -> {
                closed(ClosedErrorCode.NOT_FOUND, null)
            }

            is AcknowledgePackDeltaResult.NotOpen -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "the pack is neither STAGED nor APPROVED")
            }

            AcknowledgePackDeltaResult.NoCompleteDelta -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "no completed dry-run delta exists for the tenant")
            }

            AcknowledgePackDeltaResult.NoGraceWindow -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, NO_WINDOW_DETAIL)
            }

            is AcknowledgePackDeltaResult.WindowClosed -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "the grace window has ended")
            }
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
    ): Int? =
        held[packType to version]
            ?.stylesheets
            ?.values
            ?.onEach { it.validate(document) }
            ?.size

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
            val compiled = (outcome as CompileOutcome.Compiled<CompiledPackSchematron>).compiled
            hold.put(write.packType, write.version, compiled)
        }
        delegate.arm(write)
    }
}

/**
 * The reading of the replica text of VP-39, VP-57 and VP-79 (INV-T-4977, allowlist input validation OWASP ASVS V5.1.3,
 * NIST SP 800-53 SI-10, fail closed). The text is the JSON `value` of the `PolicyChanged` payload: an array of exactly
 * one option object `{"key": <option key>, "value": <integer, typed options only>}`. An absent value (null) is the
 * catalogue default of the row; every other text that is not listed here throws [unavailable] (503 SRT-013), with no
 * guess and no second accepted form.
 */
object ReviewPolicyValues {
    const val VP39_REVIEW = "REVIEW_THEN_ACTIVATE"
    const val VP39_NOTICE = "ACTIVATE_WITH_NOTICE"
    const val VP57_PLATFORM_MAXIMUM = "PLATFORM_MAXIMUM"
    const val VP57_SHORTER = "SHORTER"
    const val VP79_DAYS_14 = "DAYS_14"
    const val VP79_CUSTOM = "CUSTOM"
    const val DEFAULT_MAXIMUM_DAYS = 14

    private const val KEY = "key"
    private const val VALUE = "value"
    private val mapper: JsonMapper =
        JsonMapper
            .builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()

    /** VP-39 at the tenant's scope. */
    fun reviewPolicy(text: String?): ReviewPolicy =
        if (text == null) {
            ReviewPolicy.REVIEW_IN_GRACE_WINDOW
        } else {
            when (option(text, mapOf(VP39_REVIEW to false, VP39_NOTICE to false)).first) {
                VP39_REVIEW -> ReviewPolicy.REVIEW_IN_GRACE_WINDOW
                else -> ReviewPolicy.NOTICE_ONLY
            }
        }

    /** VP-57 at the tenant's scope: none stored means the platform maximum applies. */
    fun storedDays(text: String?): GraceDays? {
        if (text == null) return null
        val (key, days) = option(text, mapOf(VP57_PLATFORM_MAXIMUM to false, VP57_SHORTER to true))
        return if (key == VP57_PLATFORM_MAXIMUM) null else GraceDays(days!!)
    }

    /** VP-79 at installation scope. */
    fun maximumDays(text: String?): GraceDays {
        if (text == null) return GraceDays(DEFAULT_MAXIMUM_DAYS)
        val (key, days) = option(text, mapOf(VP79_DAYS_14 to null, VP79_CUSTOM to true))
        return GraceDays(if (key == VP79_DAYS_14) DEFAULT_MAXIMUM_DAYS else days!!)
    }

    /**
     * Parses one option. [allowed] maps each option key to whether it takes a day count: true required, false none,
     * null optional and, when present, equal to [DEFAULT_MAXIMUM_DAYS].
     */
    private fun option(
        text: String,
        allowed: Map<String, Boolean?>,
    ): Pair<String, Int?> {
        val node =
            try {
                mapper.readTree(text)
            } catch (_: JacksonException) {
                throw unreadable()
            }
        if (!node.isArray || node.size() != 1 || !node[0].isObject) throw unreadable()
        val option = node[0]
        if (option.propertyNames().asSequence().any { it != KEY && it != VALUE }) throw unreadable()
        val key = option[KEY]?.takeIf { it.isString }?.stringValue() ?: throw unreadable()
        if (!allowed.containsKey(key)) throw unreadable()
        val value = option[VALUE]
        val days =
            if (value == null) {
                null
            } else {
                if (!value.isIntegralNumber || !value.canConvertToInt()) throw unreadable()
                value.intValue()
            }
        when (allowed.getValue(key)) {
            true -> if (days == null || days < 1) throw unreadable()
            false -> if (days != null) throw unreadable()
            null -> if (days != null && days != DEFAULT_MAXIMUM_DAYS) throw unreadable()
        }
        return key to (days ?: if (allowed.getValue(key) == null) DEFAULT_MAXIMUM_DAYS else null)
    }

    private fun unreadable() = unavailable("the review policy value is not readable")
}
