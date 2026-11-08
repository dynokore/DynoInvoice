package com.dynokore.gst.masterdata.adapters.http

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.constraints.Size
import java.net.URI

/**
 * `@Size` on a `java.net.URI`: the generated contract models put `maxLength` of a `format: uri` property (the
 * `endpoint` of a connection record, max 512) on a URI-typed field, and Hibernate Validator ships no `@Size` validator
 * for that type, so every `createConnection` body failed bean validation. This validator measures the URI's text, so
 * the published `maxLength` is enforced as written (API-01), not dropped. Registered by `ValidationConfiguration`.
 */
class UriSizeValidator : ConstraintValidator<Size, URI> {
    private var min = 0
    private var max = Int.MAX_VALUE

    override fun initialize(constraint: Size) {
        min = constraint.min
        max = constraint.max
    }

    override fun isValid(
        value: URI?,
        context: ConstraintValidatorContext?,
    ): Boolean = value == null || value.toString().length in min..max
}
