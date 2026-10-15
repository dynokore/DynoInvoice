package com.dynokore.platform.partyregistry

// The submitting identity and the supplier identity are separate types throughout (INV-REQ-803): no type below
// converts to another, so passing one where the other is required does not compile.

@JvmInline
value class SenderId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a sender id must not be blank" }
    }
}

@JvmInline
value class SupplierId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a supplier id must not be blank" }
    }
}

@JvmInline
value class SupplierGstn(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a supplier GSTN must not be blank" }
    }
}

/** The identity triple resolved per (registered entity x constitution), never from `entity.gstn` (INV-REQ-310). */
data class SupplierIdentity(
    val senderId: SenderId,
    val supplierId: SupplierId,
    val supplierGstn: SupplierGstn,
)

@JvmInline
value class ConnectionId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a connection id must not be blank" }
    }
}

@JvmInline
value class PrincipalId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a principal id must not be blank" }
    }
}

/** The connection and principal that submit; recorded separately from the supplier (INV-REQ-803). */
data class SubmittingIdentity(
    val connection: ConnectionId,
    val principal: PrincipalId,
)
