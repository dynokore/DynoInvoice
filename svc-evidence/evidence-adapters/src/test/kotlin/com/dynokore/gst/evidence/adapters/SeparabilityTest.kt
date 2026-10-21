package com.dynokore.gst.evidence.adapters

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * Separability of the evidence store (INV-REQ-636, INV-ADR-269, INV-AC-7871): no foreign key, view, function or
 * trigger of schema `evidence` refers to an object of another schema. The catalogue test runs over the real migrations
 * and, once each and never committed, over a scratch migration that adds a foreign key to a table of schema
 * `operational_probe`, a view over such a table, and a reference to the look-alike schema `evidence_x`.
 */
class SeparabilityTest {
    private val allowed = setOf("evidence", "pg_catalog")

    private val foreign =
        """
        create schema operational_probe;
        create table operational_probe.item (id uuid primary key, tenant_id uuid not null);
        grant usage on schema operational_probe to evidence_migrate;
        grant references, select on operational_probe.item to evidence_migrate;
        create function operational_probe.noop() returns trigger language plpgsql as $$ begin return new; end $$;
        grant execute on function operational_probe.noop() to evidence_migrate;
        create schema evidence_x;
        create table evidence_x.item (id uuid primary key, tenant_id uuid not null);
        grant usage on schema evidence_x to evidence_migrate;
        grant references, select on evidence_x.item to evidence_migrate;
        """.trimIndent()

    /** The objects the Flyway migrations create (their role owns them), not the ones the installer provisions. */
    private fun migrated(references: List<SchemaReferences.Reference>) =
        references.filter { it.owner == EvidenceDb.MIGRATE }

    private fun outside(probe: String?): List<SchemaReferences.Reference> =
        EvidenceDb.withScratchDatabase(foreign, probe) { url ->
            DriverManager.getConnection(url, EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD).use {
                SchemaReferences.outside(migrated(SchemaReferences.references(it, EvidenceDb.SCHEMA)), allowed)
            }
        }

    @Test
    fun `INV-AC-7871 the unmodified migrations refer only to the schemas evidence and pg_catalog`() {
        EvidenceDb.superuser().use { connection ->
            val references = migrated(SchemaReferences.references(connection, EvidenceDb.SCHEMA))
            references.shouldNotBeEmpty()
            SchemaReferences.outside(references, allowed).shouldBeEmpty()
            references.map { it.toSchema }.toSet() shouldBe setOf("evidence")
        }
    }

    /**
     * The binding functions of tenancy-kernel's BindingKeyMigration.ddl are created by the installer (the sys role),
     * not by the migrations, and their bodies call pgcrypto's `public.hmac`. They are the only objects of the schema
     * that name another schema. This pins that fact; whether the criterion covers them is a gap reported in the MR.
     */
    @Test
    fun `INV-AC-7871 the installer provisioned binding functions are the only objects naming another schema`() {
        EvidenceDb.superuser().use { connection ->
            val others =
                SchemaReferences
                    .outside(SchemaReferences.references(connection, EvidenceDb.SCHEMA), allowed)
                    .filter { it.owner != EvidenceDb.MIGRATE }
            others.map { it.owner }.toSet() shouldBe setOf(EvidenceDb.SYS)
            others.map { it.toSchema }.toSet() shouldBe setOf("public")
            others.map { it.from }.toSet() shouldBe setOf("function binding_verified", "function bound_tenant")
        }
    }

    @Test
    fun `INV-AC-7871 a foreign key to a table of operational_probe fails the catalogue test`() {
        val found =
            outside(
                "create table evidence.probe (id uuid primary key, tenant_id uuid not null, " +
                    "item_id uuid references operational_probe.item (id));",
            )
        found.map { it.toSchema }.toSet() shouldBe setOf("operational_probe")
        found.map { it.kind }.toSet().contains("foreign key") shouldBe true
    }

    @Test
    fun `INV-AC-7871 a view over a table of operational_probe fails the catalogue test`() {
        val found = outside("create view evidence.probe_view as select id from operational_probe.item;")
        found.map { it.toSchema }.toSet() shouldBe setOf("operational_probe")
    }

    @Test
    fun `INV-AC-7871 a reference to the look-alike schema evidence_x fails the catalogue test`() {
        val view = outside("create view evidence.probe_view as select id from evidence_x.item;")
        view.map { it.toSchema }.toSet() shouldBe setOf("evidence_x")
        val function =
            outside(
                "create function evidence.probe_fn() returns bigint language plpgsql as " +
                    "\$\$ begin return (select count(*) from evidence_x.item); end \$\$;",
            )
        function.map { it.toSchema }.toSet() shouldBe setOf("evidence_x")
    }

    @Test
    fun `INV-AC-7871 a trigger running a function of another schema fails the catalogue test`() {
        val found =
            outside(
                "create table evidence.probe (id uuid primary key, tenant_id uuid not null);" +
                    "create trigger probe_trigger before insert on evidence.probe " +
                    "for each row execute function operational_probe.noop();",
            )
        found.map { it.toSchema }.toSet() shouldBe setOf("operational_probe")
    }
}
