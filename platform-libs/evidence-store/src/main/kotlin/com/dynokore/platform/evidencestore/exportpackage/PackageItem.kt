package com.dynokore.platform.evidencestore.exportpackage

import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.tenancykernel.TenantId

/**
 * One file of a package: its content class, a plain file name and its bytes. The name is a single relative
 * path segment (OWASP ASVS V12: fail closed on path traversal), so it cannot carry a separator, a percent
 * sign (BagIt percent-encodes it, RFC 8493 2.1.3), a space or a leading dot. The 128-character limit is
 * part of the BagIt profile of this package and is not tunable per tenant.
 */
class PackageItem(
    val contentClass: PackageContentClass,
    val name: String,
    bytes: ByteArray,
) {
    private val content: ByteArray = bytes.copyOf()

    init {
        require(NAME.matches(name)) {
            "PackageItem name must match ${NAME.pattern}, was '$name' (class $contentClass)"
        }
    }

    /** A copy of the item bytes; the caller cannot change the item through it. */
    fun bytes(): ByteArray = content.copyOf()

    private companion object {
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

/** What a package covers: one entity and period (an audit pack) or a whole tenant (offboarding). */
sealed interface PackageScope {
    data class EntityPeriod(
        val tenant: TenantId,
        val entity: EntityRef,
        val period: PeriodRef,
    ) : PackageScope

    data class WholeTenant(
        val tenant: TenantId,
    ) : PackageScope
}
