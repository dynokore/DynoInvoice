package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * The build-only stage call of [OutboxWriter] (coordinator ruling INV-Q-4254 A) and the sys role's `INSERT` on the
 * outbox it serves, on PostgreSQL 17 and 18: a staged row is the row `append` writes, and staging writes nothing; the
 * sys role inserts only rows its bound tenant's policy admits.
 */
@Tag("integration")
class OutboxStageIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a staged platform row is the row append writes, and staging writes nothing`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val context = platformContext("stage-${UUID.randomUUID()}")
            val event = OutboundEvent(TestCatalogue.AUDIT_ENTRY_RECORDED, """{"who":"operator"}""")

            val staged = kit.inPlatformTx { kit.writer.stage(listOf(event), context) }.single()
            assertEquals(0, harness.rows("payload.outbox", "subject", context.aggregateKey), "stage wrote a row")

            val written = kit.inPlatformTx { kit.writer.append(listOf(event), context) }.single()
            // Same clock, same context: only the UUIDv7's random bits differ.
            assertEquals(written, staged.envelope.copy(id = written.id))
            assertEquals("gst.platform.audit-entry-recorded.v1", staged.topic)
            assertEquals(
                1,
                harness.count(
                    "select count(*) from payload.outbox where id = ? and topic = ?",
                    written.id.value,
                    staged.topic,
                ),
            )
            assertEquals(null, staged.envelope.docSequence)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `stage refuses a domain event, a sequenced context and an uncatalogued type`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val platform = platformContext("stage-refused")
            val domain = OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}")
            assertThrows(IllegalArgumentException::class.java) {
                val document = kit.docContext(ServiceKit.TENANT, UUID.randomUUID())
                kit.inTx(ServiceKit.TENANT) { kit.writer.stage(listOf(domain), document) }
            }
            val audit = OutboundEvent(TestCatalogue.AUDIT_ENTRY_RECORDED, "{}")
            assertThrows(IllegalArgumentException::class.java) {
                kit.inPlatformTx { kit.writer.stage(listOf(audit), platform.copy(aggregateVersion = 1)) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                kit.inPlatformTx { kit.writer.stage(listOf(audit, domain), platform) }
            }
            // PolicyChanged is a platform type, but not in this test catalogue (INV-AC-1869).
            assertThrows(EmissionNotPermitted::class.java) {
                kit.inPlatformTx { kit.writer.stage(listOf(OutboundEvent(POLICY_CHANGED, "{}")), platform) }
            }
            assertEquals(0, harness.rows("payload.outbox", "subject", platform.aggregateKey))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the sys role inserts an outbox row only under its bound tenant`(image: String) {
        OutboxHarness(image).use { harness ->
            harness.fixture.adminDataSource().connection.use { c ->
                c.autoCommit = false
                c.createStatement().use { it.execute("set local role ${OutboxPostgresFixture.SYS}") }
                bindTenant(c, TenantId.PLATFORM.value.toString())
                assertEquals(1, insertAsSys(c, TenantId.PLATFORM.value, "platform-row"))
                // Bound to the platform tenant, another tenant's row is refused by the tenant policy.
                assertRefused(c) { insertAsSys(c, UUID.randomUUID(), "foreign-row") }
                // Nothing bound: no row at all.
                bindTenant(c, "")
                assertRefused(c) { insertAsSys(c, TenantId.PLATFORM.value, "unbound-row") }
                c.rollback()
            }
        }
    }

    private fun bindTenant(
        c: Connection,
        tenant: String,
    ) = c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
        it.setString(1, tenant)
        it.executeQuery().close()
    }

    private fun insertAsSys(
        c: Connection,
        tenant: UUID,
        id: String,
    ): Int =
        c
            .prepareStatement(
                "insert into payload.outbox (id, topic, type, source, subject, event_time, tenant_id, entity_ref, " +
                    "instance_id, correlation_id, causation_id, data) " +
                    "values (?, 'gst.platform.audit-entry-recorded.v1', ?, '/gst/payload-svc', 'k', now(), ?, ?, " +
                    "'i', 'c', 'c', '{}')",
            ).use {
                it.setString(1, id)
                it.setString(2, TestCatalogue.AUDIT_ENTRY_RECORDED)
                it.setObject(3, tenant)
                it.setString(4, EntityRef.PLATFORM.value)
                it.executeUpdate()
            }

    private fun assertRefused(
        c: Connection,
        block: () -> Unit,
    ) {
        val savepoint = c.setSavepoint()
        val failure = assertThrows(SQLException::class.java) { block() }
        assertEquals("42501", failure.sqlState, failure.message)
        c.rollback(savepoint)
    }

    private fun platformContext(key: String) =
        EventContext(
            tenant = TenantId.PLATFORM,
            entity = EntityRef.PLATFORM,
            docUuid = null,
            transactionDate = null,
            aggregateKey = key,
            aggregateVersion = null,
            correlation = CorrelationId("corr-$key"),
            causation = CausationId("cmd-$key"),
        )

    private companion object {
        const val POLICY_CHANGED = "com.dynokore.gst.platform.PolicyChanged.v1"
    }
}
