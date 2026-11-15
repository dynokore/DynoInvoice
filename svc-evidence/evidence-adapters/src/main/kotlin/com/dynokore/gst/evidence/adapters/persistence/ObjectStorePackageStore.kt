package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.application.export.ExportPackageStore
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.RetentionPeriod
import com.dynokore.platform.evidencestore.exportpackage.PackageFiles
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.ZoneOffset

/**
 * [ExportPackageStore] over the write-once [ObjectStore]: every file of the package is put under
 * `exports/offboarding/<tenantId>/<offboardingRef>/` and the manifest reference returned is `<scheme>://` plus the key
 * of `manifest-sha256.txt`. [scheme] is that of the configured object store. The objects are retained for [retention]
 * years from [clock] (the tenant remains obliged to produce records to IRAS, INV-REQ-877).
 */
class ObjectStorePackageStore(
    private val store: ObjectStore,
    private val scheme: String,
    private val clock: Clock,
    private val retention: RetentionPeriod,
) : ExportPackageStore {
    override fun store(
        tenant: TenantId,
        offboardingRef: String,
        files: PackageFiles,
    ): String {
        require(SEGMENT.matches(offboardingRef)) { "the offboarding reference is not a single safe path segment" }
        val prefix = prefix(tenant, offboardingRef)
        val retainUntil =
            clock
                .instant()
                .atZone(ZoneOffset.UTC)
                .plusYears(retention.years.toLong())
                .toInstant()
        check(MANIFEST in files.paths) { "the package has no $MANIFEST" }
        files.paths.forEach { path ->
            store.put(
                ObjectKey(prefix + path),
                checkNotNull(files.bytes(path)) { "the package lists $path without bytes" },
                retainUntil,
            )
        }
        return "$scheme://$prefix$MANIFEST"
    }

    companion object {
        const val MANIFEST = "manifest-sha256.txt"
        private val SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        /** The key prefix of one package, ending in a slash. */
        fun prefix(
            tenant: TenantId,
            offboardingRef: String,
        ): String = "exports/offboarding/${tenant.value}/$offboardingRef/"
    }
}
