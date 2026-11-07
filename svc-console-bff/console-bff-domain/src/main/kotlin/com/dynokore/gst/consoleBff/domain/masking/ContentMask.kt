package com.dynokore.gst.consoleBff.domain.masking

/** Class of a document-view field; the class alone decides masking, never the field name. */
enum class FieldClass { IDENTIFIER, STATE, TRACE, AMOUNT, NAME, ADDRESS }

data class ViewField(
    val name: String,
    val fieldClass: FieldClass,
    val value: String?,
)

/** The one fixed text shown instead of a content value; independent of the input length and digits. */
const val MASKED_VALUE: String = "***"

/**
 * Masks the content fields (AMOUNT, NAME, ADDRESS) of a document view unless [contentAllowed].
 *
 * [contentAllowed] is the per-request decision on the permission key `doc.view.content`, already taken by
 * the caller, which passes false when the decision is not allow or is unavailable (deny by default).
 * Null and empty values are masked too, so absence is not leaked. The input list is not modified.
 */
fun maskView(
    fields: List<ViewField>,
    contentAllowed: Boolean,
): List<ViewField> {
    if (contentAllowed) return fields.toList()
    return fields.map { field ->
        when (field.fieldClass) {
            FieldClass.AMOUNT, FieldClass.NAME, FieldClass.ADDRESS -> field.copy(value = MASKED_VALUE)
            FieldClass.IDENTIFIER, FieldClass.STATE, FieldClass.TRACE -> field
        }
    }
}
