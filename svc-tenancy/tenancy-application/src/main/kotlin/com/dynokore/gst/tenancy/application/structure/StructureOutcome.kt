package com.dynokore.gst.tenancy.application.structure

import com.dynokore.gst.tenancy.domain.CodeRefusal

/** The outcome of a structure command; a refusal is a business outcome, returned and never thrown (CS-12). */
sealed interface StructureOutcome<out T> {
    /** [repeated] is true when the request repeated an earlier one and nothing was written. */
    data class Done<T>(
        val value: T,
        val repeated: Boolean = false,
    ) : StructureOutcome<T>

    /** The node or its parent does not exist in the caller's tenant scope. */
    data object NotFound : StructureOutcome<Nothing>

    /** The request conflicts with the tenant's state: a code in use, or a code fixed by an aggregate number. */
    data class Conflict(
        val reason: CodeRefusal,
    ) : StructureOutcome<Nothing>

    /** The short code is not 1 to 10 characters of A-Z and 0-9, or was not entered. */
    data class Invalid(
        val reason: CodeRefusal,
    ) : StructureOutcome<Nothing>
}
