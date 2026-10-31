package com.dynokore.gst.rules.domain.scope

import com.dynokore.gst.rules.domain.coding.LineCoding
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.tenancykernel.TenantId

/** What a scope verdict is decided from. */
data class ScopeFacts(
    val tenant: TenantId?,
    val date: TransactionDate,
    val line: LineCoding,
)

/** The scope rules, supplied from outside; the use case judges nothing and returns the answer as is. */
interface ScopeVerdictPort {
    fun verdictOf(facts: ScopeFacts): ScopeVerdict
}
