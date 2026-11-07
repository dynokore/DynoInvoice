package com.dynokore.gst.payload.domain.party

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PartyIdentifierRulesTest {
    private val triple = IdentityTriple("0195:201912345A", "201912345A", "M2-1234567-8")
    private val centre = PartyInput("0195", "SSC-CENTRE-9")

    private fun resolve(
        buyer: PartyInput,
        identity: IdentityTriple? = triple,
    ) = PartyIdentifierRules.resolve(PartyIdentifierInput(buyer, centre, identity))

    @Test
    fun `IdentityTripleAbsent gives Refused whatever the caller supplier holds`() {
        resolve(PartyInput("SG_UEN", "201888888A"), null) shouldBe
            PartyIdentifiers.Refused(RefusalReason.IDENTITY_TRIPLE_ABSENT)
    }

    @Test
    fun `the supplier fields equal the triple and the caller supplier is not read`() {
        resolve(PartyInput("SG_UEN", "201888888A")) shouldBe
            PartyIdentifiers.Resolved("201888888A", "0195:201912345A", "201912345A", "M2-1234567-8")
    }

    @Test
    fun `the buyer id is set only for the exact scheme SG_UEN`() {
        for (scheme in listOf("sg_uen", "SG_UEN2", "SG_UEN ", " SG_UEN", "0195", "")) {
            (resolve(PartyInput(scheme, "201888888A")) as PartyIdentifiers.Resolved).buyerLegalRegistrationId shouldBe
                null
        }
    }
}
