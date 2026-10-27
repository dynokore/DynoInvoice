package com.dynokore.gst.controlPlane.application.commercial

import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Plan
import com.dynokore.gst.controlPlane.domain.commercial.PlanVersion
import com.dynokore.gst.controlPlane.domain.commercial.Qualification

/** Plan versions, in the open transaction of the command. */
interface PlanRepository {
    fun find(
        tenantRef: CommercialRef,
        version: PlanVersion,
    ): Plan?

    /** Inserts a version; a stored version is never changed. */
    fun save(
        plan: Plan,
        recordedBy: String,
    )
}

/** Qualification outcomes, in the open transaction of the command. Every record stays in the history. */
interface QualificationRepository {
    /** The latest record of the pair, or null when none is recorded. */
    fun find(
        customerRef: CommercialRef,
        installationRef: CommercialRef,
    ): Qualification?

    /** The latest record of any customer for the installation, or null when none is recorded. */
    fun latestFor(installationRef: CommercialRef): Qualification?

    /** Appends a record; an earlier record is never changed or removed. */
    fun save(
        qualification: Qualification,
        recordedBy: String,
    )
}
