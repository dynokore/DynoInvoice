package com.dynokore.gst.masterdata.adapters.permission

/**
 * The scoped permission keys of the four operations wired by INV-T-18774, each the `x-operation.permission` of its
 * operation in masterdata/openapi.yaml (INV-T-18769). The keys are decided per request by `AuthorityReplica` through
 * the catalogue's operation-to-roles map (INV-Q-10027); a service never names a role. The exact text matters: the
 * replica refuses a key the map does not list (`connection.views`).
 */
object OperationPermissionKeys {
    const val EXCHANGE_RATE_ENTER = "exchange-rate.enter"
    const val EXCHANGE_RATE_IMPORT = "exchange-rate.import"
    const val CONNECTION_CONFIGURE = "connection.configure"
    const val CONNECTION_VIEW = "connection.view"
    const val CONNECTION_TEST = "connection.test"
}
