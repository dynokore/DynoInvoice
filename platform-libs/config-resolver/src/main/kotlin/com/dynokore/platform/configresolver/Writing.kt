package com.dynokore.platform.configresolver

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.tenancykernel.BlankText
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The verified principal and the roles it holds for the scope concerned. The command API takes the roles
 * from the token, never from the request body.
 */
data class Actor(
    val principal: String,
    val roles: Set<String>,
) {
    init {
        require(BlankText.isActor(principal)) {
            "principal must be not blank and hold no control character (INV-Q-4684)"
        }
    }
}

/**
 * `setPolicy` (INV-REQ-1243 behaviour #1): the new value of [key] at [level] of [scope], effective from
 * [effectiveFrom], with a reason. [source] `PROFILE` with the profile id and version is how
 * `applyOperatingProfile` writes (INV-Q-2979); the setting role is checked for every source (INV-Q-3169).
 *
 * @property relaxation the complete relaxation acknowledged for a LOOSER option (INV-Q-3138).
 */
data class SetPolicyCommand(
    val key: VariationPointKey,
    val scope: PolicyScope,
    val level: ScopeLevel,
    val selected: List<SelectedOption>,
    val effectiveFrom: LocalDate,
    val reason: String?,
    val actor: Actor,
    val correlation: CorrelationId,
    val causation: CausationId,
    val source: SettingSource = SettingSource.OVERRIDE,
    val profileId: String? = null,
    val profileVersion: String? = null,
    val relaxation: RelaxationId? = null,
)

/**
 * `acknowledgeControlRelaxation` (INV-REQ-1244 behaviour #2, INV-NOTE-7 SCR-40.5): [actor], holding
 * [asRole], acknowledges the looser value [selected] of [key] at [level] of [scope] from [effectiveFrom].
 * Without [relaxation] a new relaxation starts; with it, the acknowledgement joins that relaxation, whose
 * target, value and effective date must be the same (INV-Q-3138).
 */
data class AcknowledgeRelaxationCommand(
    val key: VariationPointKey,
    val scope: PolicyScope,
    val level: ScopeLevel,
    val selected: List<SelectedOption>,
    val effectiveFrom: LocalDate,
    val actor: Actor,
    val asRole: String,
    val correlation: CorrelationId,
    val causation: CausationId,
    val relaxation: RelaxationId? = null,
    val reason: String? = null,
)

/**
 * An accepted change: the stored version, the value it replaced at its scope on its effective date (`null`
 * when nothing applied, e.g. a must-elect row), and the events written.
 *
 * @property policyChangedEventId `null` when the service has no outbox (INV-Q-3142).
 */
data class PolicyChange(
    val version: SettingVersion,
    val prior: ResolvedSetting?,
    val auditEventId: String,
    val policyChangedEventId: String?,
)

/** Why a write was refused. Every refusal stores nothing (INV-Q-2930). */
enum class PolicyRefusal {
    /** The row does not allow the scope level (INV-AC-2227). */
    SCOPE_NOT_ALLOWED,

    /** The actor holds none of the row's setting roles (INV-AC-2224, INV-Q-3169). */
    ROLE_NOT_ALLOWED,

    /** No reason given (INV-AC-2229). */
    REASON_REQUIRED,

    /** A planned row cannot be set (INV-REQ-1251). */
    PLANNED,

    /**
     * A cross-scope rule or installation bound config-resolver cannot evaluate: an installation bound with no
     * installation parameter row, or a cross-scope row whose options do not carry the same directed fields
     * (INV-Q-3173, INV-T-3176).
     */
    NOT_SUPPORTED,

    /** A directed field looser than a broader scope's value of the same row (INV-REQ-780 b1, INV-Q-2936). */
    LOOSER_THAN_BROADER,

    /** A value beyond the installation parameter row bounding it (INV-REQ-1248, INV-Q-3202, INV-AC-2491). */
    BEYOND_INSTALLATION_BOUND,

    /** The broader value or installation bound cannot be evaluated ([BoundUnavailable]); fail closed. */
    BOUND_UNAVAILABLE,

    /** An option the row does not have, or does not offer. */
    UNKNOWN_OPTION,

    /** The selection's shape is wrong: empty, several keys on a single-select row, an exclusive key with others. */
    SELECTION_INVALID,

    /** The value is not of the option's shape or outside its bounds (INV-REQ-1239 behaviour #3). */
    OUT_OF_BOUNDS,

    /** The owning service refused, or could not check, what only it can see (INV-Q-3167). */
    CONSTRAINT_REFUSED,

    /** A looser option of a row that offers none (INV-AC-2230, INV-REQ-780). */
    LOOSER_NOT_OFFERED,

    /** A looser option at installation scope: not in 1.1.0 (INV-Q-3142). */
    LOOSER_AT_INSTALLATION,

    /** A looser option whose catalogue entry names no acknowledger (INV-Q-3134). */
    NO_ACCOUNTABLE_ROLE,

    /** A looser option set without a complete, matching, unused relaxation (INV-AC-205, INV-Q-3138). */
    RELAXATION_REQUIRED,

    /** The acknowledging role is not one the option names, or the actor does not hold it. */
    ACKNOWLEDGER_NOT_ALLOWED,

    /** The role has already acknowledged this relaxation, or the principal already acknowledged it in another role. */
    ACKNOWLEDGER_DUPLICATE,

    /** Nothing to acknowledge, or a relaxation given with a value that is not looser. */
    NOT_LOOSER,

    /** An effective date before the business date that is not an allowed first value (INV-REQ-1243 b6, INV-Q-3140). */
    BACKDATING_REFUSED,

    /** A profile value without its profile id and version (INV-Q-2979). */
    PROFILE_INCOMPLETE,

    /** A profile value at installation scope: profile values are tenant-and-below settings (INV-Q-2979). */
    PROFILE_AT_INSTALLATION,
}

/** A write refused before anything was stored. */
class PolicyRefused(
    val refusal: PolicyRefusal,
    val key: VariationPointKey,
    detail: String,
) : RuntimeException("$key: $refusal: $detail")

/**
 * Every write path of configuration (INV-Q-3115): setting at every scope the row allows, and the control
 * relaxation acknowledgement. Each call runs in the caller's open transaction and writes its audit entry
 * (and `PolicyChanged`, where the service has an outbox) in it, so the change and its events commit
 * together (INV-REQ-1226). Installation values are written by an instance on the platform role's
 * transaction with the platform tenant bound (INV-Q-2927 option D).
 */
interface PolicyWriter {
    /**
     * @throws UnknownVariationPoint for an unregistered key; [VariationPointRetired] for a retired one.
     * @throws PolicyRefused for every other refusal, checked before any write.
     */
    fun setPolicy(command: SetPolicyCommand): PolicyChange

    /**
     * Records one acknowledgement of a relaxation and its audit entry.
     *
     * @throws PolicyRefused as [setPolicy], and when the actor may not acknowledge.
     */
    fun acknowledgeControlRelaxation(command: AcknowledgeRelaxationCommand): RelaxationStatus

    /**
     * The validator a service uses for a per-object value it stores itself (VP-67, VP-69, VP-70; INV-Q-2934):
     * scope, options, shape, bounds and the owning service's constraints, without storing anything.
     *
     * @throws PolicyRefused when the value would be refused by [setPolicy].
     */
    fun validate(
        key: VariationPointKey,
        scope: PolicyScope,
        level: ScopeLevel,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    )
}

/** One stored version of a value (INV-REQ-1129 behaviour #1): who, when, why and from when. */
data class SettingVersion(
    val id: UUID,
    val key: VariationPointKey,
    val target: SettingTarget,
    val selected: List<SelectedOption>,
    val source: SettingSource,
    val profileId: String?,
    val profileVersion: String?,
    val effectiveFrom: LocalDate,
    val setBy: String,
    val reason: String,
    val recordedAt: Instant,
    val relaxation: RelaxationId?,
)

/**
 * One row of an as-of view: the value in force on the date and every change made since (INV-AC-1146).
 *
 * @property inForce `null` when the row must be elected and nothing was in force.
 * @property changedSince versions recorded after the date or taking effect after it (INV-Q-3171), oldest
 *   recorded first, each with its actor.
 */
data class AsOfEntry(
    val key: VariationPointKey,
    val inForce: ResolvedSetting?,
    val changedSince: List<SettingVersion>,
)

/** The configuration of a scope as of a date (INV-REQ-1129 behaviour #2). */
data class ConfigurationAsOf(
    val scope: PolicyScope,
    val date: LocalDate,
    val entries: List<AsOfEntry>,
)

/** Two versions compared (INV-REQ-1129 behaviour #3). */
data class VersionComparison(
    val older: SettingVersion,
    val newer: SettingVersion,
    val addedOptions: List<String>,
    val removedOptions: List<String>,
    val changedValues: List<String>,
) {
    val sameValue: Boolean get() = addedOptions.isEmpty() && removedOptions.isEmpty() && changedValues.isEmpty()
}

/** The configuration history (INV-REQ-1129, INV-AC-1146). Reads in the caller's open transaction. */
interface PolicyHistory {
    /** Every version of [key] set at [target], oldest recorded first. */
    fun versions(
        key: VariationPointKey,
        target: SettingTarget,
    ): List<SettingVersion>

    /**
     * The value in force on [date] for each row (the rows the service owns, [VariationPointCatalogue.keys];
     * [keys] only filters them) and who changed it since (INV-AC-1146, INV-Q-3171). Retired rows are left out.
     *
     * @throws UnknownVariationPoint when a key in [keys] is not one of the service's rows.
     * @throws CatalogueListingUnavailable when the catalogue binding cannot list its rows.
     */
    fun asOf(
        scope: PolicyScope,
        date: LocalDate,
        keys: Set<VariationPointKey>? = null,
    ): ConfigurationAsOf

    /** Compares two versions of one row, in recorded order. */
    fun compare(
        a: SettingVersion,
        b: SettingVersion,
    ): VersionComparison
}
