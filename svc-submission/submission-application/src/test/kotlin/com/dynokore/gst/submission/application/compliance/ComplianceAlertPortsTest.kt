package com.dynokore.gst.submission.application.compliance

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ComplianceAlertPortsTest {
    private val pkg = "com.dynokore.gst.submission.application.compliance"

    // Compile-time conformance: a wrong parameter type, order, return type or an extra abstract
    // method breaks compilation.
    private val ledger =
        object : AlertEmissionLedger {
            override fun claim(
                tenant: TenantId,
                entityRef: String,
                periodRef: String,
                measuredOn: LocalDate,
            ): Boolean = true
        }

    private val source = EmptyTenantSource()

    @Test
    fun `the tenant source is one parameterless tenants method returning a list of tenant ids`() {
        val type = ComplianceTenantSource::class.java
        type.packageName shouldBe pkg
        type.simpleName shouldBe "ComplianceTenantSource"
        val methods = type.declaredMethods.filterNot { it.isSynthetic }
        methods.size shouldBe 1
        val method = methods.single()
        method.name shouldBe "tenants"
        method.parameterTypes.toList() shouldBe emptyList()
        method.returnType.name shouldBe "java.util.List"
        method.genericReturnType.typeName shouldBe "java.util.List<com.dynokore.platform.tenancykernel.TenantId>"
        source.tenants() shouldBe emptyList()
    }

    @Test
    fun `the emission ledger is one claim method with the erased tenant, two strings and a day returning boolean`() {
        val type = AlertEmissionLedger::class.java
        type.packageName shouldBe pkg
        type.simpleName shouldBe "AlertEmissionLedger"
        val methods = type.declaredMethods.filterNot { it.isSynthetic }
        methods.size shouldBe 1
        val method = methods.single()
        // Kotlin mangles the name of a function taking the value class TenantId.
        method.name.substringBefore('-') shouldBe "claim"
        method.parameterTypes.map { it.name } shouldBe
            listOf(UUID::class.java.name, String::class.java.name, String::class.java.name, LocalDate::class.java.name)
        method.returnType shouldBe java.lang.Boolean.TYPE
        ledger.claim(TenantId(UUID.randomUUID()), "e", "p", LocalDate.EPOCH) shouldBe true
    }
}

private class EmptyTenantSource : ComplianceTenantSource {
    override fun tenants(): List<TenantId> = emptyList()
}
