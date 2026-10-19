package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.AUDIT
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E1
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E2
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SERVICE
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.START
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.TENANT_A
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.TENANT_B
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.job
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.request
import com.dynokore.platform.serviceruntime.testing.PG_CLASS
import com.dynokore.platform.serviceruntime.testing.PG_CLASS_FORCE
import com.dynokore.platform.serviceruntime.testing.PG_CLASS_NAME
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The harness and the shared fixtures of the job contract tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class JobContractBase {
    protected val harnesses = ConcurrentHashMap<String, JobsHarness>()

    protected fun harness(image: String) = harnesses.computeIfAbsent(image) { JobsHarness(it) }

    @AfterAll
    fun close() = harnesses.values.forEach { it.close() }

    protected val e1 = JobTarget.Entity(TENANT_A, E1)
    protected val e2 = JobTarget.Entity(TENANT_B, E2)

    protected fun <T> JobsHarness.Replica.inA(block: JobRuntime.() -> T): T = asTenant(TENANT_A) { runtime.block() }

    protected val entities = JobEntitySource { listOf(e1, e2) }
    protected val operator = "platform-op"
    protected val engineer = "eng-e1"
    protected val authorizer = FakeAuthorizer(mapOf(engineer to setOf(E1), "eng-e2" to setOf(E2)), setOf(operator))
}
