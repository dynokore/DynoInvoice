package com.dynokore.gst.submission.application.completeness

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** The stored state of one completeness part (a feed control or an aggregate window). */
enum class StoredState { FAILING, NOT_FAILING, OPEN }

/** One stored row of an entity and period, reduced to what the derivation reads. */
data class StoredRow(
    val controlId: String,
    val state: StoredState,
)

/** The control identifiers of the completeness rows (INV-REQ-792, ruled case 5). */
object ControlIds {
    fun feed(
        feed: String,
        control: String,
    ): String = "feed:$feed/$control"

    fun window(prescribedPeriod: String): String = "window:$prescribedPeriod"
}

/** Feed control rows. Version-guarded: a row changes only for a stateVersion greater than the stored one. */
interface FeedControlStates {
    /** @return true when the stored row changed; a versionless event ([stateVersion] 0) changes no existing row. */
    @Suppress("LongParameterList") // the port shape is ruled (INV-T-22205 design)
    fun record(
        tenant: TenantId,
        feed: String,
        entityRef: String,
        period: String,
        control: String,
        failing: Boolean,
        stateVersion: Long,
    ): Boolean
}

/** Aggregate window rows, keyed by tenant, entity, period and window; same version guard. */
interface WindowPartStates {
    /** @return true when the stored row changed; a versionless event ([stateVersion] 0) changes no existing row. */
    @Suppress("LongParameterList") // the port shape is ruled (INV-T-22205 design)
    fun record(
        tenant: TenantId,
        entityRef: String,
        period: String,
        from: LocalDate,
        to: LocalDate,
        state: StoredState,
        stateVersion: Long,
    ): Boolean
}

/** Reads every stored completeness row of an entity and period. */
interface CompletenessRows {
    fun rows(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): List<StoredRow>
}
