package com.dynokore.gst.masterdata.application.permission

/**
 * The scoped permission keys of the tenant-scoped operations of this task (INV-Q-17215 A; the keys are in the
 * permission catalogue of platform-contracts, INV-T-17222). An operation checks its key through [TenantPermission],
 * never a role name (INV-Q-10027).
 */
object MasterdataPermissions {
    /** `runGapReport` and `getGapReport`. */
    const val GAP_REPORT_VIEW = "gap-report.view"

    /** `exportGapReport`. */
    const val GAP_REPORT_EXPORT = "gap-report.export"

    /** `dryRunCounterpartyImport`, `getCounterpartyImportDryRun`, `importCounterparties` and `getImportRecord`. */
    const val COUNTERPARTY_IMPORT_RUN = "counterparty-import.run"

    /** `bulkResolveCounterparty`. */
    const val MASTERDATA_EXCEPTION_RESOLVE = "masterdata-exception.resolve"
}

/**
 * Whether [person] holds the permission [permissionKey] at TENANT scope for the bound tenant (the operations name no
 * entity). Decided per request; fail closed.
 */
fun interface TenantPermission {
    fun holds(
        person: String,
        permissionKey: String,
    ): Boolean
}
