package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.coding.LineCoding
import com.dynokore.gst.rules.domain.evaluation.Evaluator
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.gst.rules.domain.scope.ScopeFacts
import com.dynokore.gst.rules.domain.scope.ScopeVerdict
import com.dynokore.gst.rules.domain.scope.ScopeVerdictPort
import com.dynokore.platform.rulepackkernel.InMemoryPackRegistry
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

class ScopeVerdictPassThroughTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-7000-8000-00000000000a"))
    private val date = TransactionDate(LocalDate.of(2026, 3, 1))
    private val coded = LineCoding.Coded("SR", "9")
    private val refused = LineCoding.Refused(ErrorCode.RUL_001)

    private class Recording(
        private val verdict: ScopeVerdict,
    ) : ScopeVerdictPort {
        val received = mutableListOf<ScopeFacts>()

        override fun verdictOf(facts: ScopeFacts): ScopeVerdict {
            received += facts
            return verdict
        }
    }

    private fun useCase(port: ScopeVerdictPort?) =
        EvaluateDocument(PackResolver(InMemoryPackRegistry()), Evaluator(emptyList()), emptyList(), null, port)

    @Test
    fun `every verdict is returned unchanged for a coded line`() {
        ScopeVerdict.entries.forEach { v ->
            val port = Recording(v)
            useCase(port).scopeVerdictOf(tenant, date, coded) shouldBe v
            port.received shouldBe listOf(ScopeFacts(tenant, date, coded))
        }
    }

    @Test
    fun `INV-AC-10201 every verdict is returned unchanged for a refused RUL_001 line`() {
        ScopeVerdict.entries.forEach { v ->
            val port = Recording(v)
            useCase(port).scopeVerdictOf(tenant, date, refused) shouldBe v
            port.received shouldBe listOf(ScopeFacts(tenant, date, refused))
        }
    }

    @Test
    fun `a port answering IN_SCOPE for a refused line is returned as is`() {
        useCase(Recording(ScopeVerdict.IN_SCOPE)).scopeVerdictOf(null, date, refused) shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `with no port both lines raise ScopeRulesMissing`() {
        listOf(coded, refused).forEach { line ->
            val e = assertThrows<ScopeRulesMissing> { useCase(null).scopeVerdictOf(tenant, date, line) }
            e.message shouldBe "no ScopeVerdictPort is configured"
        }
    }

    @Test
    fun `an exception of the port propagates as the same instance`() {
        val boom = IllegalStateException("boom")
        val port =
            object : ScopeVerdictPort {
                override fun verdictOf(facts: ScopeFacts): ScopeVerdict = throw boom
            }
        listOf(coded, refused).forEach { line ->
            val e = assertThrows<IllegalStateException> { useCase(port).scopeVerdictOf(tenant, date, line) }
            (e === boom) shouldBe true
        }
    }
}
