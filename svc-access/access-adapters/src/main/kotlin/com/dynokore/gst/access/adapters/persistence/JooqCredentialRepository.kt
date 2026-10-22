package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.PRINCIPAL_CREDENTIAL
import com.dynokore.gst.access.application.principal.CredentialRepository
import com.dynokore.gst.access.domain.principal.Credential
import com.dynokore.gst.access.domain.principal.CredentialMethod
import com.dynokore.gst.access.domain.principal.CredentialStatus
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.serviceruntime.persistence.StaleAggregate
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** TPL-11. The DSLContext adds `tenant_id = <bound tenant>` to every statement; forced RLS stays behind that. */
class JooqCredentialRepository(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : CredentialRepository {
    override fun insert(
        principalRef: String,
        credential: Credential,
    ) {
        val now = now()
        val actor = actor()
        dsl
            .insertInto(PRINCIPAL_CREDENTIAL)
            .set(PRINCIPAL_CREDENTIAL.ID, ids.next(clock.instant()))
            .set(PRINCIPAL_CREDENTIAL.PRINCIPAL_REF, principalRef)
            .set(PRINCIPAL_CREDENTIAL.CREDENTIAL_REF, credential.ref)
            .set(PRINCIPAL_CREDENTIAL.METHOD, credential.method.name)
            .set(PRINCIPAL_CREDENTIAL.STATUS, credential.status.name)
            .set(PRINCIPAL_CREDENTIAL.VALID_UNTIL, credential.validUntil?.atOffset(ZoneOffset.UTC))
            .set(PRINCIPAL_CREDENTIAL.PUBLIC_KEY_JWK, credential.publicKeyJwk)
            .set(PRINCIPAL_CREDENTIAL.CERTIFICATE_THUMBPRINT, credential.certificateThumbprint)
            .set(PRINCIPAL_CREDENTIAL.SECRET_VERIFIER, credential.secretVerifier)
            .set(PRINCIPAL_CREDENTIAL.CREATED_AT, now)
            .set(PRINCIPAL_CREDENTIAL.CREATED_BY, actor)
            .set(PRINCIPAL_CREDENTIAL.UPDATED_AT, now)
            .set(PRINCIPAL_CREDENTIAL.UPDATED_BY, actor)
            .set(PRINCIPAL_CREDENTIAL.VERSION, credential.version)
            .execute()
    }

    override fun findByPrincipal(principalRef: String): List<Credential> =
        dsl
            .selectFrom(PRINCIPAL_CREDENTIAL)
            .where(PRINCIPAL_CREDENTIAL.PRINCIPAL_REF.eq(principalRef))
            .orderBy(PRINCIPAL_CREDENTIAL.CREATED_AT, PRINCIPAL_CREDENTIAL.ID)
            .fetch()
            .map(::toCredential)

    override fun find(
        principalRef: String,
        credentialRef: String,
    ): Credential? =
        dsl
            .selectFrom(PRINCIPAL_CREDENTIAL)
            .where(
                PRINCIPAL_CREDENTIAL.PRINCIPAL_REF.eq(principalRef),
                PRINCIPAL_CREDENTIAL.CREDENTIAL_REF.eq(credentialRef),
            ).fetchOne()
            ?.let(::toCredential)

    override fun update(
        principalRef: String,
        before: Credential,
        after: Credential,
    ) {
        require(before.ref == after.ref) { "update changes one credential" }
        val updated =
            dsl
                .update(PRINCIPAL_CREDENTIAL)
                .set(PRINCIPAL_CREDENTIAL.STATUS, after.status.name)
                .set(PRINCIPAL_CREDENTIAL.VALID_UNTIL, after.validUntil?.atOffset(ZoneOffset.UTC))
                .set(PRINCIPAL_CREDENTIAL.UPDATED_AT, now())
                .set(PRINCIPAL_CREDENTIAL.UPDATED_BY, actor())
                .set(PRINCIPAL_CREDENTIAL.VERSION, after.version)
                .where(
                    PRINCIPAL_CREDENTIAL.PRINCIPAL_REF.eq(principalRef),
                    PRINCIPAL_CREDENTIAL.CREDENTIAL_REF.eq(before.ref),
                    PRINCIPAL_CREDENTIAL.VERSION.eq(before.version),
                ).execute()
        if (updated != 1) throw StaleAggregate("Credential", after.version)
    }

    override fun nextRef(): String =
        "CRD-" +
            ids
                .next(clock.instant())
                .toString()
                .replace("-", "")
                .takeLast(REF_DIGITS)

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    private fun actor(): String =
        checkNotNull(tenants.currentActor()) { "no actor is bound: a credential change runs in a bound transaction" }

    private fun toCredential(row: Record): Credential =
        Credential(
            ref = row.get(PRINCIPAL_CREDENTIAL.CREDENTIAL_REF).column("credential_ref"),
            principalRef = row.get(PRINCIPAL_CREDENTIAL.PRINCIPAL_REF).column("principal_ref"),
            method = CredentialMethod.valueOf(row.get(PRINCIPAL_CREDENTIAL.METHOD).column("method")),
            status = CredentialStatus.valueOf(row.get(PRINCIPAL_CREDENTIAL.STATUS).column("status")),
            validUntil = row.get(PRINCIPAL_CREDENTIAL.VALID_UNTIL)?.toInstant(),
            publicKeyJwk = row.get(PRINCIPAL_CREDENTIAL.PUBLIC_KEY_JWK),
            certificateThumbprint = row.get(PRINCIPAL_CREDENTIAL.CERTIFICATE_THUMBPRINT),
            secretVerifier = row.get(PRINCIPAL_CREDENTIAL.SECRET_VERIFIER),
            version = row.get(PRINCIPAL_CREDENTIAL.VERSION).column("version"),
        )

    private companion object {
        const val REF_DIGITS = 16
    }
}
