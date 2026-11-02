package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.application.status.ChangedAttribute
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.ProfileExtras
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.platform.configresolver.PolicyChange
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.LocalDate

/** The attribute prefix of the scheme entries on EntityProfileChanged: `gstScheme.<KEY>` (ruling INV-Q-12266). */
const val GST_SCHEME_ATTRIBUTE_PREFIX = "gstScheme."

private const val ON = "ON"
private const val OFF = "OFF"

/** The exclusive "no scheme" option key of the catalogue row VP-53 (`catalogue.exclusive_keys`): never an entry. */
private const val NO_SCHEME = "NONE"

/** The row the decorator acts on, compared by equality (VP-530, vp-53 and VP-5 are other keys). */
private val SCHEME_KEY = VariationPointKey("VP-53")

/** The version of one scheme selection: the day it takes effect and the scheme keys selected (never `NONE`). */
private data class SchemeVersion(
    val effectiveFrom: LocalDate,
    val schemes: Set<String>,
)

/**
 * The scheme selections of [entityRef] oldest effective date first, one per effective date (the later record wins,
 * as the other elections read their history). Reads the one source, the config-resolver store.
 */
private fun schemeTimeline(
    history: PolicyHistory,
    tenants: TenantContext,
    entityRef: String,
): List<SchemeVersion> =
    history
        .versions(SCHEME_KEY, PolicyScope.entity(tenants.current(), entityRef).target(ScopeLevel.ENTITY))
        .sortedBy { it.recordedAt }
        .groupBy { it.effectiveFrom }
        .map { (date, sameDate) ->
            SchemeVersion(
                date,
                sameDate
                    .last()
                    .selected
                    .map { it.key }
                    .minus(NO_SCHEME)
                    .toSet(),
            )
        }.sortedBy { it.effectiveFrom }

private fun List<SchemeVersion>.inForceOn(date: LocalDate): Set<String> =
    lastOrNull { !it.effectiveFrom.isAfter(date) }?.schemes.orEmpty()

/** The dependencies the decorator resolves when the first write runs (not at start-up: it wraps a bean). */
class SchemeEventDependencies(
    val history: PolicyHistory,
    val publisher: EntityProfileChangePublisher,
    val structure: StructureStore,
    val statuses: EntityStatusStore,
    val profileExtras: ProfileExtras,
    val tenants: TenantContext,
)

/**
 * The decorator on the [PolicyWriter] bean for the GST scheme election VP-53 (ruling INV-Q-12262): it acts only when
 * the key equals VP-53, calls [delegate] first and, only when that returned, appends EntityProfileChanged in the same
 * transaction with one `gstScheme.<KEY>` entry per scheme whose membership in the selection in force changed at the
 * effective date. A refused write throws out of [delegate] and publishes nothing; a write that changes no scheme
 * publishes no event.
 */
class GstSchemeEventDecorator(
    private val delegate: PolicyWriter,
    private val dependencies: () -> SchemeEventDependencies,
) : PolicyWriter by delegate {
    override fun setPolicy(command: SetPolicyCommand): PolicyChange {
        if (command.key != SCHEME_KEY || command.level != ScopeLevel.ENTITY) return delegate.setPolicy(command)
        val entityRef = command.scope.entity ?: return delegate.setPolicy(command)
        val deps = dependencies()
        val before = schemeTimeline(deps.history, deps.tenants, entityRef)
        val change = delegate.setPolicy(command)
        val after = schemeTimeline(deps.history, deps.tenants, entityRef)
        val date = command.effectiveFrom
        val changed = changedAttributes(before, after, date)
        if (changed.isNotEmpty()) publish(deps, entityRef, changed, date)
        return change
    }

    private fun changedAttributes(
        before: List<SchemeVersion>,
        after: List<SchemeVersion>,
        date: LocalDate,
    ): List<ChangedAttribute> {
        val was = before.inForceOn(date)
        val now = after.inForceOn(date)
        val everSet = before.flatMap { it.schemes }.toSet()
        return (was union now).sorted().mapNotNull { scheme ->
            val old =
                if (scheme in was) {
                    ON
                } else if (scheme in everSet) {
                    OFF
                } else {
                    null
                }
            val new = if (scheme in now) ON else OFF
            if (old == new) null else ChangedAttribute(GST_SCHEME_ATTRIBUTE_PREFIX + scheme, old, new)
        }
    }

    private fun publish(
        deps: SchemeEventDependencies,
        entityRef: String,
        changed: List<ChangedAttribute>,
        date: LocalDate,
    ) {
        val entity = checkNotNull(deps.structure.entity(entityRef)) { "entity $entityRef is not in the structure" }
        val version = deps.structure.nextStructureVersion()
        deps.structure.touchEntity(entity.ref, version)
        val profile =
            (
                deps.statuses.history(entity.id).map { ProfileEntry(it.attribute, it.value, it.effectiveFrom) } +
                    deps.profileExtras.entries(entityRef)
            ).sortedBy { it.effectiveDate }
        deps.publisher.publish(
            EntityProfileChange(
                deps.tenants.current().value,
                entity.ref,
                changed,
                date,
                entityNode(entity.copy(structureVersion = version), profile),
            ),
        )
    }
}

/**
 * The scheme entries of an entity's profile: one dated entry per version of each scheme key ever set, `ON` while the
 * key is in the selection of that version and `OFF` once a later version drops it (a consumer treats any value other
 * than `ON` and any absent entry as not applied).
 */
class GstSchemeProfileExtras(
    private val history: PolicyHistory,
    private val tenants: TenantContext,
) : ProfileExtras {
    override fun entries(entityRef: String): List<ProfileEntry> {
        val timeline = schemeTimeline(history, tenants, entityRef)
        val everSet = timeline.flatMap { it.schemes }.toSortedSet()
        return everSet.flatMap { scheme ->
            timeline
                .dropWhile { scheme !in it.schemes }
                .map {
                    ProfileEntry(
                        GST_SCHEME_ATTRIBUTE_PREFIX + scheme,
                        if (scheme in
                            it.schemes
                        ) {
                            ON
                        } else {
                            OFF
                        },
                        it.effectiveFrom,
                    )
                }
        }
    }
}
