package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.persistence.JooqHeldDocuments
import com.dynokore.gst.masterdata.application.held.HeldDocumentFact
import com.dynokore.gst.masterdata.application.held.HeldDocumentRow
import com.dynokore.gst.masterdata.application.held.HeldDocumentRule
import com.dynokore.gst.masterdata.application.held.HeldKind
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * INV-AC-21332 (INV-T-21333): the `masterdata.held_document` table and [JooqHeldDocuments] over real PostgreSQL with
 * the runtime role. The adapters module has no database fixture, so the test sits here.
 */
class HeldDocumentsStoreTest {
    private val d1 = uuid(1)
    private val d2 = uuid(2)
    private val d3 = uuid(3)
    private val d4 = uuid(4)
    private val d5 = uuid(5)
    private val d6 = uuid(6)

    private fun uuid(n: Int) = UUID.fromString("00000000-0000-0000-0000-" + n.toString().padStart(UUID_TAIL, '0'))

    private fun held(
        d: UUID,
        seq: Long,
        entity: String,
        party: String?,
    ) = HeldDocumentFact(HeldKind.HELD, d, seq, entity, party)

    private fun released(
        d: UUID,
        seq: Long,
    ) = HeldDocumentFact(HeldKind.RELEASED, d, seq, null, null)

    private val firstOrder =
        listOf(
            held(d3, 1, "E1", "P1"),
            held(d1, 1, "E1", "P1"),
            held(d2, 1, "E1", "P2"),
            held(d4, 1, "E2", "P1"),
            held(d5, 1, "E1", null),
            held(d3, 1, "E1", "P1"),
            released(d1, 2),
            held(d1, 1, "E1", "P1"),
        )

    private fun on(block: (CounterpartyEnv, DSLContext) -> Unit) =
        runRegistry { env -> block(env.ce, env.context.getBean(DSLContext::class.java)) }

    private fun CounterpartyEnv.apply(
        tenant: TenantId,
        facts: List<HeldDocumentFact>,
    ) {
        val dsl = context.getBean(DSLContext::class.java)
        tenants.bind(tenant, OPERATOR) {
            context.getBean(TransactionRunner::class.java).inTransaction {
                val store = JooqHeldDocuments(dsl)
                facts.forEach(store::apply)
            }
        }
    }

    private fun CounterpartyEnv.heldFor(
        tenant: TenantId,
        entity: String,
        party: String,
    ): List<UUID> =
        tenants.bind(tenant, OPERATOR) {
            context.getBean(TransactionRunner::class.java).inTransaction {
                JooqHeldDocuments(context.getBean(DSLContext::class.java)).heldFor(entity, PartyRef(party))
            }
        }

    /** The rows of [tenant] by document, read as the superuser so that every row is seen. */
    private fun rows(tenant: TenantId): Map<UUID, HeldDocumentRow> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select doc_uuid, docsequence, held, entity, party_ref from masterdata.held_document " +
                        "where tenant_id = ?::uuid",
                ).use { s ->
                    s.setString(1, tenant.value.toString())
                    s.executeQuery().use { rs ->
                        buildMap {
                            while (rs.next()) {
                                val doc = rs.getObject(1, UUID::class.java)
                                put(
                                    doc,
                                    HeldDocumentRow(
                                        doc,
                                        rs.getLong(2),
                                        rs.getBoolean(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    private fun scalar(sql: String): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add((1..rs.metaData.columnCount).joinToString("|") { rs.getString(it) })
                }
            }
        }

    @Test
    fun `INV-AC-21332 heldFor lists one entity and party in ascending UUID text order and isolates tenants`() =
        on { env, _ ->
            val t1 = TenantId(UUID.randomUUID())
            val t2 = TenantId(UUID.randomUUID())
            env.apply(t1, firstOrder)
            env.apply(t2, listOf(held(d1, 1, "E1", "P1")))
            assertThat(env.heldFor(t1, "E1", "P1")).containsExactly(d3)
            assertThat(env.heldFor(t1, "E1", "P2")).containsExactly(d2)
            assertThat(env.heldFor(t1, "E2", "P1")).containsExactly(d4)
            assertThat(env.heldFor(t2, "E1", "P1")).containsExactly(d1)
            assertThat(env.heldFor(t2, "E1", "P2")).isEmpty()
            val tenant = TenantId(UUID.randomUUID())
            env.apply(tenant, listOf(held(d3, 1, "E1", "P1"), held(d1, 1, "E1", "P1"), held(d2, 1, "E1", "P1")))
            assertThat(env.heldFor(tenant, "E1", "P1")).containsExactly(d1, d2, d3)
        }

    @Test
    fun `INV-AC-21332 one row per document, a tombstone after release, stale and repeated facts leave the row`() =
        on { env, _ ->
            val t1 = TenantId(UUID.randomUUID())
            env.apply(t1, firstOrder.take(5))
            val repeated = rows(t1).getValue(d3)
            env.apply(t1, listOf(firstOrder[5]))
            assertThat(rows(t1).getValue(d3)).isEqualTo(repeated)
            env.apply(t1, listOf(released(d1, 2)))
            assertThat(rows(t1).getValue(d1)).isEqualTo(HeldDocumentRow(d1, 2, false, "E1", "P1"))
            env.apply(t1, listOf(held(d1, 1, "E1", "P1")))
            assertThat(rows(t1).getValue(d1)).isEqualTo(HeldDocumentRow(d1, 2, false, "E1", "P1"))
            assertThat(rows(t1).keys).containsExactlyInAnyOrder(d1, d2, d3, d4)
            assertThat(rows(t1)).doesNotContainKey(d5)
            // a release on a row of another entity keeps the entity (it is not overwritten)
            env.apply(t1, listOf(released(d4, 2)))
            assertThat(rows(t1).getValue(d4)).isEqualTo(HeldDocumentRow(d4, 2, false, "E2", "P1"))
        }

    @Test
    fun `INV-AC-21332 the same facts in another order end in the same rows`() =
        on { env, _ ->
            val t1 = TenantId(UUID.randomUUID())
            val other = TenantId(UUID.randomUUID())
            env.apply(t1, firstOrder)
            env.apply(other, firstOrder.filter { it.docUuid != d5 }.reversed())
            assertThat(rows(other)).isEqualTo(rows(t1))
            assertThat(rows(other)).hasSize(4)
        }

    @Test
    fun `INV-AC-21332 a later hold without party clears the party and a hold without party on no row writes nothing`() =
        on { env, _ ->
            val t1 = TenantId(UUID.randomUUID())
            env.apply(t1, firstOrder)
            env.apply(t1, listOf(held(d2, 2, "E1", null), held(d6, 1, "E1", null)))
            assertThat(rows(t1).getValue(d2)).isEqualTo(HeldDocumentRow(d2, 2, true, "E1", null))
            assertThat(env.heldFor(t1, "E1", "P2")).isEmpty()
            assertThat(rows(t1)).doesNotContainKeys(d5, d6)
            // an equal or lower sequence does not clear the party again or restore it
            env.apply(t1, listOf(held(d2, 2, "E1", "P2"), held(d2, 1, "E1", "P2")))
            assertThat(rows(t1).getValue(d2)).isEqualTo(HeldDocumentRow(d2, 2, true, "E1", null))
        }

    @Test
    fun `INV-AC-21332 the store gives the result of HeldDocumentRule for every sequence of facts`() =
        on { env, _ ->
            val sequences =
                listOf(
                    firstOrder,
                    firstOrder.reversed(),
                    listOf(held(d6, 1, "E1", null), held(d6, 2, "E1", "P1"), held(d6, 3, "E1", null), released(d6, 4)),
                    listOf(released(d5, 3), held(d5, 2, "E2", "P2"), held(d5, 4, "E1", null), held(d5, 4, "E2", "P1")),
                    listOf(held(d4, 5, "E1", null), held(d4, 1, "E1", "P1"), released(d4, 2), held(d4, 6, "E2", "P2")),
                )
            sequences.forEach { facts ->
                val tenant = TenantId(UUID.randomUUID())
                env.apply(tenant, facts)
                val model = mutableMapOf<UUID, HeldDocumentRow>()
                facts.forEach { fact ->
                    HeldDocumentRule.next(model[fact.docUuid], fact)?.let { model[fact.docUuid] = it }
                }
                assertThat(rows(tenant)).isEqualTo(model)
            }
        }

    @Test
    fun `INV-AC-21332 a statement without a bound tenant reads and writes no row and the policy exists`() =
        on { env, _ ->
            val t1 = TenantId(UUID.randomUUID())
            env.apply(t1, firstOrder)
            assertThat(
                scalar(
                    "select relrowsecurity, relforcerowsecurity from pg_class " +
                        "where oid = 'masterdata.held_document'::regclass",
                ),
            ).containsExactly("t|t")
            assertThat(
                scalar(
                    "select policyname from pg_policies " +
                        "where schemaname = 'masterdata' and tablename = 'held_document'",
                ),
            ).containsExactly("tenant_isolation")
            BoundaryFixture.asRuntime { c ->
                c.createStatement().executeQuery("select count(*) from masterdata.held_document").use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isEqualTo(0)
                }
                assertThatThrownBy {
                    c.createStatement().execute(
                        "insert into masterdata.held_document (tenant_id, doc_uuid, docsequence, held) " +
                            "values ('${t1.value}'::uuid, gen_random_uuid(), 1, true)",
                    )
                }.hasMessageContaining("row-level security")
            }
        }

    @Test
    fun `INV-AC-21332 the runtime role holds select insert update and no delete and the key is tenant and document`() =
        on { _, _ ->
            assertThat(
                scalar(
                    "select privilege_type from information_schema.role_table_grants " +
                        "where grantee = 'masterdata_runtime' and table_schema = 'masterdata' " +
                        "and table_name = 'held_document'",
                ),
            ).containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE")
            assertThat(
                scalar(
                    "select a.attname from pg_index i join pg_attribute a " +
                        "on a.attrelid = i.indrelid and a.attnum = any(i.indkey) " +
                        "where i.indrelid = 'masterdata.held_document'::regclass and i.indisprimary",
                ),
            ).containsExactlyInAnyOrder("tenant_id", "doc_uuid")
        }

    @Test
    fun `INV-AC-21332 the migration holds the table, index, row-level security, policy and grant and nothing else`() {
        val text = Files.readString(MIGRATION)
        assertThat(text).matches("[\\x20-\\x7E\\n]+")
        val statements =
            text
                .lines()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(";")
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.isNotEmpty() }
        assertThat(statements.map { it.substringBefore(" (").substringBefore(" ON ").take(STATEMENT_HEAD) })
            .containsExactly(
                "CREATE TABLE masterdata.held_document",
                "CREATE INDEX held_document_party",
                "ALTER TABLE masterdata.held_document ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE masterdata.held_document FORCE ROW LEVEL SECURITY",
                "CREATE POLICY tenant_isolation",
                "GRANT SELECT, INSERT, UPDATE",
            )
    }

    private companion object {
        const val STATEMENT_HEAD = 80
        const val UUID_TAIL = 12
        val MIGRATION: Path =
            Path.of(
                "..",
                "masterdata-adapters",
                "src",
                "main",
                "resources",
                "db",
                "migration",
                "V202611121000__create_held_document.sql",
            )
    }
}
