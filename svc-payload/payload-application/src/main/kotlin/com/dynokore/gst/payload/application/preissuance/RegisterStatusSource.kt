package com.dynokore.gst.payload.application.preissuance

import java.time.LocalDate

/**
 * INV-REQ-475: what the pre-issuance check asks of the register. Every member is kept byte for byte (no trim, no case
 * change, no length rule): the register leg is not the place to validate the draft shape. Each text must not be blank.
 */
data class RegisterQuery(
    val supplierGstn: String,
    val entityRef: String,
    val currency: String,
    val invoiceDate: LocalDate,
) {
    init {
        require(supplierGstn.isNotBlank()) { "supplierGstn must not be blank" }
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(currency.isNotBlank()) { "currency must not be blank" }
    }
}

/** The answer of a register lookup: the raw status as sent, or nothing when the leg could not answer (INV-REQ-477). */
sealed interface RegisterLookup {
    /** The status string exactly as the owner sent it; an unlisted value stays as it is. */
    data class Answered(
        val status: String,
        val asOf: LocalDate?,
    ) : RegisterLookup

    /** Any failure of the call; never an exception. */
    data object Unavailable : RegisterLookup
}

/** The port the pre-issuance use case reads the register status through. */
fun interface RegisterStatusSource {
    fun lookup(query: RegisterQuery): RegisterLookup
}
