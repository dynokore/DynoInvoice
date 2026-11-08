package com.dynokore.gst.tenancy.application.profile

import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/** The scope of an applied operating profile record (INV-REQ-1245 b2). */
enum class ProfileScope { TENANT, ENTITY }

/**
 * Which operating profile and version were applied to a scope: the scope, an opaque profile reference and the version
 * (INV-Q-5022 A). Tenancy-svc reads no profile content and does not check the reference against the catalogue.
 */
data class AppliedProfile(
    val scope: ProfileScope,
    val entityRef: String?,
    val profileRef: String,
    val profileVersion: Long,
)

/** The record of one scope of the bound tenant; every method runs in the caller's transaction. */
interface AppliedProfileStore {
    /** Inserts the record of the scope, or replaces that scope's profile reference and version. */
    fun put(
        scope: ProfileScope,
        entityId: UUID?,
        profileRef: String,
        profileVersion: Long,
    )

    fun find(
        scope: ProfileScope,
        entityId: UUID?,
    ): AppliedProfileValues?
}

/** What the store holds for a scope. */
data class AppliedProfileValues(
    val profileRef: String,
    val profileVersion: Long,
)

sealed interface AppliedProfileOutcome {
    data class Done(
        val profile: AppliedProfile,
    ) : AppliedProfileOutcome

    /** The entity is not one of the bound tenant's, or the scope has no record (404). */
    data object NotFound : AppliedProfileOutcome

    /** The scope, entity reference or version breaks the rules of the operation (400). */
    data class Invalid(
        val reason: String,
    ) : AppliedProfileOutcome
}

/** recordAppliedOperatingProfile and getAppliedOperatingProfile of the bound tenant (INV-REQ-1245 b2). */
class AppliedOperatingProfiles(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val store: AppliedProfileStore,
) {
    /** Stores the profile reference and version of the scope; a record of that scope already held is replaced. */
    fun record(request: AppliedProfile): AppliedProfileOutcome {
        val invalid = invalidReason(request.scope, request.entityRef)
        return when {
            invalid != null -> {
                AppliedProfileOutcome.Invalid(invalid)
            }

            request.profileRef.isEmpty() || request.profileRef.length > REF_MAX -> {
                AppliedProfileOutcome.Invalid("profileRef has 1 to $REF_MAX characters")
            }

            request.profileVersion < 1 -> {
                AppliedProfileOutcome.Invalid("profileVersion is at least 1")
            }

            else -> {
                tx.inTransaction {
                    val entityId = entityIdOf(request.scope, request.entityRef)
                    if (request.scope == ProfileScope.ENTITY && entityId == null) {
                        AppliedProfileOutcome.NotFound
                    } else {
                        store.put(request.scope, entityId, request.profileRef, request.profileVersion)
                        AppliedProfileOutcome.Done(request)
                    }
                }
            }
        }
    }

    /** The record of the scope, or not found for an unknown entity or a scope with no record. */
    fun read(
        scope: ProfileScope,
        entityRef: String?,
    ): AppliedProfileOutcome {
        val invalid = invalidReason(scope, entityRef)
        return if (invalid != null) {
            AppliedProfileOutcome.Invalid(invalid)
        } else {
            tx.inTransaction {
                val entityId = entityIdOf(scope, entityRef)
                val found =
                    if (scope == ProfileScope.ENTITY && entityId == null) null else store.find(scope, entityId)
                found?.let {
                    AppliedProfileOutcome.Done(AppliedProfile(scope, entityRef, it.profileRef, it.profileVersion))
                } ?: AppliedProfileOutcome.NotFound
            }
        }
    }

    /** The entity reference is present exactly when the scope is ENTITY. */
    private fun invalidReason(
        scope: ProfileScope,
        entityRef: String?,
    ): String? =
        when {
            scope == ProfileScope.ENTITY && entityRef == null -> "entityRef is required for scope ENTITY"
            scope == ProfileScope.TENANT && entityRef != null -> "entityRef is not allowed for scope TENANT"
            else -> null
        }

    /** The id of the tenant's entity the reference names; null for scope TENANT and for an entity not the tenant's. */
    private fun entityIdOf(
        scope: ProfileScope,
        entityRef: String?,
    ): UUID? = if (scope == ProfileScope.ENTITY) structure.entity(requireNotNull(entityRef))?.id else null

    private companion object {
        const val REF_MAX = 128
    }
}
