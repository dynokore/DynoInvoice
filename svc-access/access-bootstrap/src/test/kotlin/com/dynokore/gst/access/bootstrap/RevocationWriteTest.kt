package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.persistence.PlatformReads
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.REVOCATION_ENTRY
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.REVOCATION_HEAD
import com.dynokore.gst.access.application.principal.PrincipalRevocationPublisher
import com.dynokore.gst.access.contract.model.AuthenticatePrincipalResponse.Decision
import com.dynokore.gst.access.contract.model.PrincipalResponse
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Makes the tenant transaction of revokePrincipal fail after the entry write, while the flag is set. */
class InjectedFailure {
    val armed = AtomicBoolean(false)
}

/**
 * The revocation-list write of revokePrincipal (INV-Q-8785 A) and the AuthorityChanged events per entity (INV-Q-8787 A)
 * at the service boundary. The list is read with [PlatformReads], the way getRevocationList reads it.
 */
class RevocationWriteTest {
    private val injected = InjectedFailure()

    private fun run(block: (PrincipalKit) -> Unit) {
        BoundaryFixture
            .runner()
            .withBean(
                "failAfterEntryWrite",
                BeanPostProcessor::class.java,
                {
                    object : BeanPostProcessor {
                        override fun postProcessAfterInitialization(
                            bean: Any,
                            beanName: String,
                        ): Any =
                            if (bean is PrincipalRevocationPublisher) {
                                object : PrincipalRevocationPublisher {
                                    override fun publishRevoked(
                                        principal: ServicePrincipal,
                                        nextVersion: () -> Long,
                                    ) {
                                        check(!injected.armed.get()) { "injected failure in the tenant transaction" }
                                        bean.publishRevoked(principal, nextVersion)
                                    }
                                }
                            } else {
                                bean
                            }
                    }
                },
            ).run { context ->
                context.startupFailure?.let { throw it }
                block(PrincipalKit(context))
            }
    }

    private fun entriesOf(
        context: AssertableWebApplicationContext,
        ref: String,
    ): List<Long> =
        context.getBean(PlatformReads::class.java).read {
            context
                .getBean(DSLContext::class.java)
                .select(REVOCATION_ENTRY.VERSION)
                .from(REVOCATION_ENTRY)
                .where(REVOCATION_ENTRY.PRINCIPAL_REF.eq(ref), REVOCATION_ENTRY.KIND.eq("PRINCIPAL"))
                .fetch(REVOCATION_ENTRY.VERSION)
                .filterNotNull()
        }

    private fun head(context: AssertableWebApplicationContext): Long =
        context.getBean(PlatformReads::class.java).read {
            context
                .getBean(DSLContext::class.java)
                .select(REVOCATION_HEAD.VERSION)
                .from(REVOCATION_HEAD)
                .fetchOne(REVOCATION_HEAD.VERSION) ?: 0L
        }

    @Test
    fun `revokePrincipal writes one list entry and raises the head by one, a repeat changes neither`() =
        run { kit ->
            val created = kit.create("INGEST", scope = "entity:ENT-1")
            val before = head(kit.context)
            kit.revoke(created.principalRef)
            assertThat(entriesOf(kit.context, created.principalRef)).hasSize(1)
            assertThat(head(kit.context)).isEqualTo(before + 1)
            assertThat(entriesOf(kit.context, created.principalRef)).containsExactly(before + 1)
            kit.revoke(created.principalRef)
            assertThat(entriesOf(kit.context, created.principalRef)).hasSize(1)
            assertThat(head(kit.context)).isEqualTo(before + 1)
        }

    @Test
    fun `a failure in the tenant transaction after the entry write leaves the entry and the principal active`() =
        run { kit ->
            val key = kit.newKey("k")
            val created = kit.create("INGEST", scope = "entity:ENT-1", method = PrincipalKit.JWT, key = key)
            val ref = created.principalRef
            val before = head(kit.context)
            injected.armed.set(true)
            val failure = PrincipalKit.refusal { kit.revoke(ref) }
            assertThat(failure).contains("injected failure")
            assertThat(entriesOf(kit.context, ref)).hasSize(1)
            assertThat(head(kit.context)).isEqualTo(before + 1)
            // the principal is still ACTIVE: its own credential still authenticates here (the gateway refuses by list)
            val answer = kit.authenticate(ref, PrincipalKit.AUTH_JWT, kit.proof(key, ref))
            assertThat(answer.decision).isEqualTo(Decision.AUTHENTICATED)
            injected.armed.set(false)
            val repeated = kit.revoke(ref)
            assertThat(repeated.status).isEqualTo(PrincipalResponse.Status.REVOKED)
            assertThat(entriesOf(kit.context, ref)).hasSize(1)
            assertThat(head(kit.context)).isEqualTo(before + 1)
        }

    @Test
    fun `an insert of a platform row from a tenant-bound transaction is still refused`() =
        run { kit ->
            val refused =
                PrincipalKit.refusal {
                    kit.asAdmin {
                        kit.context.getBean(TransactionRunner::class.java).inTransaction {
                            kit.context
                                .getBean(DSLContext::class.java)
                                .execute(
                                    "insert into access.revocation_entry " +
                                        "(id, tenant_id, version, kind, principal_ref, revoked_at) " +
                                        "values (?, ?::uuid, 900000001, 'PRINCIPAL', 'PRN-FORGED', now())",
                                    UUID.randomUUID(),
                                    TenantId.PLATFORM.toString(),
                                )
                        }
                    }
                }
            assertThat(refused).describedAs("the policy refuses a platform row from a tenant binding").isNotNull()
            assertThat(entriesOf(kit.context, "PRN-FORGED")).isEmpty()
        }

    private fun revocationEntities(
        kit: PrincipalKit,
        block: () -> Unit,
    ): List<String> {
        val captured = kit.context.getBean(CapturedEvents::class.java)
        captured.envelopes.clear()
        block()
        val mapper = JsonMapper.builder().build()
        return captured.envelopes
            .filter { it.type == PlatformEventCatalogue.AUTHORITY_CHANGED.eventType }
            .filter { mapper.readTree(it.dataJson).get("changeKind").asString() == "REVOCATION" }
            .map { it.entity.value }
    }

    @Test
    fun `INV-AC-8616 a principal bound to three entities emits three events, one for each entity`() =
        run { kit ->
            listOf("ENT-1", "ENT-2", "ENT-3").forEach(kit::entity)
            val created = kit.create("INGEST", scope = "tenant:${kit.tenant}")
            val entities = revocationEntities(kit) { kit.revoke(created.principalRef) }
            assertThat(entities).hasSize(3)
            assertThat(entities.toSet()).containsExactlyInAnyOrder("ENT-1", "ENT-2", "ENT-3")
        }

    @Test
    fun `INV-AC-8616 a principal bound to one entity emits one event`() =
        run { kit ->
            listOf("ENT-1", "ENT-2").forEach(kit::entity)
            val created = kit.create("INGEST", scope = "entity:ENT-2")
            val entities = revocationEntities(kit) { kit.revoke(created.principalRef) }
            assertThat(entities).containsExactly("ENT-2")
        }

    @Test
    fun `INV-AC-8616 a tenant with no entity emits no event, the entry exists and the next call is refused`() =
        run { kit ->
            val key = kit.newKey("k")
            val created = kit.create("INGEST", scope = "tenant:${kit.tenant}", method = PrincipalKit.JWT, key = key)
            val ref = created.principalRef
            assertThat(kit.authenticate(ref, PrincipalKit.AUTH_JWT, kit.proof(key, ref)).decision)
                .isEqualTo(Decision.AUTHENTICATED)
            val entities = revocationEntities(kit) { kit.revoke(ref) }
            assertThat(entities).isEmpty()
            assertThat(entriesOf(kit.context, ref)).hasSize(1)
            assertThat(kit.authenticate(ref, PrincipalKit.AUTH_JWT, kit.proof(key, ref)).decision)
                .isEqualTo(Decision.REFUSED)
        }
}
