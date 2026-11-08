package com.dynokore.gst.masterdata.adapters.connection

/**
 * The environment this installation runs in, read once per request from the service's own deployment configuration
 * (`masterdata.installation-environment`, environment variable `MASTERDATA_INSTALLATION_ENVIRONMENT`; 12-factor III,
 * OWASP ASVS V4, CWE-807). It is never a request property: a workspace caller cannot claim PRODUCTION.
 *
 * [text] is the configured text unchanged, or the empty text when nothing is configured; the use case decides whether
 * it is exactly PRODUCTION or SANDBOX (`UnknownEnvironment` otherwise), so the service starts and fails closed per
 * request.
 */
fun interface InstallationEnvironment {
    fun text(): String
}
