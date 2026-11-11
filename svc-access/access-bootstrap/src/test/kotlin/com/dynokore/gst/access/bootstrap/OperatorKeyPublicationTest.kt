package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.domain.revocation.KeyUse
import com.dynokore.gst.access.domain.revocation.VerificationKey
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * INV-T-4154: the atomic write of the operator-session key rows and of the OperatorSessionKeysChanged record, and the
 * advisory lock of the authority version draw, against the real database.
 */
class OperatorKeyPublicationTest {
    private val mapper = JsonMapper.builder().build()
    private val run = UUID.randomUUID().toString().take(8)
    private val typeFilter = "type like '%OperatorSessionKeysChanged%'"

    private fun withContext(block: (AssertableWebApplicationContext) -> Unit) {
        BoundaryFixture.deleteOperatorVerificationKeys()
        val mount = BoundaryFixture.mountOperator(TestSecrets.newSigningKey())
        BoundaryFixture
            .runner(catalogueMatrix = true, mounted = mount, requirements = null)
            .run { context ->
                context.startupFailure?.let { throw it }
                block(context)
            }
    }

    private fun count(sql: String): Int =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun outboxCount() = count("select count(*) from access.outbox where $typeFilter")

    private fun storedCount(kid: String) = count("select count(*) from access.verification_key where kid = '$kid'")

    private fun newKid(prefix: String) =
        prefix +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(15)

    private fun failingOutbox() =
        com.dynokore.gst.access.adapters.events.OperatorSessionKeysChangedOutbox(
            object : com.dynokore.platform.outboxandqueue.OutboxWriter {
                override fun append(
                    events: List<com.dynokore.platform.outboxandqueue.OutboundEvent>,
                    context: com.dynokore.platform.outboxandqueue.EventContext,
                ): List<com.dynokore.platform.outboxandqueue.Envelope> = error("forced outbox failure")
            },
            mapper,
        )

    private fun publicationWith(
        context: AssertableWebApplicationContext,
        events: com.dynokore.gst.access.adapters.events.OperatorSessionKeysChangedOutbox,
    ) = com.dynokore.gst.access.adapters.persistence.JooqOperatorKeyPublication(
        context.getBean(org.jooq.DSLContext::class.java),
        context.getBean(PlatformTenantBinding::class.java),
        context.getBean(TransactionRunner::class.java),
        events,
        com.dynokore.platform.serviceruntime.identity
            .WorkloadPrincipal(
                context.environment.getRequiredProperty("gst.service"),
            ).value,
    )

    private fun candidate(
        kid: String,
        use: KeyUse,
        at: Instant,
    ) = VerificationKey(
        kid,
        "ES256",
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-$kid",
        at,
        at.plus(Duration.ofHours(2)),
        use,
    )

    @Test
    fun `a forced failure of the outbox append rolls the key row back`() =
        withContext { context ->
            val before = outboxCount()
            val kid = newKid("f")
            val publication = publicationWith(context, failingOutbox())
            val failure =
                catchThrowable {
                    publication.publish(listOf(candidate(kid, KeyUse.OPERATOR_SESSION, Instant.now())))
                }
            assertThat(failure).hasMessageContaining("forced outbox failure")
            assertThat(storedCount(kid)).isZero()
            assertThat(outboxCount()).isEqualTo(before)
        }

    @Test
    fun `a forced failure of the key row insert leaves no outbox record and no row of the other candidate`() =
        withContext { context ->
            val now = Instant.now()
            val stored = newKid("e")
            val fresh = newKid("d")
            val real =
                publicationWith(
                    context,
                    context.getBean(
                        com.dynokore.gst.access.adapters.events.OperatorSessionKeysChangedOutbox::class.java,
                    ),
                )
            real.publish(listOf(candidate(stored, KeyUse.MACHINE_ACCESS, now)))
            val before = outboxCount()
            // the stored kid is asked for under another use: the call fails after the fresh row was inserted
            val failure =
                catchThrowable {
                    real.publish(
                        listOf(
                            candidate(fresh, KeyUse.OPERATOR_SESSION, now),
                            candidate(stored, KeyUse.OPERATOR_SESSION, now),
                        ),
                    )
                }
            assertThat(failure).isNotNull()
            assertThat(storedCount(stored)).isEqualTo(1)
            assertThat(storedCount(fresh)).isZero()
            assertThat(outboxCount()).isEqualTo(before)
        }

    @Test
    fun `the authority version draw holds an advisory lock on the operator until the transaction ends`() =
        withContext { context ->
            val versions =
                com.dynokore.gst.access.adapters.persistence
                    .JooqAuthorityVersions(context.getBean(org.jooq.DSLContext::class.java))
            val key = "user:lock-probe-$run"
            val probe =
                "select pg_try_advisory_xact_lock(" +
                    "${com.dynokore.gst.access.adapters.persistence.JooqAuthorityVersions.lockKey(key)})"

            fun tryLock(): Boolean =
                BoundaryFixture.asSuperuser { c ->
                    c.autoCommit = false
                    c
                        .createStatement()
                        .use { st -> st.executeQuery(probe).use { rs -> rs.next() && rs.getBoolean(1) } }
                        .also { c.rollback() }
                }
            val tenant = TenantId(UUID.randomUUID())
            context
                .getBean(
                    com.dynokore.platform.tenancykernel.TenantContext::class.java,
                ).bind(tenant, "user:lock-$run") {
                    context.getBean(TransactionRunner::class.java).inTransaction {
                        versions.next(key)
                        assertThat(tryLock()).isFalse()
                    }
                }
            assertThat(tryLock()).isTrue()
        }
}
