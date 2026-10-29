package com.dynokore.gst.controlPlane.adapters.audit

import com.dynokore.gst.controlPlane.application.audit.AuditPurgeFloorSource
import java.time.Period

/**
 * The floor source until evidence-svc serves `getMaxEvidenceRetention` (Wave 4): it reports no figure for any region,
 * so the use case answers `FloorUndeterminable` (CPL-002) and nothing is deleted (INV-T-3768).
 */
class UndeterminedRetentionFloorSource : AuditPurgeFloorSource {
    override fun regionRetentions(): List<Period?> = emptyList()
}
