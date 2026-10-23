package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.GatewayAuthentication
import com.dynokore.gst.access.adapters.http.GatewayCaller
import com.dynokore.gst.access.adapters.http.GrantManagerCheck
import com.dynokore.gst.access.adapters.http.PrincipalOperations
import com.dynokore.gst.access.adapters.http.PrincipalsController
import com.dynokore.gst.access.contract.model.AuthenticatePrincipalRequest
import com.dynokore.gst.access.contract.model.AuthenticatePrincipalResponse
import com.dynokore.gst.access.contract.model.CreatePrincipalRequest
import com.dynokore.gst.access.contract.model.CreatePrincipalRequestPublicKeyJwk
import com.dynokore.gst.access.contract.model.PrincipalResponse
import com.dynokore.gst.access.contract.model.RotatePrincipalRequest
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID

/**
 * What the principal acceptance tests share (INV-T-4937): a fresh tenant with one tenant.admin, the real controllers
 * called under a bound tenant, a gateway-side controller (the production one verifies a workload token, which a
 * direct call does not carry), and ES256 client assertions signed on the test clock.
 */
class PrincipalKit(
    val context: AssertableWebApplicationContext,
    val tenant: TenantId = TenantId(UUID.randomUUID()),
    val admin: String = "user:tenant-admin",
    grantDays: Long = 30,
) {
    val tenants: TenantContext = context.getBean(TenantContext::class.java)
    val clock: MovableClock = context.getBean(Clock::class.java) as MovableClock
    val principals: PrincipalsController = context.getBean(PrincipalsController::class.java)
    val authorise: AuthoriseController = context.getBean(AuthoriseController::class.java)
    private val mapper = JsonMapper.builder().build()

    /** The controller the API gateway calls: the same use cases with the gateway check answered true. */
    val gateway: PrincipalsController =
        PrincipalsController(
            context.getBean(PrincipalOperations::class.java),
            context.getBean(GatewayAuthentication::class.java),
            tenants,
            context.getBean(GrantManagerCheck::class.java),
            GatewayCaller { true },
            mapper,
        )

    init {
        Seeds.grant(
            context,
            tenant,
            SeedSpec(admin, Role.TENANT_ADMIN, window = {
                ValidityWindow(it.minus(Duration.ofDays(1)), it.plus(Duration.ofDays(grantDays)))
            }),
        )
    }

    fun <T> asAdmin(block: () -> T): T = tenants.bind(tenant, admin) { block() }

    fun entity(ref: String) {
        context
            .getBean(StructureReplica::class.java)
            .accept(OrganisationStructureChanged(tenant, ref, StructureChange.CREATED, BoundaryFixture.entityNode()))
    }

    fun create(
        type: String,
        scope: String = "tenant:$tenant",
        method: CreatePrincipalRequest.CredentialMethod? = null,
        key: ECKey? = null,
        thumbprint: String? = null,
    ): PrincipalResponse =
        asAdmin {
            principals
                .createPrincipal(
                    UUID.randomUUID(),
                    CreatePrincipalRequest(
                        CreatePrincipalRequest.PrincipalType.valueOf(type),
                        scope,
                        "orchestrator".takeIf { type == "FILING_WORKFLOW" },
                        method,
                        key?.let(::jwkOf),
                        thumbprint,
                    ),
                ).body!!
        }

    fun rotate(
        ref: String,
        overlapMinutes: Int,
        method: RotatePrincipalRequest.CredentialMethod? = null,
        key: ECKey? = null,
        thumbprint: String? = null,
    ): PrincipalResponse =
        asAdmin {
            principals
                .rotatePrincipal(
                    ref,
                    UUID.randomUUID(),
                    RotatePrincipalRequest(
                        overlapMinutes,
                        method,
                        key?.let(::jwkOf),
                        thumbprint,
                    ),
                ).body!!
        }

    fun withdraw(
        ref: String,
        credentialRef: String,
    ): PrincipalResponse = asAdmin { principals.withdrawCredential(ref, credentialRef, UUID.randomUUID()).body!! }

    fun revoke(ref: String): PrincipalResponse = asAdmin { principals.revokePrincipal(ref, UUID.randomUUID()).body!! }

    fun authenticate(
        ref: String,
        method: AuthenticatePrincipalRequest.Method,
        proof: String,
    ): AuthenticatePrincipalResponse =
        gateway.authenticatePrincipal(AuthenticatePrincipalRequest(ref, method, proof)).body!!

    fun decide(
        ref: String,
        permission: String,
        scope: String,
    ): String =
        asAdmin {
            authorise
                .authorise(ref, permission, scope)
                .body!!
                .decision.name
        }

    fun newKey(kid: String): ECKey = ECKeyGenerator(Curve.P_256).keyID(kid).generate()

    /** A client assertion of [principalRef] signed by [key], valid for [validFor] from the test clock's now. */
    fun proof(
        key: ECKey,
        principalRef: String,
        validFor: Duration = Duration.ofHours(3),
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(principalRef)
                .issuer(principalRef)
                .expirationTime(Date.from(clock.instant().plus(validFor)))
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).build(), claims)
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    fun principalCount(): Int =
        asAdmin {
            context.getBean(TransactionRunner::class.java).inTransaction {
                context.getBean(DSLContext::class.java).fetchCount(DSL.table(DSL.name("access", "service_principal")))
            }
        }

    private fun jwkOf(key: ECKey): CreatePrincipalRequestPublicKeyJwk {
        val pub = key.toPublicJWK()
        return CreatePrincipalRequestPublicKeyJwk(
            kty = pub.keyType.value,
            kid = pub.keyID,
            crv = pub.curve.name,
            x = pub.x.toString(),
            y = pub.y.toString(),
        )
    }

    companion object {
        val JWT = CreatePrincipalRequest.CredentialMethod.PRIVATE_KEY_JWT
        val SECRET = CreatePrincipalRequest.CredentialMethod.CLIENT_SECRET_BASIC
        val MTLS = CreatePrincipalRequest.CredentialMethod.MTLS
        val ROTATE_JWT = RotatePrincipalRequest.CredentialMethod.PRIVATE_KEY_JWT
        val ROTATE_SECRET = RotatePrincipalRequest.CredentialMethod.CLIENT_SECRET_BASIC
        val AUTH_JWT = AuthenticatePrincipalRequest.Method.PRIVATE_KEY_JWT
        val AUTH_SECRET = AuthenticatePrincipalRequest.Method.CLIENT_SECRET_BASIC
        val AUTH_MTLS = AuthenticatePrincipalRequest.Method.MTLS

        fun run(block: (PrincipalKit) -> Unit) {
            BoundaryFixture.runner().run { context ->
                context.startupFailure?.let { throw it }
                block(PrincipalKit(context))
            }
        }

        /** The status of the refusal an operation throws, or null when it did not throw. */
        fun refusal(block: () -> Any?): String? = runCatching { block() }.exceptionOrNull()?.message
    }
}
