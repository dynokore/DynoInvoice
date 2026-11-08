package com.dynokore.gst.masterdata.domain.connection

/**
 * INV-REQ-834: the uniform connection record of one external service and environment. Exactly six properties: the
 * assigned [connectionRef] and the five content properties. [credentialRef] is the secret-store reference text; the
 * type has no property that can hold a secret value.
 */
data class ConnectionRecord(
    val connectionRef: String,
    val externalService: String,
    val endpoint: String,
    val credentialRef: String,
    val environmentTag: String,
    val ownerService: String,
) {
    init {
        require(connectionRef.isNotBlank()) { "a connection reference is required" }
        require(externalService.isNotBlank()) { "an external service is required" }
        require(endpoint.isNotBlank()) { "an endpoint is required" }
        require(credentialRef.isNotBlank()) { "a credential reference is required" }
        require(environmentTag.isNotBlank()) { "an environment tag is required" }
        require(ownerService.isNotBlank()) { "an owner service is required" }
    }

    companion object {
        /** The environment tag a workspace must never use the credential of (INV-REQ-1126). */
        const val PRODUCTION = "PRODUCTION"
    }
}

/** The classes of a connection test (INV-REQ-835, INV-Q-2028). */
enum class TestClass { SUCCESS, UNREACHABLE, AUTHENTICATION_REFUSED, UNEXPECTED_RESPONSE }

/** A classified test result; [detail] is a short code, never raw error text. */
data class ConnectionTestResult(
    val resultClass: TestClass,
    val detail: String? = null,
) {
    init {
        require(detail == null || DETAIL.matches(detail)) { "the detail is a code, not raw error text" }
    }

    companion object {
        val DETAIL = Regex("^[A-Z][A-Z0-9_-]{1,31}$")
    }
}
