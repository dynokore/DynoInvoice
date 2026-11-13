package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonPackActivatedDataReader
import com.dynokore.gst.submission.adapters.events.JacksonPackStagedDataReader
import com.dynokore.gst.submission.application.pack.RawMargin
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The RulePackStaged and RulePackActivated readers (INV-AC-20786). */
class JacksonPackEventDataReadersTest {
    private val staged = JacksonPackStagedDataReader()
    private val activated = JacksonPackActivatedDataReader()

    private fun stagedJson(
        contents: String = """{"safetyMargin":{"value":5,"unit":"DAYS"}}""",
        top: String = "",
        identifier: String? = "\"P-CAL\"",
    ) = buildString {
        append("{")
        if (identifier != null) append("\"packIdentifier\":$identifier,")
        append("\"packType\":\"calendar\",\"version\":\"1\",\"effectiveFrom\":\"2026-01-01\",")
        append(top)
        append("\"artefact\":{\"contents\":$contents}}")
    }

    private fun marginOf(contents: String) = staged.read(stagedJson(contents = contents)).margin

    private fun activatedFails(json: String): String =
        shouldThrow<ContractViolation> { activated.read(json) }.message.orEmpty()

    @Test
    fun `INV-AC-20786 a staged calendar payload is read with its margin from artefact contents`() {
        val d = staged.read(stagedJson())
        d.packIdentifier shouldBe "P-CAL"
        d.packType shouldBe "calendar"
        d.version shouldBe "1"
        d.margin shouldBe RawMargin(5, "DAYS")
    }

    @Test
    fun `INV-AC-20786 a margin at the top level of the payload is not read`() {
        val top = """"safetyMargin":{"value":5,"unit":"DAYS"},"""
        staged.read(stagedJson(contents = "{}", top = top)).margin shouldBe null
    }

    @Test
    fun `INV-AC-20786 an absent or non-object margin is read as no margin`() {
        listOf("{}", """{"safetyMargin":null}""", """{"safetyMargin":5}""", "[]", "\"x\"").forEach {
            staged.read(stagedJson(contents = it)).margin shouldBe null
        }
    }

    @Test
    fun `INV-AC-20786 a non-integral value or non-string unit is carried as null for the handler to refuse`() {
        marginOf("""{"safetyMargin":{"value":5.5,"unit":"DAYS"}}""") shouldBe RawMargin(null, "DAYS")
        marginOf("""{"safetyMargin":{"value":"5","unit":"DAYS"}}""") shouldBe RawMargin(null, "DAYS")
        marginOf("""{"safetyMargin":{"value":5,"unit":3}}""") shouldBe RawMargin(5, null)
        marginOf("""{"safetyMargin":{"value":8785,"unit":"DAYS","extra":1}}""") shouldBe
            RawMargin(8785, "DAYS")
    }

    @Test
    fun `INV-AC-20786 a staged payload missing a required member is a ContractViolation`() {
        shouldThrow<ContractViolation> { staged.read(stagedJson(identifier = null)) }
        shouldThrow<ContractViolation> { staged.read(stagedJson(identifier = "\"\"")) }
        shouldThrow<ContractViolation> { staged.read(stagedJson(identifier = "7")) }
        shouldThrow<ContractViolation> { staged.read("""{"packIdentifier":"X","packType":"calendar"}""") }
        shouldThrow<ContractViolation> { staged.read("""{"packIdentifier":"X","version":"1"}""") }
        shouldThrow<ContractViolation> { staged.read("{") }
        shouldThrow<ContractViolation> { staged.read("[]") }
        val e = shouldThrow<ContractViolation> { staged.read("""{"packIdentifier":"SECRET-X","packType":"c"}""") }
        e.message.orEmpty().contains("SECRET-X") shouldBe false
    }

    @Test
    fun `INV-AC-20786 an activated payload is read`() {
        val d = activated.read("""{"packIdentifier":"P-CAL","version":"2","effectiveFrom":"2026-07-01","other":1}""")
        d.packIdentifier shouldBe "P-CAL"
        d.version shouldBe "2"
        d.effectiveFrom shouldBe LocalDate.of(2026, 7, 1)
    }

    @Test
    fun `INV-AC-20786 an activated payload missing a member or with a bad date is a ContractViolation`() {
        shouldThrow<ContractViolation> { activated.read("""{"version":"2","effectiveFrom":"2026-07-01"}""") }
        shouldThrow<ContractViolation> { activated.read("""{"packIdentifier":"P","effectiveFrom":"2026-07-01"}""") }
        shouldThrow<ContractViolation> { activated.read("""{"packIdentifier":"P","version":"2"}""") }
        shouldThrow<ContractViolation> { activated.read("""{"packIdentifier":"P","version":"2","effectiveFrom":1}""") }
        activatedFails(
            """{"packIdentifier":"P","version":"2","effectiveFrom":"SECRET-D"}""",
        ).contains("SECRET-D") shouldBe
            false
        shouldThrow<ContractViolation> { activated.read("not json") }
    }
}
