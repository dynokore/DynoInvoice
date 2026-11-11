package com.dynokore.gst.rules.application

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.UUID

/** The port of the last complete period (INV-REQ-1303); fakes only. */
class LastCompletePeriodPortTest {
    private val tenant = TenantId(UUID.fromString("11111111-1111-4111-8111-111111111111"))

    /**
     * Conformance fixture: the compiler checks the method name, both parameter types, their non-nullability and
     * the nullable return type. `TenantId` is a value class, so the compiled name and types are mangled and are
     * not asserted.
     */
    private object NoPeriod : LastCompletePeriodPort {
        override fun lastCompletePeriod(
            tenant: TenantId,
            entityRef: String,
        ): String? = null
    }

    @Test
    fun `INV-AC-20211 a conforming implementation compiles and returns null`() {
        val port: LastCompletePeriodPort = NoPeriod
        port.lastCompletePeriod(tenant, "E") shouldBe null
    }

    @Test
    fun `INV-AC-20211 the port has one entry point`() {
        LastCompletePeriodPort::class.java.declaredMethods
            .count { Modifier.isAbstract(it.modifiers) && !it.isSynthetic } shouldBe 1
    }

    @Test
    fun `INV-AC-20211 the port hands the tenant and the entity to the calendar`() {
        var seenTenant: TenantId? = null
        var seenEntity: String? = null
        val port =
            LastCompletePeriodPort { t, e ->
                seenTenant = t
                seenEntity = e
                "P-1"
            }
        port.lastCompletePeriod(tenant, "ENT-7")
        seenTenant shouldBe tenant
        seenEntity shouldBe "ENT-7"
    }

    @Test
    fun `INV-AC-20211 an absent period and an unavailable calendar are different answers`() {
        LastCompletePeriodPort { _, _ -> null }.lastCompletePeriod(tenant, "E") shouldBe null
        LastCompletePeriodPort { _, _ -> "2026-Q3" }.lastCompletePeriod(tenant, "E") shouldBe "2026-Q3"
        val thrown =
            shouldThrow<PeriodCalendarUnavailableException> {
                LastCompletePeriodPort { _, _ -> throw PeriodCalendarUnavailableException("x") }
                    .lastCompletePeriod(tenant, "E")
            }
        thrown.reason shouldBe "x"
    }

    @Test
    fun `INV-AC-20211 the dry run use case does not depend on the port`() {
        val type = LastCompletePeriodPort::class.java
        RunDryRun::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }
            .none { it == type } shouldBe true
        RunDryRun::class.java.declaredFields.none { it.type == type } shouldBe true
        // look-alike: another port must not match
        (HistoricalSamplePort::class.java == type) shouldBe false
    }

    @Test
    fun `INV-AC-20211 the exception is a runtime exception`() {
        (PeriodCalendarUnavailableException::class.java.superclass == RuntimeException::class.java) shouldBe true
        (Exception::class.java == RuntimeException::class.java) shouldBe false
    }
}
