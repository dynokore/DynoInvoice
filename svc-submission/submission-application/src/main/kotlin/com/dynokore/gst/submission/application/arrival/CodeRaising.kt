package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.application.queue.ErrorCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock

/**
 * Records and signals the codes raised for a document version (INV-AC-21086). It opens no transaction (the
 * caller's handler does) and decides nothing about routing.
 */
class CodeRaising(
    private val catalogue: ErrorCatalogue,
    private val codes: RaisedCodes,
    private val signals: CodeSignals,
    private val clock: Clock,
) {
    /** Appends and stages each code, in the given order, that is new; returns the number staged. */
    @Suppress("LongParameterList")
    fun raise(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        entityRef: String,
        periodRef: String,
        codes: List<String>,
    ): Int {
        // Build every value first so an invalid input is refused before any port is called.
        val raised = codes.map { RaisedCode.of(docUuid, version, entityRef, periodRef, it, catalogue.entryOf(it)) }
        val raisedAt = clock.instant()
        var staged = 0
        for (r in raised) {
            if (this.codes.append(tenant, r, raisedAt)) {
                signals.stage(r)
                staged++
            }
        }
        return staged
    }
}
