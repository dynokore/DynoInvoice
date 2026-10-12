package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Who put a stored setting there (ruling INV-Q-2979; PolicyChanged `source`). A value applied by an
 * operating profile is stored as a setting with source [PROFILE] plus the profile id and version; a
 * tenant's own deviation is an [OVERRIDE].
 */
enum class SettingSource { OVERRIDE, PROFILE }

/** Where a resolved value came from (INV-REQ-1068 behaviour #2). */
sealed interface PolicySource {
    /** An override set at [level] for [scopeId]; [settingId] names the stored row, which stays as history. */
    data class Override(
        val level: ScopeLevel,
        val scopeId: String,
        val settingId: UUID,
    ) : PolicySource

    /** The operating profile [profileId] at [profileVersion], applied at [level] for [scopeId] (INV-AC-2092). */
    data class Profile(
        val profileId: String,
        val profileVersion: String,
        val level: ScopeLevel,
        val scopeId: String,
        val settingId: UUID,
    ) : PolicySource {
        init {
            require(profileId.isNotBlank() && profileVersion.isNotBlank()) {
                "a profile source names the profile and its version"
            }
        }
    }

    /** The catalogue default. */
    data object CatalogueDefault : PolicySource
}

/**
 * One chosen option. [valueJson] is the option's typed value as a JSON value (INV-Q-2934), for an option
 * the catalogue marks typed; otherwise `null`.
 */
data class SelectedOption(
    val key: String,
    val valueJson: String? = null,
) {
    init {
        require(valueJson == null || valueJson.isNotBlank()) { "a typed value, when given, must not be blank" }
    }
}

/**
 * One directed field of a stored value capped at resolution (INV-REQ-1242 behaviour #5, ruling INV-Q-3206):
 * the stored value is unchanged, the resolved value carries [capped], and the source shows what capped it.
 *
 * @property option the option key whose typed value holds the field.
 * @property field the field's dotted path inside the option's value; empty for a plain number.
 * @property by the row whose value capped it: the same row at a broader [level] (a cross-scope rule), or the
 *   installation parameter row at [ScopeLevel.INSTALLATION] (an installation bound).
 */
data class PolicyCap(
    val option: String,
    val field: String,
    val stored: BigDecimal,
    val capped: BigDecimal,
    val by: VariationPointKey,
    val level: ScopeLevel,
)

/**
 * The resolution before the option keys are turned into the owning service's enum.
 *
 * @property caps the directed fields capped at a broader value or an installation bound (INV-REQ-1242
 *   behaviour #5); [selected] already holds the capped values. Empty when nothing was capped.
 */
data class ResolvedSetting(
    val key: VariationPointKey,
    val at: LocalDate,
    val selected: List<SelectedOption>,
    val source: PolicySource,
    val caps: List<PolicyCap> = emptyList(),
) {
    init {
        require(selected.isNotEmpty()) { "a resolved value selects at least one option" }
    }

    /** The profile version when the operating profile supplied the value (FR-F.3). */
    val profileVersion: String? get() = (source as? PolicySource.Profile)?.profileVersion
}

/** The value, its source and the profile version when the profile supplied it (FR-F.3, TPL-03, CS-28). */
data class Resolved<E : Enum<E>>(
    val value: E,
    val source: PolicySource,
    val profileVersion: String?,
)

/**
 * The port every business decision reads configuration through (CS-28, TPL-03). [at] is the transaction
 * date, never the clock (INV-REQ-1351). Call it inside the service's transaction.
 */
interface PolicyReader {
    /**
     * Resolves, at [at], the most specific override the row allows, then the most specific value of the
     * operating profile, then the catalogue default (INV-REQ-1068, INV-REQ-1242). A planned variation point
     * resolves to its default (INV-REQ-1251).
     *
     * @throws UnknownVariationPoint when [key] is not in the catalogue.
     * @throws VariationPointRetired when it is retired.
     * @throws ElectionRequired when it must be elected and nothing is set.
     */
    fun resolve(
        key: VariationPointKey,
        scope: PolicyScope,
        at: LocalDate,
    ): ResolvedSetting

    /**
     * A value a service stores itself (per object, e.g. VP-67 per webhook subscription; INV-Q-2934) as it
     * resolves at [at]: its directed fields capped at the broader value and the installation bound in force
     * then (INV-REQ-1242 behaviour #5, ruling INV-Q-3206). The source is an override at [level] of [scope] with
     * the nil setting id, since config-resolver stores no row for it.
     *
     * @throws BoundUnavailable when a bound in force cannot be evaluated (fail closed).
     */
    fun capToBounds(
        key: VariationPointKey,
        scope: PolicyScope,
        level: ScopeLevel,
        selected: List<SelectedOption>,
        at: LocalDate,
    ): ResolvedSetting = throw UnsupportedOperationException("this PolicyReader cannot cap per-object values")

    /**
     * [resolve] for a single-option variation point, as the owning service's enum (TPL-10).
     *
     * @throws OptionKeyMismatch when the value is not exactly one constant of [options].
     */
    fun <E : Enum<E>> resolve(
        key: VariationPointKey,
        scope: PolicyScope,
        at: LocalDate,
        options: KClass<E>,
    ): Resolved<E> {
        val raw = resolve(key, scope, at)
        val only =
            raw.selected.singleOrNull()
                ?: throw OptionKeyMismatch(key, raw.selected.joinToString { it.key }, options.java.simpleName)
        val value = options.java.enumConstants.firstOrNull { it.name == only.key }
            ?: throw OptionKeyMismatch(key, only.key, options.java.simpleName)
        return Resolved(value, raw.source, raw.profileVersion)
    }
}

inline fun <reified E : Enum<E>> PolicyReader.resolve(
    key: VariationPointKey,
    scope: PolicyScope,
    at: LocalDate,
): Resolved<E> = resolve(key, scope, at, E::class)

/**
 * The thing a resolved value is recorded on: one document version's record, e.g. a submission (ruling
 * INV-Q-2938). A new document version is a new subject. [type] is a lower-case token such as
 * `submission`, so a typo cannot open a second namespace.
 */
data class ResolutionSubject(
    val type: String,
    val ref: String,
) {
    init {
        require(TYPE.matches(type)) { "a subject type is a lower-case token like 'submission', was '$type'" }
        require(ref.isNotBlank()) { "subject ref must not be blank" }
    }

    private companion object {
        val TYPE = Regex("^[a-z][a-z0-9_]{0,62}$")
    }
}

/** A resolved value as recorded on its subject (INV-REQ-1068 behaviour #2), with any caps it carried. */
data class RecordedResolution(
    val subject: ResolutionSubject,
    val key: VariationPointKey,
    val transactionDate: LocalDate,
    val selected: List<SelectedOption>,
    val source: PolicySource,
    val caps: List<PolicyCap> = emptyList(),
)

/**
 * Records the resolved value and its source on a subject (INV-REQ-1068 behaviour #2, INV-AC-2091/2092).
 * Insert-only: written once per subject and variation point, never changed (ruling INV-Q-2938).
 */
interface ResolutionRecorder {
    /**
     * Stores the record in the caller's tenant transaction and returns it. When the subject already has a
     * record for the variation point, the stored one is returned unchanged (first write wins): a later
     * change of configuration never alters an earlier document (INV-AC-2093).
     */
    fun record(
        subject: ResolutionSubject,
        resolved: ResolvedSetting,
        scope: PolicyScope,
    ): RecordedResolution

    /** The records of [subject], by variation point. */
    fun recorded(subject: ResolutionSubject): List<RecordedResolution>
}

/**
 * Deletes records with their subject, for the owning service's retention (VP-43) and tenant offboarding
 * (ruling INV-Q-2938). Runs in a transaction of the tenant concerned with the caller's actor bound, after the
 * application has checked the operation and the actor: the runtime login can never delete, and the database deletes
 * only the bound tenant's rows, through functions that refuse an unset actor (2.0.0; INV-Q-3953, INV-Q-3997).
 */
interface ConfigPurge {
    /** Deletes every resolved-value record of [subject]; returns how many. */
    fun purgeSubject(subject: ResolutionSubject): Int

    /**
     * Deletes every resolved-value record, every stored setting (with its history) and every control
     * relaxation acknowledgement of [tenant] (INV-Q-2938, INV-Q-3125).
     */
    fun purgeTenant(tenant: TenantId): PurgedTenant
}

data class PurgedTenant(
    val resolvedValues: Int,
    val settings: Int,
    val relaxationAcknowledgements: Int = 0,
)

/** The variation point is not in the catalogue: refused, never defaulted. */
class UnknownVariationPoint(
    key: VariationPointKey,
) : RuntimeException("variation point $key is not registered in the catalogue")

/** A retired variation point is kept for its identifier and never offered or resolved. */
class VariationPointRetired(
    key: VariationPointKey,
) : RuntimeException("variation point $key is retired")

/** The variation point must be elected and no value is set at any scope or by the profile. */
class ElectionRequired(
    key: VariationPointKey,
) : RuntimeException("variation point $key must be elected and has no value")

/** The resolved option is not one constant of the owning enum (TPL-10). */
class OptionKeyMismatch(
    key: VariationPointKey,
    option: String,
    enumName: String,
) : RuntimeException("variation point $key resolved option '$option', which is not one constant of $enumName")

/** A stored value names an option the catalogue does not have (any more): refused, never guessed. */
class StoredValueNotInCatalogue(
    key: VariationPointKey,
    option: String,
) : RuntimeException("variation point $key has a stored option '$option' the catalogue does not define")

/**
 * A cross-scope rule or installation bound in force cannot be evaluated: the bounding row is missing or not
 * a single number in the same unit, or a value lacks a field to compare (INV-Q-3202, INV-Q-3204). Refused,
 * never guessed.
 */
class BoundUnavailable(
    key: VariationPointKey,
    detail: String,
) : RuntimeException("variation point $key: bound cannot be evaluated: $detail")
