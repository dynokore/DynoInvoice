package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.CONSENT
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.ROLE_GRANT
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.SERVICE_PRINCIPAL
import com.dynokore.gst.access.application.consent.ConsentAuthorityRecord
import com.dynokore.gst.access.application.consent.ConsentChangePublisher
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.RoleGrantChange
import com.dynokore.gst.access.application.principal.PrincipalRevocationPublisher
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import org.jooq.DSLContext

/**
 * Writes the authority version of the event just appended into the row it describes (INV-T-9061): the column
 * `authority_version` of `role_grant`, `consent` and `service_principal`. Exactly one row is updated, and only when the
 * stored value is null or lower; any other count throws, which rolls the command transaction back with the event. The
 * statements run in the caller's transaction and never touch `version`, `updated_at` or `updated_by`.
 */
class StoredAuthorityVersions(
    private val dsl: DSLContext,
) {
    fun ofGrant(change: RoleGrantChange) =
        exactlyOne(
            "role_grant ${change.grantRef.value}",
            dsl
                .update(ROLE_GRANT)
                .set(ROLE_GRANT.AUTHORITY_VERSION, change.authorityVersion)
                .where(
                    ROLE_GRANT.GRANT_REF.eq(change.grantRef.value),
                    ROLE_GRANT.AUTHORITY_VERSION.isNull.or(ROLE_GRANT.AUTHORITY_VERSION.lt(change.authorityVersion)),
                ).execute(),
        )

    fun ofConsent(record: ConsentAuthorityRecord) =
        exactlyOne(
            "consent ${record.consent.ref.value}",
            dsl
                .update(CONSENT)
                .set(CONSENT.AUTHORITY_VERSION, record.authorityVersion)
                .where(
                    CONSENT.CONSENT_REF.eq(record.consent.ref.value),
                    CONSENT.AUTHORITY_VERSION.isNull.or(CONSENT.AUTHORITY_VERSION.lt(record.authorityVersion)),
                ).execute(),
        )

    fun ofPrincipal(
        ref: String,
        version: Long,
    ) = exactlyOne(
        "service_principal $ref",
        dsl
            .update(SERVICE_PRINCIPAL)
            .set(SERVICE_PRINCIPAL.AUTHORITY_VERSION, version)
            .where(
                SERVICE_PRINCIPAL.PRINCIPAL_REF.eq(ref),
                SERVICE_PRINCIPAL.AUTHORITY_VERSION.isNull.or(SERVICE_PRINCIPAL.AUTHORITY_VERSION.lt(version)),
            ).execute(),
    )

    private fun exactlyOne(
        key: String,
        updated: Int,
    ) = check(updated == 1) { "the authority version of $key was stored in $updated rows, not 1" }
}

/** Calls [wrapped] once, then stores the version of each change in its grant row (INV-T-9061). */
class VersionStoringGrantChanges(
    private val wrapped: AuthorityChangePublisher,
    private val versions: StoredAuthorityVersions,
) : AuthorityChangePublisher {
    override fun publish(changes: List<RoleGrantChange>) {
        wrapped.publish(changes)
        changes.forEach(versions::ofGrant)
    }
}

/** Calls [wrapped] once, then stores the version of the record in its consent row (INV-T-9061). */
class VersionStoringConsentChanges(
    private val wrapped: ConsentChangePublisher,
    private val versions: StoredAuthorityVersions,
) : ConsentChangePublisher {
    override fun publish(record: ConsentAuthorityRecord) {
        wrapped.publish(record)
        versions.ofConsent(record)
    }
}

/** Draws the version once, uses it for the event and for the principal row (INV-T-9061). */
class VersionStoringPrincipalRevocations(
    private val wrapped: PrincipalRevocationPublisher,
    private val versions: StoredAuthorityVersions,
) : PrincipalRevocationPublisher {
    override fun publishRevoked(
        principal: ServicePrincipal,
        nextVersion: () -> Long,
    ) {
        val drawn by lazy(nextVersion)
        wrapped.publishRevoked(principal) { drawn }
        versions.ofPrincipal(principal.ref, drawn)
    }
}
