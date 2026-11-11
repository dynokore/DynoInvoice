package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.request.CounterpartyRequests
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.request.CounterpartyRequest
import com.dynokore.gst.masterdata.domain.request.RequestStatus
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.JwtEndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * INV-AC-18220 at the HTTP boundary of `submitCounterpartyDetails` and the binding-event consumer (INV-T-18221): the
 * real context over real PostgreSQL, the shared verifier ([JwtEndUserPrincipalResolver]) over a replica that holds the
 * LINK_SESSION key, and the two consumer handlers fed with the envelopes access-svc would publish.
 */
class CounterpartyLinkTest {
    private val linkKeys = rsa()
    private val identityKeys = rsa()
    private val replica = AuthorityReplica(Clock.systemUTC(), OperationRoleMap { emptyMap() })
    private val resolver: EndUserPrincipalResolver =
        JwtEndUserPrincipalResolver(
            NimbusJwtDecoder.withPublicKey(identityKeys.public as RSAPublicKey).build(),
            replica,
            Clock.systemUTC(),
        )

    init {
        val now = Instant.now()
        replica.keySets.accept(
            OperatorSessionKeysChanged(
                listOf(
                    OperatorSessionKey(
                        "LK1",
                        "RS256",
                        Base64.getEncoder().encodeToString(linkKeys.public.encoded),
                        now.minusSeconds(60),
                        now.plusSeconds(3600),
                        "LINK_SESSION",
                    ),
                ),
            ),
        )
    }

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture
            .runner()
            .withBean(EndUserPrincipalResolver::class.java, { resolver })
            .withConfiguration(AutoConfigurations.of(SignedLinkFeed::class.java))
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context))
            }
    }

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val tx: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val requests: CounterpartyRequests = context.getBean(CounterpartyRequests::class.java)
        val issued: EventHandler = context.getBean("signedLinkIssuedHandler", EventHandler::class.java)
        val invalidated: EventHandler = context.getBean("signedLinkInvalidatedHandler", EventHandler::class.java)
        val tenant = TenantId(UUID.randomUUID())
        val entity = "E-LINK-" + UUID.randomUUID().toString().take(8)

        fun <T> inTenant(
            of: TenantId = tenant,
            block: () -> T,
        ): T = tenants.bind(of, "IDN-OPERATOR") { block() }

        /** A SENT request of a new counterparty, stored as `sendCounterpartyRequest` would. */
        fun request(party: String = unique("CTP-LNK")): CounterpartyRequest {
            val request =
                CounterpartyRequest(
                    requestId = "REQ-" + UUID.randomUUID().toString().replace("-", ""),
                    tenant = tenant.toString(),
                    entity = entity,
                    party = PartyRef(party),
                    contactEmail = "ap@example.com.sg",
                    contactMobile = null,
                    status = RequestStatus.SENT,
                    lastRequestedAt = Instant.now().minusSeconds(10),
                    expiresAt = Instant.now().plusSeconds(86_400),
                )
            inTenant { tx.inTransaction { requests.save(request) } }
            return request
        }

        fun linkRef(): String {
            val raw = UUID.randomUUID().toString().replace("-", "") + "abcdefghij"
            return "LNK-" + raw.take(LINK_REF_LENGTH)
        }

        fun envelope(
            type: String,
            data: String,
            id: String = UUID.randomUUID().toString(),
            of: TenantId = tenant,
        ) = Envelope(
            id = EventId(id),
            source = "/gst/access",
            type = type,
            subject = "link",
            time = Instant.now(),
            tenant = of,
            entity = EntityRef(entity),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "access-svc-0",
            correlation = CorrelationId("c-1"),
            causation = CausationId("c-1"),
            dataJson = data,
        )

        @Suppress("LongParameterList")
        fun issue(
            linkRef: String,
            requestId: String,
            expiry: Instant = Instant.now().plusSeconds(3600),
            id: String = UUID.randomUUID().toString(),
            of: TenantId = tenant,
        ) = deliver(
            issued,
            envelope(
                ISSUED,
                """{"linkRef":"$linkRef","subjectReference":"$requestId","purpose":"COUNTERPARTY_DETAILS",""" +
                    """"tenant":"$of","expiry":"$expiry"}""",
                id,
                of,
            ),
            of,
        )

        fun invalidate(
            linkRef: String,
            requestId: String,
            at: Instant = Instant.now(),
            id: String = UUID.randomUUID().toString(),
        ) = deliver(
            invalidated,
            envelope(
                INVALIDATED,
                """{"linkRef":"$linkRef","subjectReference":"$requestId","reason":"ATTEMPTS_EXHAUSTED",""" +
                    """"invalidatedAt":"$at"}""",
                id,
            ),
            tenant,
        )

        fun deliver(
            handler: EventHandler,
            envelope: Envelope,
            of: TenantId,
        ) = inTenant(of) { handler.handle(envelope) }

        fun token(
            linkRef: String,
            of: TenantId? = tenant,
            typ: String? = "link-session+jwt",
            signer: KeyPair = linkKeys,
        ): String {
            val now = Instant.now()
            val claims =
                JWTClaimsSet
                    .Builder()
                    .subject("link:$linkRef")
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(600)))
                    .apply { of?.let { claim("tenant_id", it.toString()) } }
                    .build()
            val header =
                JWSHeader
                    .Builder(JWSAlgorithm.RS256)
                    .keyID("LK1")
                    .apply { typ?.let { type(JOSEObjectType(it)) } }
                    .build()
            return SignedJWT(header, claims).also { it.sign(RSASSASigner(signer.private)) }.serialize()
        }

        fun submit(
            requestId: String,
            bearer: String?,
        ): Pair<Int, String> {
            val web = context.sourceApplicationContext as WebApplicationContext
            val request =
                MockMvcRequestBuilders
                    .post("/internal/v1/masterdata/counterparty-requests/$requestId/submission")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
            if (bearer != null) request.header("Authorization", "Bearer $bearer")
            val response =
                MockMvcBuilders
                    .webAppContextSetup(web)
                    .build()
                    .perform(request)
                    .andReturn()
                    .response
            return response.status to response.contentAsString
        }

        fun sql(query: String): String =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().executeQuery(query).use { rs ->
                    rs.next()
                    rs.getString(1).orEmpty()
                }
            }

        /** The counterparty, request and binding rows of the tenant, compared before and after a refusal. */
        fun fingerprint(): String =
            listOf(
                "counterparty",
                "counterparty_version",
                "counterparty_identifier",
                "counterparty_request",
                "link_binding",
            ).joinToString("|") {
                sql(
                    "select count(*) || ':' || coalesce(md5(string_agg(t::text, '|' order by t::text)), '') " +
                        "from masterdata.$it t where tenant_id = '$tenant'::uuid",
                )
            }

        fun binding(linkRef: String): String =
            sql(
                "select coalesce(tenant,'-') || '/' || coalesce(request_id,'-') || '/' || coalesce(expiry::text,'-') " +
                    "|| '/' || coalesce(invalidated_at::text,'-') || '/' || coalesce(last_event_id,'-') " +
                    "from masterdata.link_binding where link_ref = '$linkRef'",
            )
    }

    @Test
    fun `INV-AC-18220 a verified token of the bound link is accepted and stores providedBy COUNTERPARTY`() =
        run { env ->
            val request = env.request()
            val link = env.linkRef()
            env.issue(link, request.requestId)
            val (status, body) = env.submit(request.requestId, env.token(link))
            assertThat(status).describedAs(body).isEqualTo(200)
            assertThat(body).contains("\"providedBy\":\"COUNTERPARTY\"")
            val evidence =
                env.sql(
                    "select string_agg(evidence_ref, ',') from masterdata.counterparty_identifier " +
                        "where tenant_id = '${env.tenant}'::uuid and party_ref = '${request.party.value}'",
                )
            assertThat(evidence).isEqualTo("link:$link/${request.requestId}")
            val stored =
                env.sql(
                    "select status from masterdata.counterparty_request where request_id = '${request.requestId}' " +
                        "and tenant_id = '${env.tenant}'::uuid",
                )
            assertThat(stored).isEqualTo("ANSWERED")
        }

    @Test
    fun `INV-AC-18220 every refusal of the link evidence answers 401 and changes nothing`() =
        run { env ->
            val request = env.request()
            val other = env.request()
            val link = env.linkRef()
            env.issue(link, request.requestId)
            val expired = env.linkRef()
            env.issue(expired, request.requestId, expiry = Instant.now().minusSeconds(5))
            val invalidatedLink = env.linkRef()
            env.issue(invalidatedLink, request.requestId)
            env.invalidate(invalidatedLink, request.requestId)
            val good = env.token(link)
            val altered = good.dropLast(4) + (if (good.takeLast(4) == "AAAA") "BBBB" else "AAAA")
            val otherTenant = TenantId(UUID.randomUUID())
            val refused =
                mapOf(
                    "no token" to (request.requestId to null),
                    "altered token" to (request.requestId to altered),
                    "typ JWT" to (request.requestId to env.token(link, typ = "JWT")),
                    "no typ" to (request.requestId to env.token(link, typ = null)),
                    "token of another tenant" to (request.requestId to env.token(link, of = otherTenant)),
                    "no tenant claim" to (request.requestId to env.token(link, of = null)),
                    "expired link" to (request.requestId to env.token(expired)),
                    "invalidated link" to (request.requestId to env.token(invalidatedLink)),
                    "link bound to another request" to (other.requestId to good),
                    "unknown link" to (request.requestId to env.token(env.linkRef())),
                    "identity-provider key" to (request.requestId to env.token(link, signer = identityKeys)),
                )
            val before = env.fingerprint()
            refused.forEach { (label, call) ->
                val (status, body) = env.submit(call.first, call.second)
                assertThat(status).describedAs("$label: $body").isEqualTo(401)
                assertThat(body).describedAs(label).contains("SRT-003")
                assertThat(env.fingerprint()).describedAs("$label changed a row").isEqualTo(before)
            }
        }

    @Test
    fun `INV-AC-18220 the same linkRef under another tenant does not reach the binding`() =
        run { env ->
            val request = env.request()
            val link = env.linkRef()
            env.issue(link, request.requestId)
            val before = env.binding(link)
            val intruder = TenantId(UUID.randomUUID())
            // the intruder tenant holds no request of that id, so the event is ignored and writes nothing
            env.issue(link, request.requestId, of = intruder)
            assertThat(env.binding(link)).isEqualTo(before)
            assertThat(env.submit(request.requestId, env.token(link, of = intruder)).first).isEqualTo(401)
        }

    @Test
    fun `INV-AC-18220 a repeated and a reordered event leave the binding row equal`() =
        run { env ->
            val request = env.request()
            val first = env.linkRef()
            val expiry = Instant.now().plusSeconds(3600)
            val at = Instant.now()
            val issueId = "0198" + UUID.randomUUID().toString().substring(4)
            val invalidateId = "0199" + UUID.randomUUID().toString().substring(4)
            env.issue(first, request.requestId, expiry, issueId)
            env.invalidate(first, request.requestId, at, invalidateId)
            val inOrder = env.binding(first)
            env.issue(first, request.requestId, expiry, issueId)
            env.invalidate(first, request.requestId, at, invalidateId)
            assertThat(env.binding(first)).describedAs("repeated").isEqualTo(inOrder)
            val second = env.linkRef()
            env.invalidate(second, request.requestId, at, invalidateId)
            env.issue(second, request.requestId, expiry, issueId)
            assertThat(env.binding(second)).describedAs("reordered").isEqualTo(inOrder)
        }

    @Test
    fun `INV-AC-18220 an invalidation before the issue creates the row invalidated and the link is refused`() =
        run { env ->
            val request = env.request()
            val link = env.linkRef()
            env.invalidate(link, request.requestId)
            assertThat(env.binding(link)).startsWith("-/-/-/")
            env.issue(link, request.requestId)
            assertThat(env.submit(request.requestId, env.token(link)).first).isEqualTo(401)
        }

    @Test
    fun `INV-AC-18220 an invalidated link is refused and a re-issued link is accepted`() =
        run { env ->
            val request = env.request()
            val old = env.linkRef()
            env.issue(old, request.requestId)
            env.invalidate(old, request.requestId)
            assertThat(env.submit(request.requestId, env.token(old)).first).isEqualTo(401)
            val reissued = env.linkRef()
            env.issue(reissued, request.requestId)
            assertThat(env.submit(request.requestId, env.token(reissued)).first).isEqualTo(200)
        }

    @Test
    fun `INV-AC-18220 a poison event is a contract violation and writes nothing`() =
        run { env ->
            val request = env.request()
            val link = env.linkRef()
            val before = env.fingerprint()
            val subject = """"subjectReference":"${request.requestId}""""

            fun rest(
                tenant: Any,
                expiry: Any,
            ) = """"purpose":"P","tenant":"$tenant","expiry":"$expiry""""
            val own = rest(env.tenant, Instant.now())
            listOf(
                """{"linkRef":"not-a-link",$subject,$own}""",
                """{"linkRef":"$link",$own}""",
                """{"linkRef":"$link",$subject,${rest(env.tenant, "soon")}}""",
                """{"linkRef":"$link",$subject,${rest(UUID.randomUUID(), Instant.now())}}""",
            ).forEach { data ->
                assertThatThrownBy { env.deliver(env.issued, env.envelope(ISSUED, data), env.tenant) }
                    .describedAs(data)
                    .isInstanceOf(ContractViolation::class.java)
            }
            assertThatThrownBy {
                env.deliver(env.invalidated, env.envelope(INVALIDATED, """{"linkRef":"$link"}"""), env.tenant)
            }.isInstanceOf(ContractViolation::class.java)
            assertThat(env.fingerprint()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-18220 an issue event that names no request of the tenant is ignored`() =
        run { env ->
            val link = env.linkRef()
            val before = env.fingerprint()
            env.issue(link, "REQ-unknown")
            assertThat(env.fingerprint()).isEqualTo(before)
        }

    private companion object {
        const val ISSUED = "com.dynokore.gst.access.SignedLinkIssued.v1"
        const val INVALIDATED = "com.dynokore.gst.access.SignedLinkInvalidated.v1"
        const val LINK_REF_LENGTH = 22
        const val BODY =
            """{"uen":"201954321B","legalName":"Acme Trading Pte Ltd","address":"1 Raffles Place, Singapore"}"""
    }
}
