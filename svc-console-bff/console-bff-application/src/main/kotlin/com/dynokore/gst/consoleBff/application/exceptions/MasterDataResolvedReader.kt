package com.dynokore.gst.consoleBff.application.exceptions

/**
 * Reads the MasterDataResolved payload (INV-AC-21062). Returns the distinct listed `docUuids` in first-seen order;
 * empty when the member is absent or JSON null. A malformed payload is a `ContractViolation`.
 */
interface MasterDataResolvedReader {
    fun read(dataJson: String): List<String>
}
