package com.dynokore.platform.evidencestore.exportpackage

import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/** One candidate item with the tenant, entity and period it belongs to, as an adapter read it from a store. */
data class PackageCandidate(
    val tenant: TenantId,
    val entity: EntityRef,
    val period: PeriodRef,
    val item: PackageItem,
)

enum class AssemblyRefusal { FOREIGN_TENANT, DUPLICATE_ITEM }

sealed interface AssemblyResult {
    data class Assembled(
        val files: PackageFiles,
        val included: Int,
        val excluded: Int,
    ) : AssemblyResult

    data class Refused(
        val reason: AssemblyRefusal,
        val itemName: String,
    ) : AssemblyResult
}

/**
 * Chooses the items of a package by scope and composes [AuditPackWriter] (INV-REQ-988, INV-REQ-877). Least
 * privilege and data minimisation (NIST SP 800-53 AC-6): only items in scope reach the package. Tenant isolation
 * fails closed (OWASP ASVS V4): an item of another tenant refuses the whole assembly and is never dropped silently.
 * It has no clock, store or file access; the instant is an argument.
 */
object AuditPackAssembler {
    fun assemble(
        scope: PackageScope,
        candidates: List<PackageCandidate>,
        generatedAt: Instant,
    ): AssemblyResult {
        val tenant = scopeTenant(scope)
        val included = candidates.filter { inScope(scope, it) }
        val refusal = foreignTenant(tenant, candidates) ?: duplicate(included)
        if (refusal != null) return refusal
        val files = AuditPackWriter.write(scope, included.map { it.item }, generatedAt)
        return AssemblyResult.Assembled(files, included.size, candidates.size - included.size)
    }

    /** Rule 1 runs over the whole list before anything is included. */
    private fun foreignTenant(
        tenant: TenantId,
        candidates: List<PackageCandidate>,
    ): AssemblyResult.Refused? =
        candidates
            .firstOrNull { it.tenant != tenant }
            ?.let { AssemblyResult.Refused(AssemblyRefusal.FOREIGN_TENANT, it.item.name) }

    private fun duplicate(included: List<PackageCandidate>): AssemblyResult.Refused? {
        val seen = HashSet<Pair<PackageContentClass, String>>()
        return included
            .firstOrNull { !seen.add(it.item.contentClass to it.item.name) }
            ?.let { AssemblyResult.Refused(AssemblyRefusal.DUPLICATE_ITEM, it.item.name) }
    }

    private fun scopeTenant(scope: PackageScope): TenantId =
        when (scope) {
            is PackageScope.EntityPeriod -> scope.tenant
            is PackageScope.WholeTenant -> scope.tenant
        }

    private fun inScope(
        scope: PackageScope,
        candidate: PackageCandidate,
    ): Boolean =
        when (scope) {
            is PackageScope.EntityPeriod -> candidate.entity == scope.entity && candidate.period == scope.period
            is PackageScope.WholeTenant -> true
        }
}
