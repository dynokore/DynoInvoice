package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.http.LookupController
import com.dynokore.gst.evidence.adapters.http.LookupRefusal
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqAcknowledgementLocatorStore
import com.dynokore.gst.evidence.application.auditedread.AuditedReads
import com.dynokore.gst.evidence.application.integrity.IntegrityFailureRow
import com.dynokore.gst.evidence.application.integrity.IntegrityRunRow
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.application.lookup.AcknowledgementChainLookup
import com.dynokore.gst.evidence.application.lookup.ChainLookupResult
import com.dynokore.gst.evidence.application.lookup.ENQUIRY_LOOKUP_BY_ACKNOWLEDGEMENT
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-20576: the beans of [LookupConfiguration] compose the controller and the use case; the database is a jOOQ
 * mock that records the SQL, the configuration methods are called directly (precedent AcknowledgementWiringTest).
 */
class LookupWiringTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val configuration = LookupConfiguration()

    private class Recorded {
        val sql = mutableListOf<String>()
    }

    private fun dsl(recorded: Recorded) =
        DSL.using(
            MockConnection(
                MockDataProvider { ctx ->
                    recorded.sql += ctx.sql()
                    // A1 is stored in the locator table; every other table is empty, so the reader finds no record
                    val create = DSL.using(SQLDialect.POSTGRES)
                    if (ctx.sql().contains("acknowledgement_locator")) {
                        val ack = DSL.field(DSL.name("ack_id"), String::class.java)
                        val doc = DSL.field(DSL.name("doc_uuid"), UUID::class.java)
                        val version = DSL.field(DSL.name("version"), Int::class.javaObjectType)
                        val result = create.newResult(ack, doc, version)
                        result.add(create.newRecord(ack, doc, version).values("A1", UUID.randomUUID(), 2))
                        arrayOf(MockResult(1, result))
                    } else {
                        arrayOf(MockResult(0, create.newResult()))
                    }
                },
            ),
            SQLDialect.POSTGRES,
        )

    private val noIntegrity =
        object : IntegrityStore {
            override fun lock(
                tenant: TenantId,
                entityRef: String,
                period: String,
            ) = error("not used")

            override fun failureExists(
                tenant: TenantId,
                row: IntegrityFailureRow,
            ): Boolean = error("not used")

            override fun insertFailure(
                tenant: TenantId,
                row: IntegrityFailureRow,
            ) = error("not used")

            override fun insertRun(
                tenant: TenantId,
                row: IntegrityRunRow,
            ) = error("not used")

            override fun latestRun(
                tenant: TenantId,
                entityRef: String,
                period: String,
            ): IntegrityRunRow? = null
        }

    private val operations =
        object : AuditedOperations {
            override fun <T> change(
                entry: AuditEntry,
                context: EventContext,
                block: () -> T,
            ): T = error("not used")

            override fun <T> read(
                entry: AuditEntry,
                context: EventContext,
                block: () -> T,
            ): T = block()
        }

    private fun replica(): AuthorityReplica {
        val roles =
            OperationRoleMap {
                if (it == ENQUIRY_LOOKUP_BY_ACKNOWLEDGEMENT) mapOf("auditor" to CellAllowance.PERMITTED) else emptyMap()
            }
        val unavailable = ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
        val replica = AuthorityReplica(clock, roles, fallback = unavailable)
        val grant =
            RoleGrant(
                "auditor",
                GrantScope.TENANT,
                tenant.value.toString(),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
            )
        replica.accept(
            AuthorityChanged(tenant, "user-42", 1, AuthorityRecord.RoleGrantRecord("g1", RoleGrantState.ACTIVE, grant)),
        )
        replica.onSnapshotComplete(1)
        replica.observeHead(1)
        return replica
    }

    private val context =
        EventContext(
            tenant,
            EntityRef.TENANT_WIDE,
            null,
            null,
            "A1",
            null,
            com.dynokore.platform.outboxandqueue
                .CorrelationId("c"),
            com.dynokore.platform.outboxandqueue
                .CausationId("c"),
        )

    private val principal = Principal("user-42", PrincipalKind.HUMAN)

    @Test
    fun `INV-AC-20576 the use case bean behaves as the one built by hand and reads through chainRecordReader`() {
        val replica = replica()
        val viaConfiguration = Recorded()
        val byHand = Recorded()
        val audited = configuration.auditedReads(operations)
        val bean =
            configuration.acknowledgementChainLookup(
                replica,
                dsl(viaConfiguration),
                IntegrityStatusQueryHandler(noIntegrity),
                audited,
            )
        val handDsl = dsl(byHand)
        val manual =
            AcknowledgementChainLookup(
                replica,
                JooqAcknowledgementLocatorStore(handDsl),
                ChainWiring.chainRecordReader(handDsl),
                IntegrityStatusQueryHandler(noIntegrity),
                AuditedReads(operations),
            )
        assertThat(bean.lookup(tenant, principal, "A1", 100, null, context)).isEqualTo(ChainLookupResult.Incomplete)
        assertThat(manual.lookup(tenant, principal, "A1", 100, null, context)).isEqualTo(ChainLookupResult.Incomplete)
        assertThat(viaConfiguration.sql).hasSizeGreaterThan(1).isEqualTo(byHand.sql)
        assertThat(viaConfiguration.sql.first()).contains("acknowledgement_locator")
        // the reader built by chainRecordReader reads the sealed payload link of the version
        assertThat(viaConfiguration.sql.drop(1)).anyMatch { it.contains("sealed_payload") }
    }

    @Test
    fun `INV-AC-20576 the controller is built with the use case bean and serves the operation`() {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute")
                EndUserPrincipal("user-42", tenant, emptySet()).takeIf {
                    args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE
                }
            } as HttpServletRequest
        val recorded = Recorded()
        val bean =
            configuration.acknowledgementChainLookup(
                replica(),
                dsl(recorded),
                IntegrityStatusQueryHandler(noIntegrity),
                configuration.auditedReads(operations),
            )
        val controller =
            LookupController(
                bean,
                object : ObjectProvider<HttpServletRequest> {
                    override fun getObject(): HttpServletRequest = request
                },
            )
        // the locator is found and the reader finds no record: Incomplete, the 500 refusal, never an empty 200
        assertThatThrownBy {
            controller.getAcknowledgementChain(
                "A1",
                100,
                null,
            )
        }.isInstanceOf(LookupRefusal::class.java)
        assertThat(recorded.sql).hasSizeGreaterThan(1)
    }
}
