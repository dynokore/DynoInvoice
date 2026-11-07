package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.SIGNATORY_ACTION
import com.dynokore.gst.access.application.link.SignatoryAction
import com.dynokore.gst.access.application.link.SignatoryActionKind
import com.dynokore.gst.access.application.link.SignatoryActions
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * TPL-11. The evidence of the signatory actions of a link (INV-REQ-900). The listeners of the DSLContext add the bound
 * tenant to every statement; row-level security stays behind. The repeat key (link, kind, pack version) is unique.
 */
class JooqSignatoryActions(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
) : SignatoryActions {
    override fun find(
        linkRef: LinkRef,
        kind: SignatoryActionKind,
        packVersion: String,
    ): SignatoryAction? =
        dsl
            .selectFrom(SIGNATORY_ACTION)
            .where(
                SIGNATORY_ACTION.LINK_REF.eq(linkRef.value),
                SIGNATORY_ACTION.KIND.eq(kind.name),
                SIGNATORY_ACTION.PACK_VERSION.eq(packVersion),
            ).fetchOne()
            ?.let {
                SignatoryAction(
                    linkRef = LinkRef(it.linkRef.column("link_ref")),
                    kind = SignatoryActionKind.valueOf(it.kind.column("kind")),
                    packVersion = it.packVersion.column("pack_version"),
                    queryText = it.queryText,
                    client = EntityRef(it.clientRef.column("client_ref")),
                    actor = it.actor.column("actor"),
                    recordedAt = it.recordedAt.column("recorded_at").toInstant(),
                )
            }

    override fun insert(action: SignatoryAction) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        dsl
            .insertInto(SIGNATORY_ACTION)
            .set(SIGNATORY_ACTION.ID, SignatoryEvidenceRefs.idOf(tenants.current(), action))
            .set(SIGNATORY_ACTION.LINK_REF, action.linkRef.value)
            .set(SIGNATORY_ACTION.KIND, action.kind.name)
            .set(SIGNATORY_ACTION.PACK_VERSION, action.packVersion)
            .set(SIGNATORY_ACTION.QUERY_TEXT, action.queryText)
            .set(SIGNATORY_ACTION.CLIENT_REF, action.client.value)
            .set(SIGNATORY_ACTION.ACTOR, action.actor)
            .set(
                SIGNATORY_ACTION.RECORDED_AT,
                action.recordedAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC),
            ).set(SIGNATORY_ACTION.CREATED_AT, now)
            .set(SIGNATORY_ACTION.CREATED_BY, actor)
            .execute()
    }
}

/**
 * The reference of the evidence record of an action (`evidenceRef` of the contract): `EVD-` and the name-based UUID
 * (version 5, RFC 9562 section 5.5: SHA-1 under the fixed [NAMESPACE]) of (tenant, link, kind, pack version), the
 * repeat key of the contract. It is the primary key of the row, so a repeat returns the original reference and no
 * second record exists for the same key. 40 characters, within the contract limit of 64.
 */
object SignatoryEvidenceRefs {
    /** The one fixed namespace of the evidence references of access-svc. */
    val NAMESPACE: java.util.UUID = java.util.UUID.fromString("5b0f3d2e-7a41-5c8e-9d36-1f4a7c2e8b90")

    fun idOf(
        tenant: TenantId,
        action: SignatoryAction,
    ): java.util.UUID {
        val name =
            listOf(tenant.toString(), action.linkRef.value, action.kind.name, action.packVersion)
                .joinToString("\u0000")
                .toByteArray(Charsets.UTF_8)
        // RFC 9562 defines UUID version 5 over SHA-1 (ruling INV-Q-17902); a name-based id, not a signature or a secret
        // nosemgrep: kotlin.lang.security.use-of-sha1.use-of-sha1
        val sha = java.security.MessageDigest.getInstance("SHA-1")
        sha.update(
            java.nio.ByteBuffer
                .allocate(
                    UUID_BYTES,
                ).putLong(NAMESPACE.mostSignificantBits)
                .putLong(NAMESPACE.leastSignificantBits)
                .array(),
        )
        val hash = sha.digest(name)
        hash[VERSION_BYTE] = ((hash[VERSION_BYTE].toInt() and LOW_NIBBLE) or VERSION_5).toByte()
        hash[VARIANT_BYTE] = ((hash[VARIANT_BYTE].toInt() and VARIANT_MASK) or VARIANT_RFC).toByte()
        val buffer = java.nio.ByteBuffer.wrap(hash, 0, UUID_BYTES)
        return java.util.UUID(buffer.long, buffer.long)
    }

    fun refOf(
        tenant: TenantId,
        action: SignatoryAction,
    ): String = "EVD-${idOf(tenant, action)}"

    private const val UUID_BYTES = 16
    private const val VERSION_BYTE = 6
    private const val VARIANT_BYTE = 8
    private const val LOW_NIBBLE = 0x0f
    private const val VERSION_5 = 0x50
    private const val VARIANT_MASK = 0x3f
    private const val VARIANT_RFC = 0x80
}
