package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged
import com.dynokore.platform.tenancykernel.PostgresProbeFixture
import com.dynokore.platform.tenancykernel.ProbeRow
import com.dynokore.platform.tenancykernel.ProbeTable
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * A valid tenant-scoped operator session (owner ruling INV-Q-4119) binds its tenant and `sub` like any tenant
 * principal (INV-T-3981's binding), so it reads only that tenant's rows under RLS, on PostgreSQL 17 and 18.
 */
@Tag("integration")
class OperatorSessionTenantRowsIT {
    private val tenantA = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val tenantB = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a valid operator session reads only its tenant's rows`(image: String) {
        PostgresProbeFixture(image).use { fixture ->
            fixture.createProbeTable(fixture.ownerDataSource())
            val tenants = TenantContextImpl()
            val tx = TransactionRunnerImpl(fixture.appDataSource(), tenants)
            for ((tenant, value) in listOf(tenantA to "a-row", tenantB to "b-row")) {
                tenants.bind(tenant, "seed") {
                    tx.inTransaction { ProbeTable.insert(tx.require(), ProbeRow(UUID.randomUUID(), tenant, value)) }
                }
            }

            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val replica = AuthorityReplica(Clock.systemUTC()).also { it.onSnapshotComplete(0) }
            val now = Instant.now()
            val key =
                OperatorSessionKey(
                    "k1",
                    "RS256",
                    Base64.getEncoder().encodeToString(keys.public.encoded),
                    now.minusSeconds(60),
                    now.plusSeconds(3600),
                )
            replica.operatorSessionKeys.accept(OperatorSessionKeysChanged(listOf(key)))
            val idpNeverAsked = JwtDecoder { error("an operator session is never verified by the identity provider") }
            val resolver = JwtEndUserPrincipalResolver(idpNeverAsked, replica, Clock.systemUTC())

            val claims =
                JWTClaimsSet
                    .Builder()
                    .subject("op-1")
                    .claim(JwtEndUserPrincipalResolver.TENANT_CLAIM, tenantA.toString())
                    .claim("act", mapOf("role" to "platform.support", "case" to "CASE-5521"))
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(600)))
                    .build()
            val jwt =
                SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims).apply {
                    sign(RSASSASigner(keys.private))
                }
            val principal = checkNotNull(resolver.resolve("Bearer " + jwt.serialize()))

            val read =
                tenants.bind(checkNotNull(principal.tenant), principal.actor) {
                    tx.inTransaction { ProbeTable.selectAll(tx.require()) }
                }
            read.map { it.value } shouldBe listOf("a-row")
            read.map { it.tenantId }.toSet() shouldBe setOf(tenantA)
        }
    }
}
