package com.dynokore.platform.partyregistry

/** Outcome of a scheme format check. */
sealed interface FormatVerdict {
    data object Valid : FormatVerdict

    data class Invalid(
        val reason: String,
    ) : FormatVerdict
}

/**
 * The injectable hook that validates an identifier's format for a scheme (UEN, GSTN, Peppol participant ID). The
 * module fixes no format itself: the owner of the format supplies the implementation (INV-REQ-314 point 4).
 */
fun interface SchemeValidator {
    fun validate(
        scheme: IdentifierScheme,
        value: String,
    ): FormatVerdict
}

/** A counterparty field that is either a held value or the distinct `NA` marker (INV-REQ-313). */
sealed interface PartyField {
    data class Value(
        val text: String,
    ) : PartyField {
        init {
            require(text.isNotBlank()) { "a held value must not be blank" }
            require(text != NA_LITERAL) { "the literal $NA_LITERAL is NotApplicable, not a value" }
        }
    }

    data object NotApplicable : PartyField

    companion object {
        const val NA_LITERAL = "NA"

        /** Exactly `NA` is the marker; any other text, including `na`, is a value. Blank is not a field. */
        fun of(text: String): PartyField = if (text == NA_LITERAL) NotApplicable else Value(text)
    }
}

const val MDM_004 = "MDM-004"

/** Verdict of the NA rule over Customer UEN, name, address line 1 and postal code (INV-NOTE-3#def:C-7). */
sealed interface NaVerdict {
    /** All four are `NA` (scheme `NONE`) or none is. */
    data class Accepted(
        val allNa: Boolean,
    ) : NaVerdict

    /** Partial `NA` population: blocks with [errorCode] = MDM-004. */
    data class PartialNa(
        val naFields: Set<String>,
        val valueFields: Set<String>,
    ) : NaVerdict {
        val errorCode: String = MDM_004
    }
}

/** `NA` is accepted only as a whole (UEN, name, address line 1 and postal code all `NA`); partial is MDM-004. */
fun checkNaPopulation(
    uen: PartyField,
    name: PartyField,
    addressLine1: PartyField,
    postalCode: PartyField,
): NaVerdict {
    val fields = linkedMapOf("uen" to uen, "name" to name, "addressLine1" to addressLine1, "postalCode" to postalCode)
    val (na, held) = fields.entries.partition { it.value == PartyField.NotApplicable }
    return if (na.isEmpty() || held.isEmpty()) {
        NaVerdict.Accepted(allNa = held.isEmpty())
    } else {
        NaVerdict.PartialNa(na.map { it.key }.toSet(), held.map { it.key }.toSet())
    }
}
