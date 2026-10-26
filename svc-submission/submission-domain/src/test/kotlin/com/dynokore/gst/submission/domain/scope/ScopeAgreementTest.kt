package com.dynokore.gst.submission.domain.scope

import com.dynokore.gst.submission.domain.scope.NotSubmittableReason.BUYER_REJECTED
import com.dynokore.gst.submission.domain.scope.ScopeDecision.Agree
import com.dynokore.gst.submission.domain.scope.ScopeDecision.Disagree
import com.dynokore.gst.submission.domain.scope.ScopeDecision.NotComparable
import com.dynokore.gst.submission.domain.scope.ScopeVerdict.NOT_SUBMITTABLE
import com.dynokore.gst.submission.domain.scope.ScopeVerdict.PRE_OBLIGATION
import com.dynokore.gst.submission.domain.scope.ScopeVerdict.SUBMITTABLE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

class ScopeAgreementTest {
    private val inScope = Verdict(SUBMITTABLE)
    private val outOfScope = Verdict(NOT_SUBMITTABLE)
    private val preObligation = Verdict(PRE_OBLIGATION)

    private fun reasoned(reason: NotSubmittableReason) = Verdict(NOT_SUBMITTABLE, reason)

    @Test
    fun `INV-AC-754 credit note in scope with preceding out of scope is a disagreement`() {
        ScopeAgreement.decide(inScope, outOfScope) shouldBe Disagree(ScopeCode.SUB_013, inScope, outOfScope)
    }

    @Test
    fun `INV-AC-754 credit note out of scope with preceding in scope is a disagreement`() {
        ScopeAgreement.decide(outOfScope, inScope) shouldBe Disagree(ScopeCode.SUB_013, outOfScope, inScope)
    }

    @Test
    fun `INV-AC-754 the disagreement code string is SUB-013`() {
        ScopeCode.entries.map { it.name } shouldBe listOf("SUB_013")
        ScopeCode.SUB_013.code shouldBe "SUB-013"
        val result = ScopeAgreement.decide(inScope, outOfScope) as Disagree
        result.code.code shouldBe "SUB-013"
    }

    @Test
    fun `INV-AC-754 equal sides agree`() {
        ScopeAgreement.decide(inScope, inScope) shouldBe Agree
        ScopeAgreement.decide(outOfScope, outOfScope) shouldBe Agree
    }

    @Test
    fun `INV-AC-754 the result is a function of the input alone`() {
        ScopeAgreement.decide(inScope, outOfScope) shouldBe ScopeAgreement.decide(inScope, outOfScope)
        ScopeAgreement.decide(inScope, inScope) shouldBe ScopeAgreement.decide(inScope, inScope)
    }

    @Test
    fun `INV-AC-754 pre-obligation preceding is not compared`() {
        ScopeAgreement.decide(inScope, preObligation) shouldBe NotComparable(Why.PRECEDING_SIDE)
        ScopeAgreement.decide(outOfScope, preObligation) shouldBe NotComparable(Why.PRECEDING_SIDE)
    }

    @Test
    fun `INV-AC-754 pre-obligation credit note is not compared`() {
        ScopeAgreement.decide(preObligation, inScope) shouldBe NotComparable(Why.CREDIT_NOTE_SIDE)
        ScopeAgreement.decide(preObligation, outOfScope) shouldBe NotComparable(Why.CREDIT_NOTE_SIDE)
    }

    @Test
    fun `INV-AC-754 both pre-obligation is not compared`() {
        ScopeAgreement.decide(preObligation, preObligation) shouldBe NotComparable(Why.BOTH_SIDES)
    }

    @ParameterizedTest
    @EnumSource(NotSubmittableReason::class)
    fun `INV-AC-754 a reasoned preceding verdict is not compared`(reason: NotSubmittableReason) {
        ScopeAgreement.decide(inScope, reasoned(reason)) shouldBe NotComparable(Why.PRECEDING_SIDE)
    }

    @ParameterizedTest
    @EnumSource(NotSubmittableReason::class)
    fun `INV-AC-754 a reasoned credit note verdict is not compared`(reason: NotSubmittableReason) {
        ScopeAgreement.decide(reasoned(reason), inScope) shouldBe NotComparable(Why.CREDIT_NOTE_SIDE)
    }

    @Test
    fun `INV-AC-754 reasoned on both sides and mixed look-alikes name the sides`() {
        ScopeAgreement.decide(reasoned(BUYER_REJECTED), reasoned(BUYER_REJECTED)) shouldBe
            NotComparable(Why.BOTH_SIDES)
        ScopeAgreement.decide(reasoned(BUYER_REJECTED), preObligation) shouldBe NotComparable(Why.BOTH_SIDES)
        ScopeAgreement.decide(reasoned(BUYER_REJECTED), outOfScope) shouldBe NotComparable(Why.CREDIT_NOTE_SIDE)
        ScopeAgreement.decide(outOfScope, reasoned(BUYER_REJECTED)) shouldBe NotComparable(Why.PRECEDING_SIDE)
    }

    @Test
    fun `INV-AC-754 no preceding document is not compared`() {
        ScopeAgreement.decide(inScope, null) shouldBe NotComparable(Why.NO_PRECEDING)
        ScopeAgreement.decide(outOfScope, null) shouldBe NotComparable(Why.NO_PRECEDING)
        ScopeAgreement.decide(preObligation, null) shouldBe NotComparable(Why.NO_PRECEDING)
    }

    @Test
    fun `INV-AC-754 a reason on a verdict other than NOT_SUBMITTABLE is refused`() {
        shouldThrow<IllegalArgumentException> { Verdict(SUBMITTABLE, BUYER_REJECTED) }
        shouldThrow<IllegalArgumentException> { Verdict(PRE_OBLIGATION, BUYER_REJECTED) }
    }

    @Test
    fun `INV-AC-754 the enums equal the canonical contract enums`() {
        val text = File(System.getProperty("gst.canonical.openapi")).readText()
        val block = text.substringAfter("\n    RecordDocumentResponse:\n").substringBefore("\n    ReasonItem:")

        fun enumOf(property: String): List<String> {
            val body = block.substringAfter("\n        $property:\n").substringBefore("\n        ")
            val list = Regex("enum:\\s*\\[([^\\]]*)]").find(block.substringAfter("\n        $property:\n"))!!
            body.isNotEmpty() shouldBe true
            return list.groupValues[1].split(',').map { it.trim() }
        }
        ScopeVerdict.entries.map { it.name } shouldContainExactlyInAnyOrder enumOf("scopeVerdict")
        NotSubmittableReason.entries.map { it.name } shouldContainExactlyInAnyOrder enumOf("notSubmittableReason")
    }

    @Test
    fun `INV-AC-10454 both disagreement pairs hold the two verdicts as given`() {
        ScopeAgreement.decide(inScope, outOfScope) shouldBe Disagree(ScopeCode.SUB_013, inScope, outOfScope)
        ScopeAgreement.decide(outOfScope, inScope) shouldBe Disagree(ScopeCode.SUB_013, outOfScope, inScope)
        val result = ScopeAgreement.decide(inScope, outOfScope) as Disagree
        (result.creditNote === inScope) shouldBe true
        (result.preceding === outOfScope) shouldBe true
    }

    @Test
    fun `INV-AC-10454 the look-alike with reason BUYER_REJECTED is not compared`() {
        ScopeAgreement.decide(inScope, reasoned(BUYER_REJECTED)) shouldBe NotComparable(Why.PRECEDING_SIDE)
    }

    @Test
    fun `INV-AC-10454 no member of the result carries a replacement verdict`() {
        val allowed = setOf<Class<*>>(Verdict::class.java, ScopeCode::class.java, Why::class.java)
        val members = ScopeDecision::class.java.permittedSubclasses.toList()
        members.map { it.simpleName }.toSet() shouldBe setOf("Agree", "Disagree", "NotComparable")
        val types =
            members.flatMap { member ->
                member.declaredFields
                    .filter {
                        !java.lang.reflect.Modifier
                            .isStatic(it.modifiers)
                    }.map { it.type }
            }
        types.size shouldBe 4
        types.all { it in allowed } shouldBe true
    }

    @Test
    fun `INV-AC-10455 the six pairs of the criterion`() {
        ScopeAgreement.decide(inScope, inScope) shouldBe Agree
        ScopeAgreement.decide(outOfScope, outOfScope) shouldBe Agree
        ScopeAgreement.decide(inScope, outOfScope) shouldBe Disagree(ScopeCode.SUB_013, inScope, outOfScope)
        ScopeAgreement.decide(preObligation, inScope) shouldBe NotComparable(Why.CREDIT_NOTE_SIDE)
        ScopeAgreement.decide(inScope, reasoned(NotSubmittableReason.ISSUANCE_REFUSED)) shouldBe
            NotComparable(Why.PRECEDING_SIDE)
        ScopeAgreement.decide(inScope, null) shouldBe NotComparable(Why.NO_PRECEDING)
    }

    @Test
    fun `INV-AC-10455 the code string is the catalogue code SUB-013`() {
        ScopeCode.SUB_013.code shouldBe "SUB-013"
    }
}
