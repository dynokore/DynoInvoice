package com.dynokore.gst.payload.domain

import com.dynokore.platform.integrationcanon.AcceptanceCalendar
import com.dynokore.platform.integrationcanon.AcceptanceEntry
import com.dynokore.platform.integrationcanon.ReleaseOrder
import com.dynokore.platform.integrationcanon.SpecVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

class SubmissionReleaseChoiceTest {
    private val pint = SpecificationFamily.PINT_SG
    private val bis = SpecificationFamily.SG_PEPPOL_BIS
    private val v1 = SpecVersion("v1")
    private val v2 = SpecVersion("v2")
    private val v3 = SpecVersion("v3")
    private val july1 = LocalDate.parse("2026-07-01")

    private fun entry(
        v: SpecVersion,
        from: String,
        to: String? = null,
    ) = AcceptanceEntry(v, LocalDate.parse(from), to?.let(LocalDate::parse))

    private fun calendar(
        order: List<SpecVersion>,
        vararg entries: AcceptanceEntry,
    ) = AcceptanceCalendar.of(ReleaseOrder.of(order), entries.toList())

    private val case1 = calendar(listOf(v1, v2, v3), entry(v1, "2026-01-01"), entry(v2, "2026-06-01"))
    private val case2 =
        calendar(
            listOf(v1, v2, v3),
            entry(v1, "2026-01-01", "2026-06-30"),
            entry(v2, "2026-01-01"),
            entry(v3, "2026-06-01"),
        )

    @Test
    fun `INV-AC-13164 case 1 chooses v1 beside the family`() {
        SubmissionReleaseChoice.choose(pint, case1, v1, july1) shouldBe
            ReleaseChoice.Chosen(SubmissionRelease(pint, v1))
    }

    @Test
    fun `INV-AC-13164 case 2 chooses the oldest still accepted v2 not the newest`() {
        SubmissionReleaseChoice.choose(pint, case2, v1, july1) shouldBe
            ReleaseChoice.Chosen(SubmissionRelease(pint, v2))
    }

    @Test
    fun `INV-AC-13164 case 4 chooses v2 not the older accepted v1 and not the newest v3`() {
        val cal =
            calendar(
                listOf(v1, v2, v3),
                entry(v1, "2026-01-01"),
                entry(v2, "2026-01-01"),
                entry(v3, "2026-06-01"),
            )
        SubmissionReleaseChoice.choose(pint, cal, v2, july1) shouldBe
            ReleaseChoice.Chosen(SubmissionRelease(pint, v2))
    }

    @Test
    fun `INV-AC-13164 case 3 has no accepted release for a v3 transaction-date release`() {
        val cal = calendar(listOf(v1, v2, v3), entry(v1, "2026-01-01"), entry(v2, "2026-01-01"))
        SubmissionReleaseChoice.choose(pint, cal, v3, july1) shouldBe
            ReleaseChoice.NoAcceptedRelease(pint, v3, july1, listOf(v1, v2))
    }

    @Test
    fun `the family is carried through`() {
        SubmissionReleaseChoice.choose(bis, case1, v1, july1) shouldBe
            ReleaseChoice.Chosen(SubmissionRelease(bis, v1))
    }

    @Test
    fun `acceptedTo equal to the submission date still accepts`() {
        val cal = calendar(listOf(v1, v2), entry(v1, "2026-01-01", "2026-07-01"), entry(v2, "2026-01-01"))
        SubmissionReleaseChoice.choose(pint, cal, v1, july1) shouldBe
            ReleaseChoice.Chosen(SubmissionRelease(pint, v1))
    }

    @Test
    fun `acceptedTo the day before the submission date does not accept`() {
        val cal = calendar(listOf(v1, v2), entry(v1, "2026-01-01", "2026-06-30"), entry(v2, "2026-01-01"))
        SubmissionReleaseChoice.choose(pint, cal, v1, july1) shouldBe
            ReleaseChoice.Chosen(SubmissionRelease(pint, v2))
    }

    @Test
    fun `a transaction-date release equal to the oldest accepted release is chosen never an older one`() {
        val cal =
            calendar(
                listOf(v1, v2, v3),
                entry(v1, "2026-01-01"),
                entry(v2, "2026-01-01"),
                entry(v3, "2026-01-01"),
            )
        SubmissionReleaseChoice.choose(pint, cal, v2, july1) shouldBe
            ReleaseChoice.Chosen(SubmissionRelease(pint, v2))
    }

    @Test
    fun `release order decides not string order`() {
        val v9 = SpecVersion("v9")
        val v10 = SpecVersion("v10")
        val cal = calendar(listOf(v9, v10), entry(v9, "2026-01-01"), entry(v10, "2026-01-01"))
        SubmissionReleaseChoice.choose(pint, cal, v9, july1) shouldBe
            ReleaseChoice.Chosen(SubmissionRelease(pint, v9))
    }

    @Test
    fun `a transaction-date release outside the order throws`() {
        shouldThrow<IllegalArgumentException> {
            SubmissionReleaseChoice.choose(pint, case1, SpecVersion("v7"), july1)
        }
    }

    @Test
    fun `a submission date before every window gives no accepted release with an empty list`() {
        SubmissionReleaseChoice.choose(pint, case1, v1, LocalDate.parse("2025-12-31")) shouldBe
            ReleaseChoice.NoAcceptedRelease(pint, v1, LocalDate.parse("2025-12-31"), emptyList())
    }

    @Test
    fun `the family enum keeps its two members and the release is not added to it`() {
        SpecificationFamily.entries.map { it.name } shouldBe listOf("PINT_SG", "SG_PEPPOL_BIS")
        SubmissionRelease::class.java.declaredFields
            .map { it.name }
            .toSet() shouldBe setOf("family", "release")
    }

    @Test
    fun `the main file holds no copy of the selection loop`() {
        val main = File("src/main/kotlin/com/dynokore/gst/payload/domain/SubmissionReleaseChoice.kt")
        main.isFile shouldBe true
        main.readText().contains("acceptedOn(") shouldBe false
    }
}
