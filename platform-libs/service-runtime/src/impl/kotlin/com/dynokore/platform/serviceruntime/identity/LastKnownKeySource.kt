package com.dynokore.platform.serviceruntime.identity

import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The issuer's keys with the last ones obtained kept (coordinator ruling INV-Q-3773, as the gateway does,
 * INV-REQ-1204 b6). A key the [remote] source returns is remembered; when the remote cannot be reached, the
 * remembered keys are offered instead, and only when none of them matches does the [KeySourceException] stand,
 * which the decoder reports as an unavailable issuer (503 SRT-011), never as a bad token.
 */
class LastKnownKeySource(private val remote: JWKSource<SecurityContext>) : JWKSource<SecurityContext> {
    private val known = ConcurrentHashMap<String, JWK>()

    override fun get(
        selector: JWKSelector,
        context: SecurityContext?,
    ): List<JWK> = try {
        remote.get(selector, context).also { keys -> keys.forEach { known[identity(it)] = it } }
    } catch (unreachable: KeySourceException) {
        selector.select(JWKSet(known.values.toList())).ifEmpty { throw unreachable }
    }

    private fun identity(key: JWK): String = key.keyID ?: key.computeThumbprint().toString()
}
