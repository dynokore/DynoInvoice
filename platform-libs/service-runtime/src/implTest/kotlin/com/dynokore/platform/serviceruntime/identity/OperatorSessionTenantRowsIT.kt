package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged
import com.dynokore.platform.serviceruntime.testing.TestDatabase
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancykernel.PostgresProbeFixture
import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.security.KeyPair
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
            // the runtime login's pool and a DSLContext from tenancy-jooq's factory over the probe table (TENANT)
            // the probe schema holds SignedBinding's default key
            val db =
                TestDatabase(
                    fixture.jdbcUrl(),
                    mapOf("tenancy_kernel_test.probe" to TableClass.TENANT),
                    SignedBinding.keys(),
                )
            val app = db.login(PostgresProbeFixture.APP, PostgresProbeFixture.APP_PASSWORD)
            val probe = DSL.table(DSL.name("tenancy_kernel_test", "probe"))
            val idCol = DSL.field(DSL.name("id"), SQLDataType.UUID)
            val tenantCol = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
            val valueCol = DSL.field(DSL.name("value"), SQLDataType.CLOB)
            for ((tenant, value) in listOf(tenantA to "a-row", tenantB to "b-row")) {
                app.asTenant(tenant, "seed") {
                    insertInto(
                        probe,
                    ).columns(idCol, tenantCol, valueCol).values(UUID.randomUUID(), tenant.value, value).execute()
                }
            }

            val principal = operatorPrincipal()

            val read =
                app.asTenant(checkNotNull(principal.tenant), principal.actor) {
                    select(tenantCol, valueCol).from(probe).fetch().map { it.get(tenantCol) to it.get(valueCol) }
                }
            read.map { it.second } shouldBe listOf("a-row")
            read.map { it.first }.toSet() shouldBe setOf(tenantA.value)
            db.close()
        }
    }

    private fun operatorPrincipal(): EndUserPrincipal {
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

        val jwt = operatorSessionJwt(keys, now)
        val principal = checkNotNull(resolver.resolve("Bearer " + jwt.serialize()))
        return principal
    }

    /** An operator session for op-1 on tenant A, signed with key k1 and issued at [now]. */
    private fun operatorSessionJwt(
        keys: KeyPair,
        now: Instant,
    ): SignedJWT {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject("op-1")
                .claim(JwtEndUserPrincipalResolver.TENANT_CLAIM, tenantA.toString())
                .claim("act", mapOf("role" to "platform.support", "case" to "CASE-5521"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims).apply {
            sign(RSASSASigner(keys.private))
        }
    }
}
