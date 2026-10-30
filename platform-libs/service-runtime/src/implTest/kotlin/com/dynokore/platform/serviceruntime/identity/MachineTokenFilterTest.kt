package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.OperationKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureKey
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

private val MT_TENANT = TenantId.of("0a000000-0000-0000-0000-00000000000a")
private val MT_OTHER_TENANT = TenantId.of("0b000000-0000-0000-0000-00000000000b")
private const val MT_ISSUER = "https://access.example/"
private const val MT_AUDIENCE = "gst-public-api"
private const val MT_LINES =
    "POST\t/v1/ingest/a\tingest\t-\n" +
        "POST\t/v1/ingest/cert\tingest\tcertification\n" +
        "GET\t/v1/reports\treporting\t-\n" +
        "GET\t/v1/metrics\tmonitoring\t-\n"
private const val MT_ENTITY_LINES = "POST\t/v1/ingest/a\tentityRef\n"

/** Verification of the machine access token, its principal and its entity set (INV-Q-9028 A, INV-Q-9040 A, INV-Q-9032 A). */
class MachineTokenFilterTest {
    private val machineKeys = rsa()
    private val operatorKeys = rsa()
    private val now = Instant.now()
    private val replica = AuthorityReplica(Clock.systemUTC())
    private val resolverCalls = AtomicInteger()
    private val handled = AtomicInteger()
    private val seen = mutableListOf<Principal?>()
    private val operations = mutableListOf<OperationKey?>()
    private val bound = mutableListOf<TenantId?>()
    private val tenantContext = TenantContextImpl()
    private val clock = Clock.systemUTC()
    private val fallbackNodes = mutableMapOf<StructureKey, FallbackResult<StructureNode>>()
    private val structure = StructureReplica(clock) { key, _ -> fallbackNodes[key] ?: FallbackResult.NotFound }

    private fun node(
        kind: StructureNodeKind = StructureNodeKind.ENTITY,
        retired: Boolean = false,
    ) = StructureNode(kind, null, retired, "n", null, emptyList(), 1)

    private fun hold(
        tenant: TenantId,
        ref: String,
        node: StructureNode,
    ) = structure.accept(OrganisationStructureChanged(tenant, ref, StructureChange.CREATED, node))

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun item(
        pair: KeyPair,
        kid: String,
        use: String?,
        alg: String = "RS256",
    ) = OperatorSessionKey(
        kid,
        alg,
        Base64.getEncoder().encodeToString(pair.public.encoded),
        now.minusSeconds(60),
        now.plusSeconds(3600),
        use,
    )

    private fun publish(vararg keys: OperatorSessionKey) =
        replica.keySets.accept(OperatorSessionKeysChanged(keys.toList()))

    private fun provider(tenants: TenantContext): ObjectProvider<TenantContext> =
        DefaultListableBeanFactory()
            .apply {
                registerSingleton(
                    "tenants",
                    tenants,
                )
            }.getBeanProvider(TenantContext::class.java)

    private fun verifier(
        authority: AuthorityReplica? = replica,
        issuer: String? = MT_ISSUER,
        audience: String? = MT_AUDIENCE,
    ) = MachineTokenVerifier(authority, MachineIdentityProperties(issuer, audience), Clock.systemUTC())

    private fun lines(text: String = MT_LINES) =
        text.removeSuffix("\n").split('\n').map {
            val p = it.split('\t')
            OperationLine(p[0], p[1], MachineOperation(p[2], p[3].takeIf { also -> also != "-" }))
        }

    private fun claims(
        scope: String = "ingest",
        block: JWTClaimsSet.Builder.() -> Unit = {},
    ): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .issuer(MT_ISSUER)
            .audience(MT_AUDIENCE)
            .subject("client-1")
            .claim("client_id", "client-1")
            .claim("tenant_id", MT_TENANT.toString())
            .claim("scope", scope)
            .claim("principal_scope", "entity:E1")
            .jwtID("jti-1")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(600)))
            .apply(block)
            .build()

    private fun token(
        claims: JWTClaimsSet = claims(),
        pair: KeyPair = machineKeys,
        kid: String? = "mk",
        alg: JWSAlgorithm = JWSAlgorithm.RS256,
        typ: JOSEObjectType? = JOSEObjectType("at+jwt"),
    ): String {
        val header =
            JWSHeader
                .Builder(alg)
                .apply { kid?.let(::keyID) }
                .type(typ)
                .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(RSASSASigner(pair.private))
        return "Bearer " + jwt.serialize()
    }

    private fun send(
        authorization: String?,
        method: String = "POST",
        path: String = "/v1/ingest/a",
        body: String = """{"entityRef":"E1"}""",
        verifier: MachineTokenVerifier = verifier(),
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest(method, path)
        request.setContent(body.toByteArray())
        authorization?.let { request.addHeader("Authorization", it) }
        val response = MockHttpServletResponse()
        val dir = Files.createTempDirectory("mt-lines")
        val tsv = dir.resolve("META-INF/gst/entity-ref-operations.tsv")
        Files.createDirectories(tsv.parent)
        Files.writeString(tsv, MT_ENTITY_LINES)
        val entityFilter =
            EntityRefScopeFilter.from(
                URLClassLoader(arrayOf(dir.toUri().toURL()), null),
                objectProviderOf(structure),
                clock,
            )
        val endUser =
            EndUserPrincipalBindingFilter(
                objectProviderOf(
                    EndUserPrincipalResolver {
                        resolverCalls.incrementAndGet()
                        null
                    },
                ),
                provider(tenantContext),
                UntouchedOperations.of(emptyList()),
            )
        val servlet =
            object : HttpServlet() {
                override fun service(
                    req: HttpServletRequest,
                    res: HttpServletResponse,
                ) {
                    handled.incrementAndGet()
                    seen += req.getAttribute(MachineTokenFilter.PRINCIPAL_ATTRIBUTE) as? Principal
                    operations += req.getAttribute(OperationKey.ATTRIBUTE) as? OperationKey
                    bound += runCatching { tenantContext.current() }.getOrNull()
                    res.status = 200
                }
            }
        val machine = MachineTokenFilter(verifier, provider(tenantContext), lines())
        MockFilterChain(servlet, machine, endUser, entityFilter).doFilter(request, response)
        return response
    }

    private fun <T : Any> objectProviderOf(bean: T): ObjectProvider<T> =
        DefaultListableBeanFactory().apply { registerSingleton("b", bean) }.getBeanProvider(bean.javaClass)

    private fun status(
        authorization: String?,
        path: String = "/v1/ingest/a",
        method: String = "POST",
    ) = send(authorization, method, path).status

    @Test
    fun `a valid token passes, never reaches the end-user resolver and yields the machine principal`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        status(token()) shouldBe 200
        resolverCalls.get() shouldBe 0
        seen.single() shouldBe Principal.machine("client-1", MT_TENANT, MachineEntityCoverage.Entity("E1"))
        seen.single()!!.kind shouldBe PrincipalKind.MACHINE
        bound.single() shouldBe MT_TENANT
    }

    @Test
    fun `a verified request carries the matched operation, the token scope and the token id`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        status(token()) shouldBe 200
        operations.single() shouldBe OperationKey("POST", "/v1/ingest/a", "ingest")
        seen.single()!!.scope shouldBe "ingest"
        seen.single()!!.tokenId shouldBe "jti-1"
    }

    @Test
    fun `a token without a jti, or with a blank one, is 401`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        status(token(claims { jwtID(null) })) shouldBe 401
        status(token(claims { jwtID(" ") })) shouldBe 401
        status(token(claims { jwtID("") })) shouldBe 401
        operations.size shouldBe 0
    }

    @Test
    fun `a refused request carries no operation`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        status("Bearer nonsense") shouldBe 401
        operations.size shouldBe 0
    }

    @Test
    fun `a PS256 token over a PS256 key passes`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS", "PS256"))
        status(token(alg = JWSAlgorithm.PS256)) shouldBe 200
    }

    @Test
    fun `an empty machine key set is 401`() {
        status(token()) shouldBe 401
        handled.get() shouldBe 0
    }

    @Test
    fun `scope reporting on an ingest line is 403`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        status(token(claims("reporting"))) shouldBe 403
        handled.get() shouldBe 0
    }

    @Test
    fun `scope is exactly one string equal to the line scope`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        status(token(claims("ingest reporting"))) shouldBe 403
        status(token(claims("ingest") { claim("scope", listOf("ingest")) })) shouldBe 401
        status(token(claims("ingest") { claim("scope", null) })) shouldBe 401
        status(token(claims("INGEST"))) shouldBe 403
    }

    @Test
    fun `a certification token in SANDBOX needs the additional principal on the line`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        val sandbox = token(claims("certification") { claim("environment", "SANDBOX") })
        status(sandbox) shouldBe 403
        status(sandbox, "/v1/ingest/cert") shouldBe 200
        status(token(claims("ingest")), "/v1/ingest/cert") shouldBe 200
        status(token(claims("ingest"))) shouldBe 200
    }

    @Test
    fun `a certification token outside SANDBOX is 403 on every line`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        listOf("PRODUCTION", "sandbox", "SANDBOX ", "", null).forEach { environment ->
            val t = token(claims("certification") { claim("environment", environment) })
            status(t, "/v1/ingest/cert") shouldBe 403
            status(t) shouldBe 403
        }
        status(token(claims("certification") { claim("environment", listOf("SANDBOX")) }), "/v1/ingest/cert") shouldBe
            403
    }

    @Test
    fun `a token signed with an operator-session key is 401`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"), item(operatorKeys, "ok", null))
        status(token(pair = operatorKeys, kid = "ok")) shouldBe 401
        status(token(pair = operatorKeys, kid = "mk")) shouldBe 401
    }

    @Test
    fun `the same kid under the other key set is 401`() {
        publish(item(operatorKeys, "same", "OPERATOR_SESSION"))
        status(token(pair = operatorKeys, kid = "same")) shouldBe 401
        publish(item(machineKeys, "same", "MACHINE_ACCESS"), item(operatorKeys, "same", "OPERATOR_SESSION"))
        status(token(kid = "same")) shouldBe 401
    }

    @Test
    fun `typ other than at+jwt is 401`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        status(token(typ = JOSEObjectType.JWT)) shouldBe 401
        status(token(typ = JOSEObjectType("AT+JWT"))) shouldBe 401
        status(token(typ = null)) shouldBe 401
    }

    @Test
    fun `issuer and audience must match`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        status(token(claims { audience("another-api") })) shouldBe 401
        status(token(claims { audience(listOf("another-api", MT_AUDIENCE)) })) shouldBe 200
        status(token(claims { issuer("https://other/") })) shouldBe 401
        status(token(claims { issuer(null) })) shouldBe 401
        send(token(), verifier = verifier(issuer = null)).status shouldBe 401
        send(token(), verifier = verifier(audience = " ")).status shouldBe 401
        send(token(), verifier = verifier(authority = null)).status shouldBe 401
    }

    @Test
    fun `lifetime, algorithm, key and identity claims are checked`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        status(token(claims { expirationTime(Date.from(now.minusSeconds(1))) })) shouldBe 401
        status(token(claims { expirationTime(null) })) shouldBe 401
        status(token(claims { issueTime(null) })) shouldBe 401
        status(token(alg = JWSAlgorithm.RS384)) shouldBe 401
        status(token(kid = null)) shouldBe 401
        status(token(kid = "unknown")) shouldBe 401
        status(token(claims { claim("client_id", "client-2") })) shouldBe 401
        status(token(claims { claim("client_id", null) })) shouldBe 401
        status(token(claims { subject(null) })) shouldBe 401
        status(token(claims { claim("tenant_id", null) })) shouldBe 401
        status(token(claims { claim("tenant_id", "not-a-uuid") })) shouldBe 401
        status(token(claims { claim("tenant_id", TenantId.PLATFORM.toString()) })) shouldBe 401
        status(token(pair = operatorKeys)) shouldBe 401
        status(null) shouldBe 401
        status("Basic abc") shouldBe 401
        status("Bearer not-a-jwt") shouldBe 401
    }

    @Test
    fun `HMAC and none are refused`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS", "HS256"))
        val hmac =
            SignedJWT(
                JWSHeader
                    .Builder(JWSAlgorithm.HS256)
                    .keyID("mk")
                    .type(JOSEObjectType("at+jwt"))
                    .build(),
                claims(),
            )
        hmac.sign(MACSigner(ByteArray(32) { 7 }))
        status("Bearer " + hmac.serialize()) shouldBe 401
        val none =
            com.nimbusds.jwt
                .PlainJWT(claims())
                .serialize()
        status("Bearer $none") shouldBe 401
    }

    @Test
    fun `a monitoring token needs no tenant`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        val t = token(claims("monitoring") { claim("tenant_id", null) })
        status(t, "/v1/metrics", "GET") shouldBe 200
        seen.single()!!.tenant shouldBe null
        bound.single() shouldBe null
    }

    @Test
    fun `a request outside the list is not a machine request`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        listOf("/v10/ingest/a", "/internal/v1/ingest/a", "/v1/ingest/a/extra", "/V1/ingest/a").forEach {
            resolverCalls.set(0)
            status(token(), it) shouldBe 401
            resolverCalls.get() shouldBe 1
        }
        status(token(), "/v1/ingest/a", "GET") shouldBe 401
        resolverCalls.get() shouldBe 2
    }

    @Test
    fun `an end-user token on a machine line is 401 and never reaches the resolver`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        status(
            token(claims { claim("act", mapOf("role" to "r", "case" to "c")) }, pair = operatorKeys, kid = "x"),
        ) shouldBe
            401
        resolverCalls.get() shouldBe 0
    }

    private fun entityStatus(
        principalScope: Any?,
        entity: String,
    ): Int {
        val t = token(claims { claim("principal_scope", principalScope) })
        return send(t, body = """{"entityRef":"$entity"}""").status
    }

    @Test
    fun `entity coverage entity ref covers exactly that ref`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        entityStatus("entity:E1", "E1") shouldBe 200
        entityStatus("entity:E1", "E2") shouldBe 403
        entityStatus("entity:E1", "E1X") shouldBe 403
        entityStatus("entity:E1", "e1") shouldBe 403
    }

    @Test
    fun `tenant coverage allows an entity node held under the token tenant`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        hold(MT_TENANT, "E2", node())
        entityStatus("tenant:$MT_TENANT", "E2") shouldBe 200
    }

    @Test
    fun `tenant coverage allows an entity node filled by the fallback`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        fallbackNodes[StructureKey(MT_TENANT, "E3")] = FallbackResult.Found(node())
        entityStatus("tenant:$MT_TENANT", "E3") shouldBe 200
    }

    @Test
    fun `tenant coverage allows a retired entity node`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        hold(MT_TENANT, "E4", node(retired = true))
        entityStatus("tenant:$MT_TENANT", "E4") shouldBe 200
    }

    @Test
    fun `tenant coverage refuses an entity held only under another tenant`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        hold(MT_OTHER_TENANT, "E9", node())
        entityStatus("tenant:$MT_TENANT", "E9") shouldBe 403
    }

    @Test
    fun `tenant coverage refuses a group node with that ref`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        hold(MT_TENANT, "G1", node(StructureNodeKind.GROUP))
        entityStatus("tenant:$MT_TENANT", "G1") shouldBe 403
    }

    @Test
    fun `tenant coverage refuses an absent node`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        entityStatus("tenant:$MT_TENANT", "unknown") shouldBe 403
    }

    @Test
    fun `tenant coverage refuses when the structure is unavailable`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        fallbackNodes[StructureKey(MT_TENANT, "E5")] = FallbackResult.Unavailable
        entityStatus("tenant:$MT_TENANT", "E5") shouldBe 403
    }

    @Test
    fun `tenant coverage of another tenant covers nothing`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        hold(MT_TENANT, "E2", node())
        hold(MT_OTHER_TENANT, "E2", node())
        entityStatus("tenant:$MT_OTHER_TENANT", "E2") shouldBe 403
    }

    @Test
    fun `tenant coverage refuses an entityRef that is not a valid reference`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        hold(MT_TENANT, " ", node())
        hold(MT_TENANT, "", node())
        entityStatus("tenant:$MT_TENANT", " ") shouldBe 403
        entityStatus("tenant:$MT_TENANT", "") shouldBe 403
    }

    @Test
    fun `installation and look-alike principal scopes cover nothing`() {
        publish(item(machineKeys, "mk", "MACHINE_ACCESS"))
        hold(MT_TENANT, "E1", node())
        listOf(
            "installation",
            "entity:",
            "Entity:E1",
            "entity:E1,E2",
            "entity: E1",
            "entity:E1 ",
            "entity:E1\n",
            "",
            "tenant:",
            "TENANT:$MT_TENANT",
            "installation ",
            "Installation",
            "entity",
            "entity:E1;entity:E2",
            listOf("entity:E1"),
            null,
        ).forEach { entityStatus(it, "E1") shouldBe 403 }
    }

    @Test
    fun `a machine principal cannot be built outside the verifier`() {
        shouldThrow<IllegalArgumentException> { Principal("m", PrincipalKind.MACHINE) }
        Principal("u", PrincipalKind.HUMAN).kind shouldBe PrincipalKind.HUMAN
        MachineEntityCoverage.of("entity:E1") shouldBe MachineEntityCoverage.Entity("E1")
        MachineEntityCoverage.of("installation") shouldBe MachineEntityCoverage.Installation
        MachineEntityCoverage.of(EntityRef("E1")) shouldBe MachineEntityCoverage.None
    }

    @Test
    fun `a malformed list stops the start-up`(
        @TempDir dir: Path,
    ) {
        fun load(text: String): MachineTokenFilter {
            val file = dir.resolve("META-INF/gst/machine-principal-operations.tsv")
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
            return MachineTokenFilter.from(
                URLClassLoader(arrayOf(dir.toUri().toURL()), null),
                verifier(),
                provider(tenantContext),
            )
        }
        load("")
        load("GET\t/v1/a\tingest\t-\nPOST\t/v1/b\tcertification\tcertification\n")
        listOf(
            "GET\t/v1/a\tingest",
            "GET\t/v1/a\tadmin\t-",
            "GET\t/v1/a\tingest\tingest",
            "GET\tv1/a\tingest\t-",
            "\t/v1/a\tingest\t-",
            "GET\t/v1/a\tingest\t-\nGET\t/v1/a\treporting\t-",
        ).forEach { shouldThrow<IllegalStateException> { load(it) } }
    }
}
