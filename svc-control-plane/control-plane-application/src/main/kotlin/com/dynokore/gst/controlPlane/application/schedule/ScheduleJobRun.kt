package com.dynokore.gst.controlPlane.application.schedule

/**
 * One run of the scheduled job: the opening step, then the closing step (INV-T-8180). The closing step runs even when
 * the opening step threw (an `openTenant` failure or a database error must not keep a due offboarding from being
 * carried out); the opening failure is then rethrown so the job runtime records the failed run. If both throw, the
 * opening failure is rethrown and the closing failure is attached as suppressed.
 */
object ScheduleJobRun {
    /** Returns the number of `openTenant` and `closeTenant` calls made. */
    @Suppress("TooGenericExceptionCaught")
    fun run(
        opening: OpenDueTenants,
        closing: CloseDueTenants,
        batchSize: Int,
    ): Int {
        var opened = 0
        var openingFailure: Throwable? = null
        try {
            opened = opening.runOnce(batchSize)
        } catch (e: Exception) {
            openingFailure = e
        }
        val closed =
            try {
                closing.runOnce(batchSize)
            } catch (e: Exception) {
                openingFailure?.addSuppressed(e) ?: throw e
                0
            }
        openingFailure?.let { throw it }
        return opened + closed
    }
}
