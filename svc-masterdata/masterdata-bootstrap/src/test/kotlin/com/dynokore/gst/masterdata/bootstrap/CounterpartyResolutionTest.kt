package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.http.ApiRefusal
import com.dynokore.gst.masterdata.contract.model.CounterpartyAddress
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * INV-T-4943 at the service boundary: `resolveCounterparty` returns UEN, name and address for every counterparty and
 * reports MDM-002, MDM-004 and MDM-006 (INV-REQ-313, INV-REQ-315). A finding never changes the 200 and nothing is
 * transmitted by this service.
 */
class CounterpartyResolutionTest {
    private val block = { code: String -> Triple(code, "BLOCK", "MANUAL") }

    @Test
    fun `INV-AC-343 INV-AC-8374 a customer with no UEN and a scheme other than NONE resolves with MDM-002`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            val gstn = unique("M2")
            env.triple(entity)
            env.write(unique("CTP"), env.request("GSTN" to gstn, name = "Acme Pte Ltd", address = ADDRESS))
            val before = env.captured.envelopes.size
            val result = env.resolve(entity, "GSTN", gstn)
            assertThat(env.codes(result)).containsExactly(block("MDM-002"))
            assertThat(result.counterparty).isNotNull
            assertThat(env.captured.envelopes).hasSize(before)
        }

    @Test
    fun `INV-AC-344 INV-AC-8371 an individual under a personal name carries NA in UEN, name and address and passes`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            val gstn = unique("M2")
            env.triple(entity)
            val na = CounterpartyAddress(addressLine1 = "NA", postalCode = "NA")
            env.write(unique("CTP"), env.request("NONE" to "NA", "GSTN" to gstn, name = "NA", address = na))
            val result = env.resolve(entity, "GSTN", gstn)
            val party = result.counterparty!!
            assertThat(result.findings).isNullOrEmpty()
            assertThat(party.resolvedIdentifiers.single { it.scheme == "NONE" }.value).isEqualTo("NA")
            assertThat(party.legalName).isEqualTo("NA")
            assertThat(party.address).isEqualTo(na)
            // the scheme NONE is no key: its value NA, shared by every individual, finds no counterparty
            assertThat(
                env.codes(env.resolve(entity, "NONE", "NA")),
            ).containsExactly(Triple("MDM-001", "HOLD", "AUTO_RELEASE"))
        }

    @Test
    fun `INV-AC-345 INV-AC-8371 an overseas customer carries its registration number as UEN and passes`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            val registration = unique("OV-REG")
            val gstn = unique("M2")
            env.triple(entity)
            val identifiers =
                env.identifiers(IdentifierScheme.OVERSEAS_REGISTRATION to registration, IdentifierScheme.GSTN to gstn)
            val change =
                CounterpartyChange(
                    identifiers,
                    "Overseas GmbH",
                    PostalAddress("1 Raffles Place", null, "048616"),
                    null,
                    null,
                )
            env.recorded(unique("CTP"), WriteOrigin.OPERATOR, "EVD-OV", change)
            val result = env.resolve(entity, "GSTN", gstn)
            val party = result.counterparty!!
            assertThat(result.findings).isNullOrEmpty()
            assertThat(
                party.resolvedIdentifiers.map { it.scheme to it.value },
            ).contains("OVERSEAS_REGISTRATION" to registration)
            assertThat(party.legalName).isEqualTo("Overseas GmbH")
            assertThat(party.address).isEqualTo(ADDRESS)
        }

    @Test
    fun `INV-AC-1605 INV-AC-8371 INV-AC-8374 NA in some but not all of UEN, name and address blocks with MDM-004`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)
            val cases =
                listOf(
                    // the criterion: UEN and name NA, a real address
                    "NA" to ADDRESS,
                    // UEN NA, name real, address NA
                    "Real Name" to CounterpartyAddress("NA", "NA"),
                    // UEN, name and address line 1 NA, a real postal code
                    "NA" to CounterpartyAddress("NA", "048616"),
                )
            cases.forEachIndexed { index, (name, address) ->
                val gstn = unique("M2C$index")
                env.write(unique("CTP"), env.request("NONE" to "NA", "GSTN" to gstn, name = name, address = address))
                val result = env.resolve(entity, "GSTN", gstn)
                assertThat(env.codes(result)).describedAs("case $index").containsExactly(block("MDM-004"))
            }
            // NA in the name of a customer with a UEN is partial too: NA is valid only as a whole, for scheme NONE
            val uen = unique("UEN")
            env.write(unique("CTP"), env.request("UEN" to uen, name = "NA", address = ADDRESS))
            assertThat(env.codes(env.resolve(entity, "UEN", uen))).containsExactly(block("MDM-004"))
        }

    @Test
    fun `INV-AC-1606 INV-AC-8371 INV-AC-8374 a missing address line 1 or postal code blocks with MDM-006`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)
            val noPostal = unique("UEN")
            val partial = CounterpartyAddress("1 Raffles Place", "")
            env.write(unique("CTP"), env.request("UEN" to noPostal, name = "Acme", address = partial))
            val result = env.resolve(entity, "UEN", noPostal)
            assertThat(env.codes(result)).containsExactly(block("MDM-006"))
            assertThat(result.findings!!.single().field).isEqualTo("/address/postalCode")
            val noLine = unique("UEN")
            env.write(
                unique("CTP"),
                env.request("UEN" to noLine, name = "Acme", address = CounterpartyAddress(" ", "048616")),
            )
            assertThat(env.resolve(entity, "UEN", noLine).findings!!.map { it.code to it.field })
                .containsExactly("MDM-006" to "/address/addressLine1")
            val noAddress = unique("UEN")
            env.write(unique("CTP"), env.request("UEN" to noAddress, name = "Acme"))
            assertThat(
                env.codes(env.resolve(entity, "UEN", noAddress)),
            ).containsExactly(block("MDM-006"), block("MDM-006"))
            val complete = unique("UEN")
            env.write(unique("CTP"), env.request("UEN" to complete, name = "Acme", address = ADDRESS))
            assertThat(env.resolve(entity, "UEN", complete).findings).isNullOrEmpty()
        }

    @Test
    fun `resolution of an unknown party is the hold pattern MDM-001 and a half-given party is refused`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)
            val hold = Triple("MDM-001", "HOLD", "AUTO_RELEASE")
            val unknown = env.resolve(entity, "UEN", unique("NOBODY"))
            assertThat(env.codes(unknown)).containsExactly(hold)
            assertThat(unknown.counterparty).isNull()
            assertThat(env.resolve(entity, null, null).findings).isNullOrEmpty()
            val half = runCatching { env.resolve(entity, "UEN", null) }.exceptionOrNull()
            assertThat((half as ApiRefusal).code).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(env.codes(env.resolve(entity, "UNKNOWN_SCHEME", "x"))).containsExactly(hold)
        }
}
