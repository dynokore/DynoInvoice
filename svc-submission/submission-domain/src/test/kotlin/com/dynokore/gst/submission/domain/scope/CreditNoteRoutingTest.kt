package com.dynokore.gst.submission.domain.scope

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CreditNoteRoutingTest {
    private val invoiceTypeCode = "380"
    private val creditNoteTypeCode = "381"

    /** The pin of INV-AC-13100: at least one `decide`, and every one has exactly one parameter, of type String. */
    private fun decidePinHolds(holder: Class<*>): Boolean {
        val decides = holder.declaredMethods.filter { !it.isSynthetic && it.name == "decide" }
        return decides.isNotEmpty() && decides.all { it.parameterTypes.toList() == listOf(String::class.java) }
    }

    /** Routes a document of the given type code; the type code is data of the test and never reaches the routing. */
    private fun route(
        typeCode: String,
        verdict: String,
    ): ScopeRoute {
        typeCode.isNotEmpty() shouldBe true
        return ScopeVerdictRouting.decide(verdict)
    }

    @ParameterizedTest(name = "INV-AC-1743 INV-AC-13100 [{0}] routes a credit note like an invoice")
    @ValueSource(strings = [SUBMITTABLE, NOT_SUBMITTABLE, PRE_OBLIGATION, "", "submittable"])
    fun `INV-AC-1743 INV-AC-13100 credit note and invoice give the same route for the verdict`(verdict: String) {
        val invoice = route(invoiceTypeCode, verdict)
        val creditNote = route(creditNoteTypeCode, verdict)
        creditNote shouldBe invoice
    }

    @Test
    fun `INV-AC-1743 INV-AC-13100 an in-scope credit note and invoice both give Submittable`() {
        route(invoiceTypeCode, SUBMITTABLE) shouldBe ScopeRoute.Submittable
        route(creditNoteTypeCode, SUBMITTABLE) shouldBe ScopeRoute.Submittable
    }

    @Test
    fun `INV-AC-13100 every decide of ScopeVerdictRouting has exactly one String parameter`() {
        decidePinHolds(ScopeVerdictRouting::class.java) shouldBe true
    }

    @Test
    fun `INV-AC-13100 the pin rejects an overload, a second parameter, no decide and an Any parameter`() {
        decidePinHolds(OneString::class.java) shouldBe true
        decidePinHolds(WithOverload::class.java) shouldBe false
        decidePinHolds(TwoParameters::class.java) shouldBe false
        decidePinHolds(Renamed::class.java) shouldBe false
        decidePinHolds(OneAny::class.java) shouldBe false
    }

    private object OneString {
        fun decide(scopeVerdict: String): String = scopeVerdict
    }

    private object WithOverload {
        fun decide(scopeVerdict: String): String = scopeVerdict

        fun decide(
            scopeVerdict: String,
            documentTypeCode: String,
        ): String = scopeVerdict + documentTypeCode
    }

    private object TwoParameters {
        fun decide(
            scopeVerdict: String,
            documentTypeCode: String,
        ): String = scopeVerdict + documentTypeCode
    }

    private object Renamed {
        fun route(scopeVerdict: String): String = scopeVerdict
    }

    private object OneAny {
        fun decide(scopeVerdict: Any): String = scopeVerdict.toString()
    }
}
