package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.connection.CircuitBreakerState
import com.dynokore.platform.serviceruntime.connection.ConnectionState
import com.dynokore.platform.serviceruntime.connection.ConnectionTestClass
import com.dynokore.platform.serviceruntime.connection.ConnectionTestResult
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID

/** The api half's job and connection contract types (INV-REQ-1136, INV-REQ-1138, INV-REQ-836). */
class JobContractTypesTest {
    private fun job(
        tolerance: Duration = Duration.ofMinutes(5),
        lockAtMostFor: Duration = JobDefinition.DEFAULT_LOCK_AT_MOST_FOR,
        lockAtLeastFor: Duration = JobDefinition.DEFAULT_LOCK_AT_LEAST_FOR,
    ) = JobDefinition(
        "scheduled-despatch",
        "submission-svc",
        JobEntityScope.ENTITY,
        JobSchedule("0 0 * * * *"),
        JobCriticality.DEADLINE_CRITICAL,
        tolerance,
        lockAtMostFor,
        lockAtLeastFor,
    )

    @Test
    fun `a job carries the TPL-09 lock defaults and refuses a negative tolerance or leases out of order`() {
        job().lockAtMostFor shouldBe Duration.ofMinutes(10)
        job().lockAtLeastFor shouldBe Duration.ofSeconds(30)
        assertThrows<IllegalArgumentException> { job(tolerance = Duration.ofSeconds(-1)) }
        assertThrows<IllegalArgumentException> {
            job(lockAtMostFor = Duration.ofSeconds(10), lockAtLeastFor = Duration.ofSeconds(20))
        }
        assertThrows<IllegalArgumentException> { job().copy(name = "Scheduled Despatch") }
    }

    @Test
    fun `the whole job is the platform tenant and entity, an entity share never is`() {
        JobTarget.Whole.tenant shouldBe TenantId.PLATFORM
        JobTarget.Whole.entity shouldBe EntityRef.PLATFORM
        assertThrows<IllegalArgumentException> { JobTarget.Entity(TenantId.PLATFORM, EntityRef("E-1")) }
        assertThrows<IllegalArgumentException> {
            JobTarget.Entity(TenantId(UUID.randomUUID()), EntityRef("E".repeat(41)))
        }
    }

    @Test
    fun `an entity share can carry the tenant-wide entity with a real tenant`() {
        val tenant = TenantId(UUID.randomUUID())
        val target = JobTarget.Entity(tenant, EntityRef.TENANT_WIDE)
        target.entity shouldBe EntityRef.TENANT_WIDE
        target.tenant shouldBe tenant
    }

    @Test
    fun `the platform tenant with a real entity is refused`() {
        assertThrows<IllegalArgumentException> { JobTarget.Entity(TenantId.PLATFORM, EntityRef("E-1")) }
    }

    @Test
    fun `a real tenant with the platform entity is refused`() {
        assertThrows<IllegalArgumentException> { JobTarget.Entity(TenantId(UUID.randomUUID()), EntityRef.PLATFORM) }
    }

    @Test
    fun `an entity reference of 41 characters is refused`() {
        assertThrows<IllegalArgumentException> {
            JobTarget.Entity(TenantId(UUID.randomUUID()), EntityRef("E".repeat(41)))
        }
    }

    @Test
    fun `the platform tenant with the tenant-wide entity is refused`() {
        assertThrows<IllegalArgumentException> { JobTarget.Entity(TenantId.PLATFORM, EntityRef.TENANT_WIDE) }
    }

    @Test
    fun `a command reason is at most 500 characters`() {
        fun request(reason: String?): JobCommandRequest {
            val id = UUID.randomUUID()
            return JobCommandRequest("u-1", "j", null, id, reason, CorrelationId("c"), CausationId("c"))
        }
        request("x".repeat(500)).reason?.length shouldBe 500
        assertThrows<IllegalArgumentException> { request("x".repeat(501)) }
        assertThrows<IllegalArgumentException> { request(" ") }
    }

    @Test
    fun `the job error codes are the catalogue's JOB codes with their HTTP statuses`() {
        JobErrorCode.entries.map { it.code to it.httpStatus } shouldBe
            listOf("JOB-001" to 404, "JOB-002" to 403, "JOB-003" to 422, "JOB-004" to 422)
    }

    @Test
    fun `the connection state maps from the circuit breaker and a test result carries a code only`() {
        CircuitBreakerState.entries.map { ConnectionState.of(it).wire } shouldBe listOf("up", "degraded", "down")
        ConnectionTestResult(ConnectionTestClass.AUTHENTICATION_REFUSED, "TRN-004").errorCode shouldBe "TRN-004"
        assertThrows<IllegalArgumentException> {
            ConnectionTestResult(ConnectionTestClass.UNREACHABLE, "connect timed out to 10.0.0.1")
        }
    }
}
