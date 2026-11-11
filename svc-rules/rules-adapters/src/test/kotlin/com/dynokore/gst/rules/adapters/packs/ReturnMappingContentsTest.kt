package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.coding.Direction
import com.dynokore.gst.rules.domain.pack.BoxBasis
import com.dynokore.gst.rules.domain.pack.MappingSignOff
import com.dynokore.gst.rules.domain.pack.PackEntry
import com.dynokore.gst.rules.domain.pack.ReturnBox
import com.dynokore.gst.rules.domain.pack.SourceClass
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The RETURN_MAPPING contents decoder (INV-REQ-654) and the sign-off over its entries. All values are invented. */
class ReturnMappingContentsTest {
    private fun entry(
        code: String = "TX",
        direction: String = "PURCHASE",
        boxes: String = """[{"box":"5","basis":"TAXABLE_AMOUNT"},{"box":"7","basis":"TAX_AMOUNT"}]""",
        sourceClass: String? = "PRIMARY",
        reference: String? = "ASR+ Appendix 2",
    ): String =
        listOfNotNull(
            """"categoryCode":"$code"""",
            """"direction":"$direction"""",
            """"boxes":$boxes""",
            sourceClass?.let { """"sourceClass":"$it"""" },
            reference?.let { """"sourceReference":"$it"""" },
        ).joinToString(",", "{", "}")

    private fun pack(vararg entries: String) = """{"entries":[${entries.joinToString(",")}]}""".toByteArray()

    private fun failure(vararg entries: String) =
        shouldThrow<PackContentsFailure> { ReturnMappingPackContents.decode(pack(*entries)) }

    @Test
    fun `INV-AC-7968 an entry decodes with every member`() {
        val e = ReturnMappingPackContents.decode(pack(entry())).single()
        e.categoryCode shouldBe "TX"
        e.direction shouldBe Direction.PURCHASE
        e.boxes shouldBe listOf(ReturnBox("5", BoxBasis.TAXABLE_AMOUNT), ReturnBox("7", BoxBasis.TAX_AMOUNT))
        e.sourceClass shouldBe SourceClass.PRIMARY
        e.sourceReference shouldBe "ASR+ Appendix 2"
    }

    @Test
    fun `INV-AC-7968 an entry without sourceClass or without sourceReference is refused naming the entry`() {
        failure(entry(sourceClass = null)).message shouldContain "$.entries[0].sourceClass"
        failure(entry(), entry(code = "SR", reference = null)).message shouldContain "$.entries[1].sourceReference"
    }

    @Test
    fun `INV-AC-7968 a direction outside SALE and PURCHASE is refused`() {
        failure(entry(direction = "BOTH")).message shouldContain "$.entries[0].direction"
        failure(entry(direction = "sale")).message shouldContain "$.entries[0].direction"
    }

    @Test
    fun `INV-AC-7968 a repeated category code and direction pair is refused and the other direction is not`() {
        failure(entry(), entry()).message shouldContain "TX/PURCHASE"
        ReturnMappingPackContents.decode(pack(entry(), entry(direction = "SALE"))).size shouldBe 2
    }

    @Test
    fun `INV-AC-7968 a Box repeated within one entry is refused naming the entry`() {
        failure(entry(boxes = """[{"box":"5","basis":"TAXABLE_AMOUNT"},{"box":"5","basis":"TAX_AMOUNT"}]"""))
            .message
            .shouldContain("$.entries[0].boxes")
    }

    @Test
    fun `INV-AC-7970 a SECONDARY entry decodes as SECONDARY with its sourceReference unchanged`() {
        val e =
            ReturnMappingPackContents
                .decode(
                    pack(entry(sourceClass = "SECONDARY", reference = "vendor note 4")),
                ).single()
        e.sourceClass shouldBe SourceClass.SECONDARY
        e.sourceReference shouldBe "vendor note 4"
    }

    private fun signOff(
        accepted: Set<String>,
        vararg entries: String,
    ) = MappingSignOff.attempt(
        ReturnMappingPackContents.decode(pack(*entries)).map { it.toPackEntry() },
        accepted,
    )

    private val tx = entry()
    private val scheme = entry(code = "SCHEME", sourceClass = "INFERRED", reference = "none")
    private val secondary = entry(code = "OTHER", direction = "SALE", sourceClass = "SECONDARY", reference = "vendor")

    @Test
    fun `INV-AC-1716 a non-PRIMARY entry blocks sign-off with RUL-007 until it is accepted`() {
        signOff(emptySet(), tx, scheme) shouldBe
            MappingSignOff.Outcome.Blocked(ErrorCode.RUL_007, listOf("SCHEME/PURCHASE"))
        signOff(setOf("SCHEME/PURCHASE"), tx, scheme) shouldBe MappingSignOff.Outcome.SignedOff
    }

    @Test
    fun `INV-AC-7969 sign-off names exactly the non-PRIMARY entries until both are accepted`() {
        signOff(emptySet(), tx, scheme, secondary) shouldBe
            MappingSignOff.Outcome.Blocked(ErrorCode.RUL_007, listOf("SCHEME/PURCHASE", "OTHER/SALE"))
        signOff(setOf("SCHEME/PURCHASE"), tx, scheme, secondary) shouldBe
            MappingSignOff.Outcome.Blocked(ErrorCode.RUL_007, listOf("OTHER/SALE"))
        signOff(setOf("SCHEME/PURCHASE", "OTHER/SALE"), tx, scheme, secondary) shouldBe MappingSignOff.Outcome.SignedOff
    }

    @Test
    fun `INV-AC-7969 an entry id that does not match the mapping stays blocked`() {
        signOff(setOf("SCHEME"), tx, scheme) shouldBe
            MappingSignOff.Outcome.Blocked(ErrorCode.RUL_007, listOf("SCHEME/PURCHASE"))
        MappingSignOff.attempt(
            listOf(PackEntry("SCHEME", "none", SourceClass.INFERRED)),
            setOf("SCHEME/PURCHASE"),
        ) shouldBe
            MappingSignOff.Outcome.Blocked(ErrorCode.RUL_007, listOf("SCHEME"))
    }
}
