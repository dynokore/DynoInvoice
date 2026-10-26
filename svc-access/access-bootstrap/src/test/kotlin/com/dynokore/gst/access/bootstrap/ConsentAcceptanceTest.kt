package com.dynokore.gst.access.bootstrap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.ConsentController
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.consent.DocumentList
import com.dynokore.gst.access.application.consent.HandoverStatements
import com.dynokore.gst.access.application.consent.UnsubmittedDocument
import com.dynokore.gst.access.application.consent.UnsubmittedDocuments
import com.dynokore.gst.access.contract.model.AuthoriseResponse.Decision.ALLOW
import com.dynokore.gst.access.contract.model.AuthoriseResponse.Decision.DENY
import com.dynokore.gst.access.contract.model.ConsentResponse
import com.dynokore.gst.access.contract.model.GrantConsentRequest
import com.dynokore.gst.access.contract.model.WithdrawConsentRequest
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.LocalDate
import java.util.UUID

/**
 * The acceptance criteria of INV-T-4939 at the service boundary: the real application context over a real PostgreSQL,
 * the permission matrix and the role catalogue from the gst-catalogue table (no fixture rows), the consent controller
 * and `authorise` called under a bound tenant.
 *
 * Scope items come from the production `agent-scope-items.tsv` (the real `ScopeItemRequirements` bean, no test
 * table): `submission.replay` needs SUBMIT_INVOICE_DATA and `filing.confirm` needs FILE_GST_RETURN (INV-Q-8866 A).
 */
class ConsentAcceptanceTest {
    private val signatory = "user:signatory"
    private val agent = "user:agent"
    private val replay = "submission.replay"
    private val filing = "filing.confirm"

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        /** Own tenant per test: the database is shared. */
        val tenant = TenantId(UUID.randomUUID())
        val tenants = context.getBean(TenantContext::class.java)
        val tx = context.getBean(TransactionRunner::class.java)
        val consents = context.getBean(ConsentRepository::class.java)
        val statements = context.getBean(HandoverStatements::class.java)
        val api = context.getBean(ConsentController::class.java)
        val authorise = context.getBean(AuthoriseController::class.java)

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

        fun grant(
            client: String,
            items: Set<ScopeItem> = emptySet(),
            scope: List<String>? = null,
            asUser: String = signatory,
        ): ConsentResponse =
            tenants
                .bind(tenant, asUser) {
                    api.grantConsent(
                        UUID.randomUUID(),
                        GrantConsentRequest(
                            clientRef = client,
                            agentRef = agent,
                            validFrom = LocalDate.of(2026, 1, 1),
                            authorisingPersonEmail = "director@client.example",
                            scope = scope,
                            submitInvoiceData = ScopeItem.SUBMIT_INVOICE_DATA in items,
                            fileGstReturn = ScopeItem.FILE_GST_RETURN in items,
                            mappingApproval = ScopeItem.MAPPING_APPROVAL in items,
                        ),
                    )
                }.body!!

        fun withdraw(
            consentRef: String,
            asUser: String = signatory,
        ): ConsentResponse =
            tenants
                .bind(tenant, asUser) {
                    api.withdrawConsent(consentRef, UUID.randomUUID(), WithdrawConsentRequest("Relationship ended"))
                }.body!!

        /** The agent's decision on [client], asked of `authorise` as a service does. */
        fun decide(
            client: String,
            permission: String,
        ) = tenants
            .bind(tenant, signatory) { authorise.authorise(agent, permission, "entity:$client").body!!.decision }

        fun liveConsent(client: String) =
            tenants.bind(tenant, signatory) { tx.inTransaction { consents.findLiveForClient(EntityRef(client)) } }

        fun statement(ref: String) = tenants.bind(tenant, signatory) { tx.inTransaction { statements.find(ref) } }

        /** The number of consent rows of [tenant], counted by a superuser that sees past row-level security. */
        fun consentRows(tenant: TenantId): Int =
            BoundaryFixture.asSuperuser { connection ->
                connection
                    .prepareStatement("select count(*) from access.consent where tenant_id = ?")
                    .use { statement ->
                        statement.setObject(1, tenant.toString().let(UUID::fromString))
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "count returned no row" }
                            rows.getInt(1)
                        }
                    }
            }

        /** Changes the stored consent behind the service's back (a superuser, no use case, no event). */
        fun updateRow(
            consentRef: String,
            assignments: String,
        ) = BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement("update access.consent set $assignments where consent_ref = ?")
                .use { statement ->
                    statement.setString(1, consentRef)
                    check(statement.executeUpdate() == 1) { "the consent $consentRef is not stored" }
                }
        }
    }

    private fun run(
        documents: UnsubmittedDocuments? = null,
        block: (Env) -> Unit,
    ) {
        var runner = BoundaryFixture.runner(catalogueMatrix = true, requirements = null)
        if (documents != null) runner = runner.withBean(UnsubmittedDocuments::class.java, { documents })
        runner.run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    private fun refusal(block: () -> Unit) = runCatching(block).exceptionOrNull()

    @Test
    fun `INV-AC-8606 grantConsent records the three items separately, legacy scope 400 FILE_GST_RETURN`() =
        run { env ->
            val granted = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            assertThat(granted.status).isEqualTo(ConsentResponse.Status.ACTIVE)
            assertThat(granted.submitInvoiceData).isTrue()
            assertThat(granted.fileGstReturn).isFalse()
            assertThat(granted.mappingApproval).isFalse()
            assertThat(checkNotNull(env.liveConsent("CLI-A")).items).containsExactly(ScopeItem.SUBMIT_INVOICE_DATA)
            // INV-AC-1946: the replay is allowed, the filing fact is refused
            assertThat(env.decide("CLI-A", replay)).isEqualTo(ALLOW)
            assertThat(env.decide("CLI-A", filing)).isEqualTo(DENY)
            // the second call, carrying a non-empty legacy scope array, is refused with 400 and records nothing
            val refused = refusal { env.grant("CLI-B", setOf(ScopeItem.FILE_GST_RETURN), scope = listOf("everything")) }
            assertThat(refused).hasMessageContaining("SRT-001")
            assertThat(env.liveConsent("CLI-B")).isNull()
            assertThat(env.consentRows(env.tenant)).isEqualTo(1)
        }

    @Test
    fun `INV-AC-1946 a consent grants only the chosen items, FILE_GST_RETURN half`() =
        run { env ->
            env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            assertThat(env.decide("CLI-A", replay)).isEqualTo(ALLOW)
            assertThat(env.decide("CLI-A", filing)).isEqualTo(DENY)
        }

    @Test
    fun `INV-AC-2255 an agent consent with nothing granted refuses the agent until SUBMIT_INVOICE_DATA is granted`() =
        run { env ->
            val first = env.grant("CLI-A")
            assertThat(listOf(first.submitInvoiceData, first.fileGstReturn, first.mappingApproval)).containsOnly(false)
            assertThat(env.decide("CLI-A", replay)).isEqualTo(DENY)
            // a consent is not edited: the signatory withdraws it and grants a new one with the item
            env.withdraw(first.consentRef)
            env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            assertThat(env.decide("CLI-A", replay)).isEqualTo(ALLOW)
        }

    @Test
    fun `INV-AC-8603 by default an agent may do nothing for the client and the item values default to false`() =
        run { env ->
            val request = GrantConsentRequest("CLI-A", agent, LocalDate.of(2026, 1, 1), "director@client.example")
            val defaults = listOf(request.submitInvoiceData, request.fileGstReturn, request.mappingApproval)
            assertThat(defaults).containsOnly(false)
            val granted =
                env.tenants.bind(env.tenant, signatory) { env.api.grantConsent(UUID.randomUUID(), request) }.body!!
            assertThat(checkNotNull(env.liveConsent("CLI-A")).items).isEmpty()
            assertThat(granted.submitInvoiceData).isFalse()
            assertThat(env.decide("CLI-A", replay)).isEqualTo(DENY)
            assertThat(env.decide("CLI-A", filing)).isEqualTo(DENY)
            env.withdraw(granted.consentRef)
            env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            assertThat(env.decide("CLI-A", replay)).isEqualTo(ALLOW)
        }

    @Test
    fun `INV-AC-1822 consent for client A is accepted for A and refused for client B, the Wave 2 part`() =
        run { env ->
            env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            assertThat(env.decide("CLI-A", replay)).isEqualTo(ALLOW)
            assertThat(env.decide("CLI-B", replay)).isEqualTo(DENY)
        }

    @Test
    fun `INV-AC-8604 a third-party issuer acts for a client only under its own consent record`() =
        run { env ->
            val consent = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            assertThat(env.decide("CLI-A", replay)).isEqualTo(ALLOW)
            assertThat(env.decide("CLI-B", replay)).isEqualTo(DENY)
            env.withdraw(consent.consentRef)
            assertThat(env.decide("CLI-A", replay)).isEqualTo(DENY)
        }

    @Test
    fun `INV-AC-1745 an agent action after withdrawal is refused at the next check and a handover statement exists`() =
        run { env ->
            val consent = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            assertThat(env.decide("CLI-A", replay)).isEqualTo(ALLOW)
            val withdrawn = env.withdraw(consent.consentRef)
            assertThat(env.decide("CLI-A", replay)).isEqualTo(DENY)
            val statement = env.statement(checkNotNull(withdrawn.handoverStatementRef))
            assertThat(statement?.client).isEqualTo(EntityRef("CLI-A"))
        }

    @Test
    fun `INV-AC-8605 the check reads the consent record on every call, not a copy`() =
        run { env ->
            val consent = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            // the scope is read at least once
            assertThat(env.decide("CLI-A", replay)).isEqualTo(ALLOW)
            assertThat(env.decide("CLI-A", replay)).isEqualTo(ALLOW)
            // the record changes behind the service's back, through no use case and no event: the next check sees it
            env.updateRow(consent.consentRef, "status = 'WITHDRAWN', handover_statement_ref = 'HOS-OUT-OF-BAND'")
            assertThat(env.decide("CLI-A", replay)).isEqualTo(DENY)
        }

    @Test
    fun `INV-AC-8607 every agent operation is checked live against the item it needs`() =
        run { env ->
            val consent = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            assertThat(env.decide("CLI-A", replay)).isEqualTo(ALLOW)
            assertThat(env.decide("CLI-A", filing)).isEqualTo(DENY)
            assertThat(env.decide("CLI-B", replay)).isEqualTo(DENY)
            // a permission the table does not list needs an item nobody can name: refused
            assertThat(env.decide("CLI-A", "validation.override")).isEqualTo(DENY)
            env.updateRow(consent.consentRef, "submit_invoice_data = false")
            assertThat(env.decide("CLI-A", replay)).isEqualTo(DENY)
            env.updateRow(consent.consentRef, "submit_invoice_data = true")
            assertThat(env.decide("CLI-A", replay)).isEqualTo(ALLOW)
            env.withdraw(consent.consentRef)
            assertThat(env.decide("CLI-A", replay)).isEqualTo(DENY)
        }

    @Test
    fun `INV-AC-933 after FILE_GST_RETURN is withdrawn the next filing operation is refused`() =
        run { env ->
            val consent = env.grant("CLI-A", setOf(ScopeItem.FILE_GST_RETURN))
            assertThat(env.decide("CLI-A", filing)).isEqualTo(ALLOW)
            env.withdraw(consent.consentRef)
            assertThat(env.decide("CLI-A", filing)).isEqualTo(DENY)
        }

    @Test
    fun `INV-AC-919 withdrawal stops the agent and produces the handover statement, the statement part`() =
        run { env ->
            val consent = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            val withdrawn = env.withdraw(consent.consentRef)
            assertThat(withdrawn.status).isEqualTo(ConsentResponse.Status.WITHDRAWN)
            assertThat(env.decide("CLI-A", replay)).isEqualTo(DENY)
            assertThat(env.statement(checkNotNull(withdrawn.handoverStatementRef))).isNotNull()
        }

    @Test
    fun `INV-AC-8608 withdrawConsent lists the unsubmitted documents with due date and blocking reason`() {
        val documents =
            listOf(
                UnsubmittedDocument("DOC-1", LocalDate.of(2026, 11, 30), "MISSING_BUYER_TIN"),
                UnsubmittedDocument("DOC-2", LocalDate.of(2026, 12, 15), "VALIDATION_FAILED"),
            )
        run(UnsubmittedDocuments { DocumentList.Listed(documents) }) { env ->
            val consent = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            val withdrawn = env.withdraw(consent.consentRef)
            val ref = checkNotNull(withdrawn.handoverStatementRef)
            assertThat(env.statement(ref)?.documents).isEqualTo(DocumentList.Listed(documents))
            // a repeat is a no-op that returns the same statement reference
            assertThat(env.withdraw(consent.consentRef).handoverStatementRef).isEqualTo(ref)
        }
    }

    @Test
    fun `INV-AC-8608 with the document-list port unbound the statement names the list as unavailable`() =
        run { env ->
            val consent = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            val ref = checkNotNull(env.withdraw(consent.consentRef).handoverStatementRef)
            assertThat(env.statement(ref)?.documents).isEqualTo(DocumentList.Unavailable)
        }

    @Test
    fun `INV-AC-8608 a throwing document-list port still withdraws, names the list unavailable, logs and counts`() {
        var calls = 0
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(ConsentUseCases::class.java) as Logger
        logger.addAppender(appender)
        try {
            run(
                UnsubmittedDocuments {
                    calls++
                    error("documents are down")
                },
            ) { env ->
                val counter = {
                    env.context
                        .getBean(
                            MeterRegistry::class.java,
                        ).counter(HANDOVER_DOCUMENTS_FAILED_METER)
                }
                val consent = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
                val withdrawn = env.withdraw(consent.consentRef)
                // 1: withdrawn with the reference, list unavailable, agent denied, one WARN, counter +1
                assertThat(withdrawn.status).isEqualTo(ConsentResponse.Status.WITHDRAWN)
                val ref = checkNotNull(withdrawn.handoverStatementRef)
                assertThat(env.statement(ref)?.documents).isEqualTo(DocumentList.Unavailable)
                assertThat(env.decide("CLI-A", replay)).isEqualTo(DENY)
                val warnings = appender.list.filter { it.level == Level.WARN }
                assertThat(warnings).hasSize(1)
                assertThat(warnings.single().formattedMessage)
                    .contains(consent.consentRef)
                    .contains("documents are down")
                assertThat(counter().count()).isEqualTo(1.0)
                assertThat(calls).isEqualTo(1)
                // 2: a repeat returns the same reference, calls the port zero times, leaves the counter unchanged
                assertThat(env.withdraw(consent.consentRef).handoverStatementRef).isEqualTo(ref)
                assertThat(calls).isEqualTo(1)
                assertThat(counter().count()).isEqualTo(1.0)
                assertThat(appender.list.count { it.level == Level.WARN }).isEqualTo(1)
            }
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `INV-AC-8608 the unbound port writes no WARN event and leaves the counter unchanged`() {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(ConsentUseCases::class.java) as Logger
        logger.addAppender(appender)
        try {
            run { env ->
                val consent = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
                env.withdraw(consent.consentRef)
                assertThat(appender.list.filter { it.level == Level.WARN }).isEmpty()
                assertThat(
                    env.context
                        .getBean(MeterRegistry::class.java)
                        .counter(HANDOVER_DOCUMENTS_FAILED_METER)
                        .count(),
                ).isEqualTo(0.0)
            }
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `only the client signatory of the entity grants or withdraws a consent`() =
        run { env ->
            // the agent cannot consent for itself, and a signatory of client A cannot consent for client B
            val agentOwn = refusal { env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA), asUser = agent) }
            assertThat(agentOwn).isNotNull()
            val other = SeedSpec("user:other", Role.CLIENT_SIGNATORY, Scope.Entity(EntityRef("CLI-B")))
            Seeds.grant(env.context, env.tenant, other)
            val wrongClient =
                refusal { env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA), asUser = "user:other") }
            assertThat(wrongClient).isNotNull()
            assertThat(env.liveConsent("CLI-A")).isNull()
            val consent = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            assertThat(refusal { env.withdraw(consent.consentRef, asUser = agent) }).isNotNull()
            assertThat(env.liveConsent("CLI-A")).isNotNull()
            assertThat(refusal { env.withdraw("CNS-UNKNOWN") }).hasMessageContaining("SRT-")
        }

    private fun ApiRefusal.problem() = code.code to detail

    private fun eventRows(tenant: TenantId): Int =
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement(
                    "select count(*) from access.outbox where tenant_id = ? and type like '%AuthorityChanged%'",
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(tenant.toString()))
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "count returned no row" }
                        rows.getInt(1)
                    }
                }
        }

    @Test
    fun `a second grantConsent for a live client is 403 SRT-004 with the ruled detail and records nothing`() =
        run { env ->
            val first = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            val rows = env.consentRows(env.tenant)
            val events = eventRows(env.tenant)
            val detail = "the client entity already has a live consent; withdraw it before recording another"
            // other items, and another agent with the same items: both refused alike
            val otherItems = refusal { env.grant("CLI-A", setOf(ScopeItem.FILE_GST_RETURN)) }
            assertThat((otherItems as ApiRefusal).problem()).isEqualTo("SRT-004" to detail)
            assertThat(env.consentRows(env.tenant)).isEqualTo(rows)
            assertThat(eventRows(env.tenant)).isEqualTo(events)
            // a repeat with the same client, agent and items answers 200 with the original ref, no new row, no event
            val repeat =
                env.tenants.bind(env.tenant, signatory) {
                    env.api.grantConsent(
                        UUID.randomUUID(),
                        GrantConsentRequest(
                            "CLI-A",
                            agent,
                            LocalDate.of(2026, 1, 1),
                            "director@client.example",
                            submitInvoiceData = true,
                        ),
                    )
                }
            assertThat(repeat.statusCode.value()).isEqualTo(200)
            assertThat(repeat.body!!.consentRef).isEqualTo(first.consentRef)
            assertThat(env.consentRows(env.tenant)).isEqualTo(rows)
            assertThat(eventRows(env.tenant)).isEqualTo(events)
            assertThat(checkNotNull(env.liveConsent("CLI-A")).items).containsExactly(ScopeItem.SUBMIT_INVOICE_DATA)
        }

    @Test
    fun `a caller who is not the client signatory gets 403 SRT-004 with the default detail for both operations`() =
        run { env ->
            val consent = env.grant("CLI-A", setOf(ScopeItem.SUBMIT_INVOICE_DATA))
            val grant =
                refusal {
                    env.grant(
                        "CLI-B",
                        setOf(ScopeItem.SUBMIT_INVOICE_DATA),
                        asUser = agent,
                    )
                } as ApiRefusal
            val withdraw = refusal { env.withdraw(consent.consentRef, asUser = agent) } as ApiRefusal
            listOf(grant, withdraw).forEach {
                assertThat(it.code.code).isEqualTo("SRT-004")
                assertThat(it.detail).isNull()
                assertThat(it.message).doesNotContainIgnoringCase("consent").doesNotContain(agent, signatory)
            }
            assertThat(env.liveConsent("CLI-B")).isNull()
            assertThat(env.liveConsent("CLI-A")).isNotNull()
        }

    @Test
    fun `withdrawConsent of an unknown consentRef is still the 404 refusal`() =
        run { env ->
            val unknown = refusal { env.withdraw("CNS-UNKNOWN") } as ApiRefusal
            assertThat(unknown.code).isEqualTo(ClosedErrorCode.NOT_FOUND)
        }
}
