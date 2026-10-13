package com.dynokore.platform.serviceruntime.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The typed workload principal (owner ruling INV-Q-4344 (b)): `svc:<svc>` and `svc:<svc>/job:<job>`, format-checked.
 */
class WorkloadPrincipalTest {
    @Test
    fun `a lower-case service name gives svc and its jobs svc-job`() {
        val principal = WorkloadPrincipal("submission-svc")
        principal.value shouldBe "svc:submission-svc"
        principal.job("scheduled-despatch") shouldBe "svc:submission-svc/job:scheduled-despatch"
        principal shouldBe WorkloadPrincipal("submission-svc")
    }

    @Test
    fun `anything but a lower-case service name is refused, and so is a job name outside the job rule`() {
        val refused =
            listOf("", "Submission-svc", "submission_svc", "svc:submission", "1svc", "submission svc", "a".repeat(64))
        refused.forEach {
            shouldThrow<IllegalArgumentException> { WorkloadPrincipal(it) }
        }
        listOf("", "Despatch", "despatch/x", "job:x").forEach {
            shouldThrow<IllegalArgumentException> { WorkloadPrincipal("submission-svc").job(it) }
        }
    }
}
