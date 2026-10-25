package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date

/** Operator-session and machine-access keys are two sets, a key in exactly one (INV-Q-9028 A; INV-AC-9147). */
class KeySetIsolationTest {
    private val operatorPair = rsa()
    private val machinePair = rsa()
    private val now = Instant.now()
    private val replica = AuthorityReplica(Clock.systemUTC())
    private val resolver =
        JwtEndUserPrincipalResolver(
            NimbusJwtDecoder.withPublicKey(operatorPair.public as RSAPublicKey).build(),
            replica,
            Clock.systemUTC(),
        )

    private fun item(
        pair: KeyPair,
        kid: String,
        use: String?,
    ) = OperatorSessionKey(
        kid,
        "RS256",
        Base64.getEncoder().encodeToString(pair.public.encoded),
        now.minusSeconds(60),
        now.plusSeconds(3600),
        use,
    )

    private fun publish(vararg keys: OperatorSessionKey) =
        replica.keySets.accept(OperatorSessionKeysChanged(keys.toList()))

    private fun token(
        pair: KeyPair,
        kid: String,
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject("op-1")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .claim("tenant_id", "00000000-0000-0000-0000-00000000000a")
                .claim("act", mapOf("role" to "platform.support", "case" to "CASE-1"))
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims)
        jwt.sign(RSASSASigner(pair.private))
        return "Bearer " + jwt.serialize()
    }

    @Test
    fun `use selects the set by exact string, absent is an operator-session key`() {
        publish(
            item(operatorPair, "a", null),
            item(operatorPair, "b", "OPERATOR_SESSION"),
            item(machinePair, "m", "MACHINE_ACCESS"),
        )
        listOf("a", "b").forEach {
            (replica.operatorSessionKeys.valid(it) != null) shouldBe true
            replica.machineAccessKeys.valid(it) shouldBe null
        }
        (replica.machineAccessKeys.valid("m") != null) shouldBe true
        replica.operatorSessionKeys.valid("m") shouldBe null
        replica.keySets.droppedItems shouldBe 0
    }

    @Test
    fun `any other use drops only its item, counts it and keeps the others`() {
        val lookAlikes =
            listOf(
                "machine-access",
                "operator-session",
                "Machine_Access",
                "machine_access",
                "MACHINE-ACCESS",
                " MACHINE_ACCESS",
                "MACHINE_ACCESS ",
                "",
            )
        lookAlikes.forEachIndexed { i, use -> publish(item(operatorPair, "kept", null), item(machinePair, "x$i", use)) }
        replica.keySets.droppedItems shouldBe lookAlikes.size
        (replica.operatorSessionKeys.valid("kept") != null) shouldBe true
        lookAlikes.indices.forEach {
            replica.operatorSessionKeys.valid("x$it") shouldBe null
            replica.machineAccessKeys.valid("x$it") shouldBe null
        }
    }

    @Test
    fun `a kid under both uses drops both items and counts a refused publication, other kids stay`() {
        publish(
            item(operatorPair, "dup", null),
            item(machinePair, "dup", "MACHINE_ACCESS"),
            item(operatorPair, "ok", null),
        )
        replica.operatorSessionKeys.valid("dup") shouldBe null
        replica.machineAccessKeys.valid("dup") shouldBe null
        (replica.operatorSessionKeys.valid("ok") != null) shouldBe true
        replica.keySets.refusedPublications shouldBe 1
        replica.keySets.droppedItems shouldBe 2
    }

    @Test
    fun `the same kid published under both uses verifies no token`() {
        publish(item(operatorPair, "dup", "OPERATOR_SESSION"), item(operatorPair, "dup", "MACHINE_ACCESS"))
        resolver.resolve(token(operatorPair, "dup")) shouldBe null
        replica.machineAccessKeys.valid("dup") shouldBe null
    }

    @Test
    fun `the machine key's kid in an operator-session token header is refused`() {
        publish(item(operatorPair, "op", null), item(machinePair, "mk", "MACHINE_ACCESS"))
        resolver.resolve(token(machinePair, "mk")) shouldBe null
        (resolver.resolve(token(operatorPair, "op")) != null) shouldBe true
    }

    @Test
    fun `the operator key's kid in a machine token header is not accepted by the machine set`() {
        publish(item(operatorPair, "op", null), item(machinePair, "mk", "MACHINE_ACCESS"))
        replica.machineAccessKeys.valid("op") shouldBe null
    }

    @Test
    fun `both sets are replaced whole by the next event, no machine key means an empty machine set`() {
        replica.machineAccessKeys.valid("mk") shouldBe null
        publish(item(machinePair, "mk", "MACHINE_ACCESS"), item(operatorPair, "op", null))
        (replica.machineAccessKeys.valid("mk") != null) shouldBe true
        publish(item(operatorPair, "op", null))
        replica.machineAccessKeys.valid("mk") shouldBe null
        publish()
        replica.operatorSessionKeys.valid("op") shouldBe null
    }

    private companion object {
        fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
