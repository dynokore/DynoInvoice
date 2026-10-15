package com.dynokore.platform.partyregistry

/** The identifier schemes of a party (INV-REQ-313): `NONE` is the individual with no registration number. */
enum class IdentifierScheme { UEN, GSTN, PEPPOL_PARTICIPANT_ID, OVERSEAS_REGISTRATION, NONE }

/** A scheme-qualified identifier with its provenance (INV-REQ-313, INV-REQ-314). */
data class Identifier(
    val scheme: IdentifierScheme,
    val value: String,
    val provenance: Provenance,
) {
    init {
        require(value.isNotBlank()) { "an identifier value must not be blank" }
    }
}

@JvmInline
value class LegalEntityId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a legal entity id must not be blank" }
    }
}

/** A postal address; line 1 and postal code are the mandatory parts (INV-REQ-313). */
data class Address(
    val line1: String,
    val line2: String?,
    val postalCode: String,
    val country: String?,
) {
    init {
        require(line1.isNotBlank()) { "address line 1 must not be blank" }
        require(postalCode.isNotBlank()) { "the postal code must not be blank" }
        require(line2 == null || line2.isNotBlank()) { "address line 2 is absent or not blank" }
        require(country == null || country.isNotBlank()) { "the country is absent or not blank" }
    }
}

data class LegalEntity(
    val id: LegalEntityId,
    val name: String,
    val identifiers: List<Identifier>,
    val address: Address,
) {
    init {
        require(name.isNotBlank()) { "a legal entity name must not be blank" }
    }
}
