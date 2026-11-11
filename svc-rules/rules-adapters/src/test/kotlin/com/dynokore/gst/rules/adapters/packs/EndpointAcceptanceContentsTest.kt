package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.domain.pack.SpecAcceptance
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The ENDPOINT_ACCEPTANCE contents decoder (INV-REQ-589). The valid sample is the contract's open-ended example. */
class EndpointAcceptanceContentsTest {
    private fun failure(json: String) =
        shouldThrow<PackContentsFailure> { EndpointAcceptancePackContents.decode(json.toByteArray()) }

    private fun one(member: String) = """{"acceptances":[{"specVersion":"1.0",$member}]}"""

    @Test
    fun `a valid pack equals the valid sample of the contract`() {
        EndpointAcceptancePackContents.decode(
            """{"acceptances":[
              {"specVersion":"1.0","accepted_from":"2026-01-01","accepted_to":"2027-03-31"},
              {"specVersion":"1.1","accepted_from":"2027-01-01"}]}""".toByteArray(),
        ) shouldBe
            listOf(
                SpecAcceptance("1.0", LocalDate.parse("2026-01-01"), LocalDate.parse("2027-03-31")),
                SpecAcceptance("1.1", LocalDate.parse("2027-01-01"), null),
            )
    }

    @Test
    fun `a malformed date is refused naming the member`() {
        failure(one(""""accepted_from":"2027-13-45"""")).message shouldContain "$.acceptances[0].accepted_from"
        failure(one(""""accepted_from":"2027-01-01","accepted_to":"2027-1-5"""")).message shouldContain
            "$.acceptances[0].accepted_to"
    }

    @Test
    fun `a missing accepted_from is refused naming the member`() {
        failure(one(""""accepted_to":"2027-03-31"""")).message shouldContain "$.acceptances[0].accepted_from"
    }

    @Test
    fun `an accepted_to before accepted_from is refused naming the member`() {
        failure(one(""""accepted_from":"2027-02-01","accepted_to":"2027-01-31"""")).message shouldContain
            "$.acceptances[0].accepted_to"
    }

    @Test
    fun `a repeated specVersion and accepted_from pair is refused`() {
        val row = """{"specVersion":"1.0","accepted_from":"2027-01-01"}"""
        failure("""{"acceptances":[$row,$row]}""").message shouldContain "$.acceptances"
    }

    @Test
    fun `an empty list and a version longer than 32 characters are refused`() {
        failure("""{"acceptances":[]}""").message shouldContain "$.acceptances"
        failure(one(""""accepted_from":"2027-01-01"""").replace("1.0", "v".repeat(33))).message shouldContain
            "$.acceptances[0].specVersion"
    }
}
