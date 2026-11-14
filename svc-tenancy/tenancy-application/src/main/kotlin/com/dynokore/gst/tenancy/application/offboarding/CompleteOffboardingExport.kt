package com.dynokore.gst.tenancy.application.offboarding

import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingRules
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.OffboardingState
import com.dynokore.gst.tenancy.domain.exportOverdue
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Duration

/** What [CompleteOffboardingExport.apply] did with a result. */
enum class ExportResultOutcome {
    /** The record became COMPLETED or FAILED. */
    APPLIED,

    /** The result is incomplete: the record stays REQUESTED. */
    PENDING,

    /** The record is not REQUESTED (a repeat or a late result): nothing changed. */
    IGNORED,

    /** No such record. */
    NOT_FOUND,
}

/**
 * Applies an export result to a REQUESTED offboarding record (INV-REQ-877): one transaction, idempotent for an
 * at-least-once event, and the tenant is closed once when a TENANT record becomes COMPLETED (INV-Q-8097 B).
 */
class CompleteOffboardingExport(
    private val tx: TransactionRunner,
    private val results: OffboardingExportResults,
    private val tenantStore: TenantStore,
) {
    fun apply(
        offboardingRef: String,
        manifest: ExportManifest,
        failureReason: String?,
    ): ExportResultOutcome =
        tx.inTransaction {
            val stored = results.find(offboardingRef)
            val record = stored?.record
            when {
                record == null -> {
                    ExportResultOutcome.NOT_FOUND
                }

                record.state != OffboardingState.REQUESTED -> {
                    ExportResultOutcome.IGNORED
                }

                else -> {
                    val state = OffboardingRules.recordState(manifest)
                    val reason = failureReason.takeIf { state == OffboardingState.FAILED }
                    if (!results.applyResult(offboardingRef, state, manifest, reason)) {
                        ExportResultOutcome.IGNORED
                    } else if (state == OffboardingState.REQUESTED) {
                        ExportResultOutcome.PENDING
                    } else {
                        if (record.scope == OffboardingScope.TENANT && state == OffboardingState.COMPLETED) {
                            tenantStore.close()
                        }
                        ExportResultOutcome.APPLIED
                    }
                }
            }
        }
}

/** Marks the REQUESTED record of a scope FAILED once it is older than [limit]; the limit comes from configuration. */
class ExpireOverdueOffboarding(
    private val results: OffboardingExportResults,
    private val clock: Clock,
    private val limit: Duration,
) : OffboardingExpiry {
    override fun expireOverdue(
        scope: OffboardingScope,
        entityRef: String?,
    ): Boolean {
        val stored = results.requestedFor(scope, entityRef) ?: return false
        val overdue = exportOverdue(stored.record.state, stored.createdAt, clock.instant(), limit)
        return overdue &&
            results.applyResult(
                stored.record.offboardingRef,
                OffboardingState.FAILED,
                stored.record.export.copy(state = ExportState.FAILED),
                "no export result within $limit",
            )
    }
}
