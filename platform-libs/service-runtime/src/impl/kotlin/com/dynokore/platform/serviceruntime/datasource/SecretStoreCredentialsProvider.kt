package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.zaxxer.hikari.HikariCredentialsProvider
import com.zaxxer.hikari.util.Credentials

/**
 * A database role's credentials, read through [SecretStore] for every new physical connection (STK-29, INV-REQ-1231;
 * coordinator ruling INV-Q-3909, INV-T-3913).
 *
 * HikariCP (STK-17) calls [getCredentials] each time a pool opens a physical connection. It reads [passwordRef]
 * through [secrets] on every call and never caches the value, so each new connection authenticates with the password
 * mounted at that moment: a rotated password (STK-29 "rotation by file watch without restart") takes effect without a
 * restart. Connections already open keep their session until the pool retires them (`maxLifetime`, or
 * `softEvictConnections`).
 *
 A pool sets it as its HikariConfig `credentialsProvider`, with no `username` and never reading the password into
 * `password`:
 * ```
 * HikariConfig().also {
 *     it.jdbcUrl = url
 *     it.credentialsProvider = SecretStoreCredentialsProvider(secrets, "submission_runtime", SecretRef("db/submission_runtime"))
 * }
 * ```
 * Spring Boot's application pool takes one from `service-runtime.datasource.credential`, and Flyway from
 * `service-runtime.flyway.credential` (README "Database credentials").
 *
 * CS-24: the raw password is revealed only inside [getCredentials], to hand it to the driver. [toString] names the
 * role and the reference only.
 */
class SecretStoreCredentialsProvider(
    private val secrets: SecretStore,
    val username: String,
    val passwordRef: SecretRef,
) : HikariCredentialsProvider {
    init {
        require(username.isNotBlank()) { "the database username must not be blank (passwordRef '$passwordRef')" }
    }

    override fun getCredentials(): Credentials = Credentials.of(username, secrets.read(passwordRef).reveal())

    override fun toString(): String = "SecretStoreCredentialsProvider(username=$username, passwordRef=$passwordRef)"
}
