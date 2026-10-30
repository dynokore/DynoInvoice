package com.dynokore.gst.evidence.application.integrity

import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import java.time.Duration
import java.time.ZoneOffset

/** The daily chain verification job of INV-Q-7251 A (4), exactly as the ruling declares it. */
object ChainVerificationJob {
    const val NAME = "evidence-chain-verification"

    val DEFINITION =
        JobDefinition(
            name = NAME,
            ownerService = "evidence-svc",
            entityScope = JobEntityScope.ENTITY,
            schedule = JobSchedule("0 0 1 * * *", ZoneOffset.UTC),
            criticality = JobCriticality.STANDARD,
            tolerance = Duration.ofHours(6),
            lockAtMostFor = Duration.ofHours(2),
        )
}
