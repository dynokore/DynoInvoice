package com.dynokore.platform.serviceruntime.identity

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Test double of the two flow-context ports (INV-T-9254): [FakeFlowContextKeys] and [FakeFlowContextIssuer] sign and
 * verify HS256 JWS compact forms (RFC 7515) with fixed test keys. A test-only fixture: never wire it in a service.
 */
class FakeFlowContextKeys(
    private val keys: Map<String, ByteArray> = mapOf(FlowContextTestSupport.KEY_ID to FlowContextTestSupport.SECRET),
) : FlowContextKeys {
    override fun verify(compact: String): ByteArray? {
        val parts = compact.split('.')
        if (parts.size != 3) return null
        return try {
            val header = String(DECODER.decode(parts[0]), Charsets.UTF_8)
            val secret = keys.entries.firstOrNull { header == headerJson(it.key) }?.value ?: return null
            val expected = hmac(secret, "${parts[0]}.${parts[1]}")
            if (MessageDigest.isEqual(expected, DECODER.decode(parts[2]))) DECODER.decode(parts[1]) else null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    internal companion object {
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()

        fun headerJson(kid: String): String = "{\"alg\":\"HS256\",\"kid\":\"$kid\"}"

        fun hmac(
            secret: ByteArray,
            input: String,
        ): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(secret, "HmacSHA256"))
                doFinal(input.toByteArray(Charsets.UTF_8))
            }
    }
}

class FakeFlowContextIssuer(
    private val kid: String = FlowContextTestSupport.KEY_ID,
    private val secret: ByteArray = FlowContextTestSupport.SECRET,
) : FlowContextIssuer {
    override fun sign(payload: ByteArray): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val signingInput =
            encoder.encodeToString(FakeFlowContextKeys.headerJson(kid).toByteArray(Charsets.UTF_8)) + "." +
                encoder.encodeToString(payload)
        return signingInput + "." + encoder.encodeToString(FakeFlowContextKeys.hmac(secret, signingInput))
    }
}

object FlowContextTestSupport {
    const val KEY_ID = "fake-1"
    val SECRET: ByteArray = "flow-context-fake-key-0123456789".toByteArray(Charsets.UTF_8)

    /** The instant the contexts of [contextFor] are issued at; [CLOCK] stands still there. */
    val NOW: Instant = Instant.parse("2026-11-14T00:00:00Z")
    val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    fun keys(): FlowContextKeys = FakeFlowContextKeys()

    fun issuer(): FlowContextIssuer = FakeFlowContextIssuer()

    /** An issuer whose key is not in [keys]: its contexts must not verify. */
    fun outsideIssuer(): FlowContextIssuer =
        FakeFlowContextIssuer("outside", "another-key-0123456789abcdefghij".toByteArray(Charsets.UTF_8))

    /**
     * A header value of `X-Flow-Context`, signed by the fake keys, valid from [issuedAt] to [expiresAt] (epoch
     * seconds; by default [NOW] for 300 seconds).
     */
    @Suppress("LongParameterList")
    fun contextFor(
        tenant: String,
        entity: FlowEntity,
        principalKind: String,
        callerService: String,
        flowId: String = "flow-1",
        issuedAt: Long = NOW.epochSecond,
        expiresAt: Long = issuedAt + 300,
        issuer: FlowContextIssuer = issuer(),
    ): String {
        val ref = entity.ref?.let { ",\"ref\":\"$it\"" }.orEmpty()
        val payload =
            "{\"tenant\":\"$tenant\",\"entity\":{\"kind\":\"${entity.kind}\"$ref}," +
                "\"principalKind\":\"$principalKind\"," +
                "\"callerService\":\"$callerService\",\"flowId\":\"$flowId\"," +
                "\"iat\":$issuedAt,\"exp\":$expiresAt}"
        return issuer.sign(payload.toByteArray(Charsets.UTF_8))
    }
}
