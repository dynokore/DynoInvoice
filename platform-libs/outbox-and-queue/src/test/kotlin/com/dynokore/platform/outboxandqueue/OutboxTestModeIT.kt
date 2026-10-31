package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.util.UUID

/**
 * The test-mode attribute through the outbox (INV-Q-12635, INV-REQ-829, INV-AC-12638), on PostgreSQL 17 and 18: the
 * writer stores `testmode`, the relay carries it to the event log record, a replay keeps it, and
 * [OutboxSchema.testModeUpgradeDdl] is idempotent.
 */
@Tag("integration")
class OutboxTestModeIT {
    private val log = "eventlog.gst_payload_payload_ready_v1"
    private val replayTopic = "gst.canonical.replay-reconciliation.v1"

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-12638 the writer stores testmode and the relay carries it to the event log record`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val tenant = UUID.randomUUID()
            val written = mutableListOf<Envelope>()
            for (test in listOf(true, false)) {
                written += kit.inTx(tenant) { kit.writer.append(listOf(event(test)), context(tenant, test)) }
            }
            assertEquals(listOf(true, false), written.map { it.testMode })
            assertEquals(1, harness.count("select count(*) from payload.outbox where testmode"))
            assertEquals(
                1,
                harness.count("select count(*) from payload.outbox where not testmode and data->>'t' = 'false'"),
            )
            harness.drain(kit.relay)

            val wire = harness.wire(log)
            val marked = wire.single { it.get("data").get("t").asBoolean() }
            val plain = wire.single { !it.get("data").get("t").asBoolean() }
            assertTrue(marked.get("testmode").isBoolean && marked.get("testmode").asBoolean(), "wire: $marked")
            assertFalse(plain.has("testmode"), "wire: $plain")
            assertEquals(
                written,
                harness.logOf("gst.payload.payload-ready.v1").sortedBy { it.time }.let { logged ->
                    written.map { w -> logged.single { it.id == w.id } }
                },
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-12638 a replay keeps the state's testmode and the replay marker stays false`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.canonical
            val tenant = TenantId(UUID.randomUUID())
            val states =
                listOf(true, false).map { test ->
                    val doc = UUID.randomUUID()
                    ReplayedState(
                        TestCatalogue.CANONICAL_VERSION_CREATED,
                        """{"version":1}""",
                        EntityRef("entity-1"),
                        doc.toString(),
                        1,
                        DocUuid(doc),
                        LocalDate.of(2026, 9, 30),
                        testMode = test,
                    )
                }
            val replayed =
                kit.inTx(tenant.value) {
                    kit.writer.appendReplayed(replayTopic, states, tenant, CorrelationId("c"), CausationId("k"))
                }
            assertEquals(listOf(true, false), replayed.map { it.testMode })
            assertEquals(1, harness.count("select count(*) from canonical.outbox where testmode"))
            val marker =
                kit.inPlatformTx {
                    kit.writer.appendCompleted(
                        replayTopic,
                        UUID.randomUUID(),
                        2,
                        CorrelationId("c"),
                        CausationId("k"),
                    )
                }
            assertFalse(marker.testMode)
            assertEquals(1, harness.count("select count(*) from canonical.outbox where testmode"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-12638 the upgrade adds the column once and changes nothing on a schema made by ddl`(image: String) {
        OutboxHarness(image).use { harness ->
            val fixture = harness.fixture
            fixture.runAsAdmin(
                "create schema legacy; create table legacy.outbox (seq bigint); insert into legacy.outbox values (1)",
            )
            assertEquals(null, column(harness, "legacy"))
            val upgrade = OutboxSchema.testModeUpgradeDdl("legacy")
            fixture.runAsAdmin(upgrade)
            val after = column(harness, "legacy")
            assertEquals("boolean/false/NO", after)
            assertEquals(1, harness.count("select count(*) from legacy.outbox where testmode = false"))
            val columnsAfterFirst = columns(harness, "legacy")
            fixture.runAsAdmin(upgrade)
            assertEquals(after, column(harness, "legacy"))
            assertEquals(columnsAfterFirst, columns(harness, "legacy"))

            val before = columns(harness, "payload")
            val made = column(harness, "payload")
            assertEquals("boolean/false/NO", made)
            fixture.runAsAdmin(OutboxSchema.testModeUpgradeDdl("payload"))
            assertEquals(before, columns(harness, "payload"))
            assertEquals(made, column(harness, "payload"))
        }
    }

    /** `type/default/nullable` of `<schema>.outbox.testmode`, or null when the column is absent. */
    private fun column(
        harness: OutboxHarness,
        schema: String,
    ): String? =
        query(
            harness,
            "select data_type || '/' || column_default || '/' || is_nullable from information_schema.columns " +
                "where table_schema = ? and table_name = 'outbox' and column_name = 'testmode'",
            schema,
        ).singleOrNull()

    /** The column names of `<schema>.outbox` in order. */
    private fun columns(
        harness: OutboxHarness,
        schema: String,
    ): List<String> =
        query(
            harness,
            "select column_name from information_schema.columns where table_schema = ? " +
                "and table_name = 'outbox' order by ordinal_position",
            schema,
        )

    private fun query(
        harness: OutboxHarness,
        sql: String,
        schema: String,
    ): List<String> =
        harness.fixture.adminDataSource().connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, schema)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
        }

    @org.junit.jupiter.api.Test
    fun `INV-AC-12638 the upgrade text is exact and refuses what ddl refuses`() {
        assertEquals(
            "alter table svc.outbox add column if not exists testmode boolean not null default false",
            OutboxSchema.testModeUpgradeDdl("svc"),
        )
        val bad = "svc; drop table x"
        val expected = assertThrows(Exception::class.java) { OutboxSchema.ddl(bad, "a", "b") }
        val actual = assertThrows(Exception::class.java) { OutboxSchema.testModeUpgradeDdl(bad) }
        assertEquals(expected::class, actual::class)
        assertThrows(expected::class.java) { OutboxSchema.testModeUpgradeDdl("") }
    }

    @org.junit.jupiter.api.Test
    fun `INV-AC-12638 the constructors end with the testMode property`() {
        // Positional construction: the new property is the last one and defaults to false.
        val context =
            EventContext(
                TenantId.PLATFORM,
                EntityRef.PLATFORM,
                null,
                null,
                "k",
                null,
                CorrelationId("c"),
                CausationId("k"),
            )
        assertFalse(context.testMode)
        assertTrue(context.copy(testMode = true).testMode)
        val state = ReplayedState("t", "{}", EntityRef.PLATFORM, "k", null, null, null)
        assertFalse(state.testMode)
        assertEquals(
            listOf(
                "type",
                "dataJson",
                "entity",
                "aggregateKey",
                "docSequence",
                "docUuid",
                "transactionDate",
                "testMode",
            ),
            ReplayedState::class.java.declaredFields
                .map { it.name }
                .filter { it != "Companion" },
        )
        assertEquals(
            "testMode",
            EventContext::class.java.declaredFields
                .map { it.name }
                .last { it != "Companion" },
        )
    }

    private fun event(test: Boolean) = OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"t":$test}""")

    private fun context(
        tenant: UUID,
        test: Boolean,
    ) = EventContext(
        tenant = TenantId(tenant),
        entity = EntityRef("entity-1"),
        docUuid = null,
        transactionDate = LocalDate.of(2026, 9, 30),
        aggregateKey = "job-${UUID.randomUUID()}",
        aggregateVersion = 1,
        correlation = CorrelationId("corr-$tenant"),
        causation = CausationId("cmd-$tenant"),
        testMode = test,
    )
}
