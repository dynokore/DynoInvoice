package com.dynokore.gst.masterdata.domain.connection

/**
 * The closed environment tag of a connection record (INV-REQ-834, INV-REQ-1126); equal to the enum of
 * `CreateConnectionRequest.environmentTag` and `ConnectionRecord.environmentTag` in masterdata/openapi.yaml.
 * A workspace is the [SANDBOX] environment.
 */
enum class EnvironmentTag {
    PRODUCTION,
    SANDBOX,
    ;

    companion object {
        /** Exact, case-sensitive match on the member name; null for any other text (no trimming, no case folding). */
        fun parse(text: String): EnvironmentTag? = entries.firstOrNull { it.name == text }
    }
}

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
    val environmentTag: EnvironmentTag,
    val ownerService: String,
) {
    init {
        require(connectionRef.isNotBlank()) { "a connection reference is required" }
        require(externalService.isNotBlank()) { "an external service is required" }
        require(endpoint.isNotBlank()) { "an endpoint is required" }
        require(credentialRef.isNotBlank()) { "a credential reference is required" }
        require(ownerService.isNotBlank()) { "an owner service is required" }
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
