package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import java.time.ZoneOffset

/** Reading the request fields of grantRole and of the landing operations; an unreadable one is SRT-001. */
internal object GrantRequests {
    fun scopeOf(
        text: String,
        tenant: TenantRef,
    ): Scope? =
        when (val parsed = ScopeParser.parse(text, tenant)) {
            is ScopeParser.Parsed.Ok -> parsed.scope
            ScopeParser.Parsed.Missing -> null
            is ScopeParser.Parsed.Unsupported -> invalid("scope is not supported")
        }

    fun identityOf(text: String): IdentityRef =
        runCatching { IdentityRef(text) }.getOrElse { invalid("identityRef is invalid") }

    fun windowOf(request: GrantRequest): ValidityWindow =
        runCatching {
            ValidityWindow(
                request.validFrom.atStartOfDay(ZoneOffset.UTC).toInstant(),
                request.validTo?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
            )
        }.getOrElse { invalid("validTo must be after validFrom") }

    fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)
}
