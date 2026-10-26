package com.dynokore.gst.access.bootstrap

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.GatewayCaller
import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.authorise.AuthorisePlatform
import com.dynokore.gst.access.application.authorise.MachineAuthorise
import com.dynokore.gst.access.application.keys.RevocationList
import com.dynokore.gst.access.application.keys.VerificationKeys
import com.dynokore.gst.access.contract.model.IssueAccessTokenRequest
import com.dynokore.gst.access.contract.model.IssueAccessTokenResponse
import com.dynokore.gst.access.contract.model.VerificationKeysResponseKeysInner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/** What the token acceptance tests share (INV-T-8850): the issuing call, the published keys and a verifier. */
class TokenKit(
    val kit: PrincipalKit,
    val mounted: Path,
    val calls: SignerCalls,
) {
    private val context = kit.context

    /** The gateway-side controller of getVerificationKeys and getRevocationList (the direct call carries no token). */
    val keys: AuthoriseController =
        AuthoriseController(
            context.getBean(Authorise::class.java),
            context.getBean(AuthorisePlatform::class.java),
            context.getBean(MachineAuthorise::class.java),
            context.getBean(VerificationKeys::class.java),
            context.getBean(RevocationList::class.java),
            GatewayCaller { true },
        )

    private val appender =
        ListAppender<ILoggingEvent>().also {
            it.start()
            (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(it)
        }

    /** Every captured log line with its throwable text, as it would be printed. */
    fun logLines(): List<String> =
        appender.list.map { event ->
            val chain = generateSequence(event.throwableProxy) { it.cause }.joinToString { it.message.orEmpty() }
            event.formattedMessage + " " + chain
        }

    fun close() {
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(appender)
    }

    fun issue(
        ref: String,
        proof: String,
    ): IssueAccessTokenResponse =
        kit.gateway
            .issueAccessToken(IssueAccessTokenRequest(ref, IssueAccessTokenRequest.Method.PRIVATE_KEY_JWT, proof))
            .body!!

    fun published(): List<VerificationKeysResponseKeysInner> = keys.getVerificationKeys().body!!.propertyKeys

    fun replaceKey(key: ECKey) = TestSecrets.replaceSigningKey(mounted, key)

    fun removeKey() = Files.delete(mounted.resolve("access/token-signing-key"))

    /** The findings of the five checks of INV-Q-4081 A and the signature; empty when the token verifies at [now]. */
    fun verify(
        token: String,
        published: List<VerificationKeysResponseKeysInner>,
        now: Instant,
        useKey: ByteArray? = null,
    ): List<String> {
        val jwt = runCatching { SignedJWT.parse(token) }.getOrElse { return listOf("parse") }
        val found = mutableListOf<String>()
        if (jwt.header.type?.type != "at+jwt") found += "typ"
        val key =
            published.firstOrNull { it.kid == jwt.header.keyID && it.alg == jwt.header.algorithm.name }?.publicKey
        val der = useKey ?: key
        if (der == null) {
            found += "key"
        } else {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as ECPublicKey
            if (!jwt.verify(ECDSAVerifier(pub))) found += "signature"
        }
        val claims = jwt.jwtClaimsSet
        if (claims.issuer != TestSecrets.TOKEN_ISSUER) found += "iss"
        if (TestSecrets.TOKEN_AUDIENCE !in claims.audience) found += "aud"
        if (claims.expirationTime == null || !now.isBefore(claims.expirationTime.toInstant())) found += "exp"
        if (claims.issueTime == null) found += "iat"
        return found
    }

    /** True when any text holds one of the [needles]. */
    fun leaks(
        texts: List<String>,
        needles: List<String>,
    ): Boolean = texts.any { text -> needles.any { text.contains(it) } }

    companion object {
        /** The encodings of a private key that must appear in no response and no log line. */
        fun needlesOf(key: ECKey): List<String> {
            val pkcs8 = key.toECPrivateKey().encoded
            return listOf(
                Base64.getEncoder().encodeToString(pkcs8),
                Base64.getUrlEncoder().withoutPadding().encodeToString(pkcs8),
                key.d.toString(),
            )
        }

        /** Both halves of a published key must decode as a public key and not as PKCS#8. */
        fun isPublicOnly(der: ByteArray): Boolean {
            val decodes = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) }.isSuccess
            val pkcs8 =
                runCatching {
                    KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
                }.isSuccess
            return decodes && !pkcs8
        }
    }
}
