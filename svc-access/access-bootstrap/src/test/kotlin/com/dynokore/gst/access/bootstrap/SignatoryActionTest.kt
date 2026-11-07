package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiLinkInvalidated
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.ConsentController
import com.dynokore.gst.access.adapters.http.SignedLinksController
import com.dynokore.gst.access.adapters.link.Sha256PasscodeDigests
import com.dynokore.gst.access.application.link.LinkPermissions
import com.dynokore.gst.access.application.link.SignedLinks
import com.dynokore.gst.access.contract.model.GrantConsentRequest
import com.dynokore.gst.access.contract.model.SignatoryActionRequest
import com.dynokore.gst.access.contract.model.SignatoryActionResponse
import com.dynokore.gst.access.contract.model.WithdrawConsentRequest
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `recordSignatoryAction` (INV-T-17453; INV-REQ-899, INV-REQ-900, INV-REQ-1010, INV-AC-2686): the real application
 * context over a real PostgreSQL. The link is the principal `link:<linkRef>`; the consent, the client entity and the
 * actor come from the stored link. The stored evidence and the consent rows are read back by a superuser.
 */
@Suppress("TooManyFunctions")
class SignatoryActionTest {
    private val signatory = "user:signatory"
    private val agent = "user:agent"
    private val digests = Sha256PasscodeDigests()

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        val tenants = context.getBean(TenantContext::class.java)
        val api = context.getBean(SignedLinksController::class.java)
        val consents = context.getBean(ConsentController::class.java)

        init {
            listOf("CLI-A", "CLI-B").forEach { client ->
                context
                    .getBean(StructureReplica::class.java)
                    .accept(
                        OrganisationStructureChanged(
                            tenant,
                            client,
                            StructureChange.CREATED,
                            BoundaryFixture.entityNode(),
                        ),
                    )
                Seeds.grant(
                    context,
                    tenant,
                    SeedSpec(signatory, Role.CLIENT_SIGNATORY, Scope.Entity(EntityRef(client))),
                )
                Seeds.grant(context, tenant, SeedSpec(agent, Role.AGENT_OPERATOR, Scope.Entity(EntityRef(client))))
            }
        }

        fun consent(client: String): String =
            tenants
                .bind(tenant, signatory) {
                    consents.grantConsent(
                        UUID.randomUUID(),
                        GrantConsentRequest(
                            clientRef = client,
                            agentRef = agent,
                            validFrom = LocalDate.of(2026, 1, 1),
                            authorisingPersonEmail = "director@client.example",
                            scope = null,
                            submitInvoiceData = true,
                            fileGstReturn = false,
                            mappingApproval = false,
                        ),
                    )
                }.body!!
                .consentRef

        fun withdrawConsent(ref: String) =
            tenants.bind(tenant, signatory) {
                consents.withdrawConsent(ref, UUID.randomUUID(), WithdrawConsentRequest("Relationship ended"))
            }

        fun storedHandoverStatement(ref: String): String? =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select handover_statement_ref from access.consent where tenant_id = ? and consent_ref = ?",
                    ).use { s ->
                        s.setObject(1, UUID.fromString(tenant.toString()))
                        s.setString(2, ref)
                        s.executeQuery().use {
                            check(it.next()) { "no consent $ref" }
                            it.getString(1)
                        }
                    }
            }

        fun consentStatus(ref: String): String =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement("select status from access.consent where tenant_id = ? and consent_ref = ?")
                    .use { s ->
                        s.setObject(1, UUID.fromString(tenant.toString()))
                        s.setString(2, ref)
                        s.executeQuery().use {
                            check(it.next()) { "no consent $ref" }
                            it.getString(1)
                        }
                    }
            }

        fun link(
            client: String? = "CLI-A",
            consent: String? = client?.let { consent(it) },
            verified: Boolean = true,
            status: String = "ACTIVE",
            expiresAt: Instant = Instant.now().plusSeconds(3600),
        ): String {
            val alertEntity = client ?: "CLI-A"
            val ref =
                "LNK-" +
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(22)
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "insert into access.signed_link (id, tenant_id, link_ref, purpose, client_ref, consent_ref, " +
                            "alert_entity, recipient_email, expires_at, passcode_digest, status, wrong_entries, " +
                            "verified_at, invalidated_at, source, source_event_id, created_at, created_by, " +
                            "updated_at, updated_by, version) values (?, ?, ?, 'CONFIRM_FILING', ?, ?, ?, " +
                            "'signatory@client.example', " +
                            "?, ?, ?, 0, ?, ?, ?, ?, now(), 'test', now(), 'test', 0)",
                    ).use { s ->
                        s.setObject(1, UUID.randomUUID())
                        s.setObject(2, UUID.fromString(tenant.toString()))
                        s.setString(3, ref)
                        s.setString(4, client)
                        s.setString(5, consent)
                        s.setString(6, alertEntity)
                        s.setTimestamp(7, Timestamp.from(expiresAt))
                        s.setString(8, digests.digestOf(LinkRef(ref), "482913"))
                        s.setString(9, status)
                        s.setTimestamp(10, if (verified) Timestamp.from(Instant.now().minusSeconds(5)) else null)
                        s.setTimestamp(11, if (status == "INVALIDATED") Timestamp.from(Instant.now()) else null)
                        s.setString(12, if (client == null) "EVENT" else "CALL")
                        s.setString(13, if (client == null) "evt-$ref" else null)
                        s.executeUpdate()
                    }
            }
            return ref
        }

        fun act(
            ref: String,
            kind: SignatoryActionRequest.Kind,
            pack: String? = null,
            query: String? = null,
        ) = api.recordSignatoryAction(ref, UUID.randomUUID(), SignatoryActionRequest(kind, pack, query))

        fun evidence(): List<Pair<String, String>> =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select actor, kind || '|' || client_ref from access.signatory_action where tenant_id = ? " +
                            "order by recorded_at, id",
                    ).use { s ->
                        s.setObject(1, UUID.fromString(tenant.toString()))
                        s.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                        }
                    }
            }

        fun acknowledgements(client: String): Int =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select count(*) from access.mapping_acknowledgement where tenant_id = ? and client_ref = ?",
                    ).use { s ->
                        s.setObject(1, UUID.fromString(tenant.toString()))
                        s.setString(2, client)
                        s.executeQuery().use {
                            it.next()
                            it.getInt(1)
                        }
                    }
            }
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner(catalogueMatrix = true, requirements = null).run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    private val ack = SignatoryActionRequest.Kind.ACKNOWLEDGE
    private val withdraw = SignatoryActionRequest.Kind.WITHDRAW
    private val query = SignatoryActionRequest.Kind.QUERY

    private fun status(failure: Throwable?): Any =
        when (failure) {
            is ApiLinkInvalidated -> 410
            is ApiRefusal -> failure.code
            else -> failure.toString()
        }

    @Test
    fun `INV-REQ-899 a link that is unknown, expired or not yet verified is 401 and stores nothing`() =
        run { env ->
            val unverified = env.link(verified = false)
            val expired = env.link(expiresAt = Instant.now().minusSeconds(1))
            val live = env.link()
            listOf("LNK-UNKNOWN", unverified, expired, live.lowercase(), "$live ", live.dropLast(1)).forEach { ref ->
                val failure = runCatching { env.act(ref, ack, "2026.3") }.exceptionOrNull()
                assertThat(status(failure)).describedAs(ref).isEqualTo(ClosedErrorCode.UNAUTHENTICATED)
            }
            assertThat(env.evidence()).isEmpty()
            assertThat(env.acknowledgements("CLI-A")).isEqualTo(0)
        }

    @Test
    fun `INV-REQ-899 an invalidated or revoked link is 410 ACC-004 and stores nothing`() =
        run { env ->
            listOf("INVALIDATED", "REVOKED").forEach { state ->
                val ref = env.link(status = state)
                assertThatThrownBy { env.act(ref, ack, "2026.3") }
                    .describedAs(state)
                    .isInstanceOf(ApiLinkInvalidated::class.java)
            }
            assertThat(env.evidence()).isEmpty()
        }

    @Test
    fun `INV-REQ-899 a body that carries consentRef or clientRef is 400 and the link's consent is untouched`() =
        run { env ->
            val consent = env.consent("CLI-A")
            val ref = env.link(consent = consent)
            val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(env.context).build()
            listOf(
                """{"kind":"WITHDRAW","consentRef":"CNS-OTHER"}""",
                """{"kind":"WITHDRAW","clientRef":"CLI-B"}""",
                """{"kind":"ACKNOWLEDGE","packVersion":"2026.3","clientRef":"CLI-B"}""",
            ).forEach { body ->
                val result =
                    mvc
                        .perform(
                            post("/internal/v1/access/signatory-links/$ref/actions")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        ).andReturn()
                assertThat(result.response.status).describedAs(body + result.response.contentAsString).isEqualTo(400)
            }
            assertThat(env.consentStatus(consent)).isEqualTo("LIVE")
            assertThat(env.evidence()).isEmpty()
            // a missing pack version or query text is 400 as well
            listOf(ack to null, query to null).forEach { (kind, pack) ->
                val failure = runCatching { env.act(ref, kind, pack) }.exceptionOrNull()
                assertThat(status(failure)).describedAs(kind.name).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            }
        }

    @Test
    fun `INV-REQ-1010 WITHDRAW withdraws exactly the consent of the link and a repeat is a no-op`() =
        run { env ->
            val mine = env.consent("CLI-A")
            val other = env.consent("CLI-B")
            val ref = env.link(consent = mine)
            val first = env.act(ref, withdraw)
            assertThat(first.statusCode.value()).isEqualTo(201)
            assertThat(first.body!!.kind).isEqualTo(SignatoryActionResponse.Kind.WITHDRAW)
            assertThat(env.consentStatus(mine)).isEqualTo("WITHDRAWN")
            assertThat(env.consentStatus(other)).isEqualTo("LIVE")
            val repeat = env.act(ref, withdraw)
            assertThat(repeat.body!!.evidenceRef).isEqualTo(first.body!!.evidenceRef)
            assertThat(repeat.body!!.recordedAt).isEqualTo(first.body!!.recordedAt)
            assertThat(env.evidence()).containsExactly("link:$ref" to "WITHDRAW|CLI-A")
        }

    @Test
    fun `INV-REQ-1010 handoverStatementRef is the stored one on WITHDRAW and its repeat, absent otherwise`() =
        run { env ->
            val mine = env.consent("CLI-A")
            val ref = env.link(consent = mine)
            assertThat(env.act(ref, ack, "2026.3").body!!.handoverStatementRef).isNull()
            assertThat(env.act(ref, query, "2026.3", "Question").body!!.handoverStatementRef).isNull()
            val first = env.act(ref, withdraw).body!!.handoverStatementRef
            assertThat(first).isNotNull().isEqualTo(env.storedHandoverStatement(mine))
            assertThat(env.act(ref, withdraw).body!!.handoverStatementRef).isEqualTo(first)
        }

    @Test
    fun `INV-REQ-1010 a link whose consent is no longer the live one withdraws nothing, not the newer consent`() =
        run { env ->
            val old = env.consent("CLI-A")
            val ref = env.link(consent = old)
            env.withdrawConsent(old)
            val newer = env.consent("CLI-A")
            val failure = runCatching { env.act(ref, withdraw) }.exceptionOrNull()
            assertThat(status(failure)).isEqualTo(ClosedErrorCode.NOT_FOUND)
            assertThat(env.consentStatus(newer)).isEqualTo("LIVE")
            assertThat(env.evidence()).isEmpty()
        }

    @Test
    fun `INV-REQ-899 an action by a link of client A never reads or changes anything of client B`() =
        run { env ->
            val b = env.consent("CLI-B")
            val ref = env.link(client = "CLI-A")
            assertThat(env.act(ref, ack, "2026.3").statusCode.value()).isEqualTo(201)
            assertThat(env.act(ref, query, "2026.3", "Is the mapping final?").statusCode.value()).isEqualTo(201)
            assertThat(env.acknowledgements("CLI-A")).isEqualTo(1)
            assertThat(env.acknowledgements("CLI-B")).isEqualTo(0)
            assertThat(env.consentStatus(b)).isEqualTo("LIVE")
            assertThat(env.evidence().map { it.second }).containsExactly("ACKNOWLEDGE|CLI-A", "QUERY|CLI-A")
            // a link naming the consent of B while it is bound to A withdraws nothing of B
            val crossed = env.link(client = "CLI-A", consent = b)
            val failure = runCatching { env.act(crossed, withdraw) }.exceptionOrNull()
            assertThat(status(failure)).isEqualTo(ClosedErrorCode.NOT_FOUND)
            assertThat(env.consentStatus(b)).isEqualTo("LIVE")
        }

    @Test
    fun `INV-REQ-899 an unknown bound entity is 403 and a key outside the client signatory cells is denied`() =
        run { env ->
            val ghost = env.link(client = "CLI-GHOST", consent = "CNS-GHOST")
            val failure = runCatching { env.act(ghost, ack, "2026.3") }.exceptionOrNull()
            assertThat(status(failure)).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.evidence()).isEmpty()
            // the scoped key decides: mapping-pack.approve is in no client.signatory cell of the pinned catalogue
            val ref = env.link()
            val permissions = env.context.getBean(LinkPermissions::class.java)
            val links = env.context.getBean(SignedLinks::class.java)
            val tx = env.context.getBean(TransactionRunner::class.java)
            val now = Instant.now()

            fun allows(key: Permission) =
                env.tenants.bind(env.tenant, "link:$ref") {
                    tx.inTransaction { permissions.allows(checkNotNull(links.find(LinkRef(ref))), key, now) }
                }
            assertThat(allows(Permission("mapping-pack.approve"))).isFalse()
            assertThat(allows(Permission("validation.override"))).isFalse()
            assertThat(allows(LinkPermissions.CONSENT_WITHDRAW)).isTrue()
            assertThat(allows(LinkPermissions.MAPPING_EFFECT_ACKNOWLEDGE)).isTrue()
        }

    @Test
    fun `INV-REQ-900 each success stores exactly one evidence record whose actor is link-linkRef`() =
        run { env ->
            val ref = env.link()
            repeat(3) { env.act(ref, ack, "2026.3") }
            env.act(ref, ack, "2026.4")
            env.act(ref, query, "2026.4", "Question")
            assertThat(env.evidence())
                .containsExactly(
                    "link:$ref" to "ACKNOWLEDGE|CLI-A",
                    "link:$ref" to "ACKNOWLEDGE|CLI-A",
                    "link:$ref" to "QUERY|CLI-A",
                )
        }

    @Test
    fun `INV-AC-2686 sixty calls on one verified link are all served, none is 429, none has Retry-After`() =
        run { env ->
            val ref = env.link()
            val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(env.context).build()
            val statuses = mutableListOf<Int>()
            val retryAfter = mutableListOf<String?>()
            repeat(60) {
                val result =
                    mvc
                        .perform(
                            post("/internal/v1/access/signatory-links/$ref/actions")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"kind":"ACKNOWLEDGE","packVersion":"2026.3"}"""),
                        ).andReturn()
                statuses += result.response.status
                retryAfter += result.response.getHeader(HttpHeaders.RETRY_AFTER)
            }
            assertThat(statuses).hasSize(60).doesNotContain(429).containsOnly(201)
            assertThat(retryAfter).containsOnlyNulls()
            assertThat(env.evidence()).hasSize(1)
        }
}
