package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApCredentialRotation.Companion.AP_CREDENTIAL_ROTATION
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApCredentialState.Companion.AP_CREDENTIAL_STATE
import com.dynokore.gst.transport.application.AdapterCredentialState
import com.dynokore.gst.transport.application.AdapterCredentialStates
import com.dynokore.gst.transport.application.AdapterNotRegistered
import com.dynokore.gst.transport.application.CredentialRef
import com.dynokore.gst.transport.application.CredentialRotationAudit
import com.dynokore.gst.transport.application.CredentialRotationLog
import com.dynokore.gst.transport.application.RotationOutcome
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

private fun Instant.utc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

/**
 * The credential state of each AP connection of the bound tenant (INV-REQ-837): references and times only, tenant data
 * under forced row level security. A connection with no row uses [initialRef] of its adapter (null: the adapter is not
 * configured, so it is not registered); nothing is stored until the first rotation. [load] locks the connection row for
 * the transaction it runs in, so two rotations of one connection run one after the other.
 */
class JooqAdapterCredentialStates(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val initialRef: (AdapterId) -> CredentialRef?,
) : AdapterCredentialStates {
    override fun load(adapter: AdapterId): AdapterCredentialState? =
        runner.inTransaction {
            dsl
                .select(AP_CONNECTION.ADAPTER_ID)
                .from(AP_CONNECTION)
                .where(AP_CONNECTION.TENANT_ID.eq(tenants.current().value))
                .and(AP_CONNECTION.ADAPTER_ID.eq(adapter.value))
                .forUpdate()
                .fetch()
            row(adapter)
                ?.let { row ->
                    AdapterCredentialState(
                        CredentialRef(checkNotNull(row.currentRef)),
                        row.previousRef?.let(::CredentialRef),
                        row.overlapEnd?.toInstant(),
                    )
                } ?: initialRef(adapter)?.let { AdapterCredentialState(it) }
        }

    private fun row(adapter: AdapterId) =
        dsl
            .selectFrom(AP_CREDENTIAL_STATE)
            .where(AP_CREDENTIAL_STATE.TENANT_ID.eq(tenants.current().value))
            .and(AP_CREDENTIAL_STATE.ADAPTER_ID.eq(adapter.value))
            .fetchOne()

    override fun save(
        adapter: AdapterId,
        state: AdapterCredentialState,
    ) {
        val now = clock.instant().utc()
        val tenant = tenants.current().value
        runner.inTransaction {
            dsl
                .insertInto(AP_CREDENTIAL_STATE)
                .set(AP_CREDENTIAL_STATE.TENANT_ID, tenant)
                .set(AP_CREDENTIAL_STATE.ADAPTER_ID, adapter.value)
                .set(AP_CREDENTIAL_STATE.CURRENT_REF, state.current.value)
                .set(AP_CREDENTIAL_STATE.PREVIOUS_REF, state.previous?.value)
                .set(AP_CREDENTIAL_STATE.OVERLAP_END, state.overlapEnd?.utc())
                .set(AP_CREDENTIAL_STATE.UPDATED_AT, now)
                .onConflict(AP_CREDENTIAL_STATE.TENANT_ID, AP_CREDENTIAL_STATE.ADAPTER_ID)
                .doUpdate()
                .set(AP_CREDENTIAL_STATE.CURRENT_REF, state.current.value)
                .set(AP_CREDENTIAL_STATE.PREVIOUS_REF, state.previous?.value)
                .set(AP_CREDENTIAL_STATE.OVERLAP_END, state.overlapEnd?.utc())
                .set(AP_CREDENTIAL_STATE.UPDATED_AT, now)
                .execute()
        }
    }

    /** The current reference of the bound tenant's connection to [adapter]; [AdapterNotRegistered] if unconfigured. */
    fun currentRef(adapter: AdapterId): CredentialRef =
        runner.inTransaction {
            row(adapter)?.let { CredentialRef(checkNotNull(it.currentRef)) } ?: initialRef(adapter)
        } ?: throw AdapterNotRegistered(adapter.value)
}

/** The rotation log (INV-REQ-837): the outcome per (tenant, adapter, new reference) and the acting user ID. */
class JooqCredentialRotationLog(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
) : CredentialRotationLog {
    override fun find(
        adapter: AdapterId,
        credentialId: CredentialRef,
    ): RotationOutcome? = row(adapter, credentialId)?.first

    /** When the rotation to [credentialId] was made; null when there is none. */
    fun rotatedAt(
        adapter: AdapterId,
        credentialId: CredentialRef,
    ): Instant? = row(adapter, credentialId)?.second

    private fun row(
        adapter: AdapterId,
        credentialId: CredentialRef,
    ): Pair<RotationOutcome, Instant>? =
        runner.inTransaction {
            dsl
                .selectFrom(AP_CREDENTIAL_ROTATION)
                .where(AP_CREDENTIAL_ROTATION.TENANT_ID.eq(tenants.current().value))
                .and(AP_CREDENTIAL_ROTATION.ADAPTER_ID.eq(adapter.value))
                .and(AP_CREDENTIAL_ROTATION.NEW_REF.eq(credentialId.value))
                .fetchOne()
                ?.let {
                    RotationOutcome(
                        adapter,
                        credentialId,
                        CredentialRef(checkNotNull(it.previousRef)),
                        checkNotNull(it.overlapEnd).toInstant(),
                    ) to checkNotNull(it.rotatedAt).toInstant()
                }
        }

    override fun record(audit: CredentialRotationAudit) {
        val outcome = audit.outcome
        val tenant = tenants.current().value
        runner.inTransaction {
            dsl
                .insertInto(AP_CREDENTIAL_ROTATION)
                .set(AP_CREDENTIAL_ROTATION.TENANT_ID, tenant)
                .set(AP_CREDENTIAL_ROTATION.ADAPTER_ID, outcome.adapter.value)
                .set(AP_CREDENTIAL_ROTATION.NEW_REF, outcome.credentialId.value)
                .set(AP_CREDENTIAL_ROTATION.PREVIOUS_REF, outcome.previous.value)
                .set(AP_CREDENTIAL_ROTATION.OVERLAP_END, outcome.overlapEnd.utc())
                .set(AP_CREDENTIAL_ROTATION.ROTATED_AT, audit.at.utc())
                .set(AP_CREDENTIAL_ROTATION.ACTING_USER_ID, audit.actingUserId)
                .execute()
        }
    }
}
