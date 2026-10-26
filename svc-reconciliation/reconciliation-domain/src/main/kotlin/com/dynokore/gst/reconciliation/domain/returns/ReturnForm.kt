package com.dynokore.gst.reconciliation.domain.returns

/**
 * The return forms of `UploadGstReturnRequest.formType` (INV-Q-6109): [value] is the member name and the contract
 * value.
 */
enum class ReturnForm {
    F5,
    F7,
    F8,
    ;

    val value: String get() = name
}
