package com.dynokore.gst.evidence.adapters

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection

/**
 * Separability of the evidence store (INV-REQ-636, INV-ADR-269, INV-AC-7871): no foreign key, view, function or
 * trigger of schema `evidence` refers to an object of another schema. The catalogue test runs over the real migrations
 * and, once each and never committed, over a scratch migration that adds a foreign key to a table of schema
 * `operational_probe`, a view over such a table, and a reference to the look-alike schema `evidence_x`. Every function
 * of the schema is checked; only the installer functions of [INSTALLER_BINDING_EXCEPTIONS] may call `public.hmac`.
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

    /**
     * The one exception of INV-AC-7871: the installer functions `binding_verified()` and `bound_tenant()` (empty
     * argument list, owner `evidence_sys`) call pgcrypto's `public.hmac`, and nothing else outside the allowed schemas.
     */
    private companion object {
        val INSTALLER_BINDING_EXCEPTIONS = setOf("function binding_verified()", "function bound_tenant()")
    }

    private fun excepted(reference: SchemaReferences.Reference) =
        reference.from in INSTALLER_BINDING_EXCEPTIONS &&
            reference.owner == EvidenceDb.SYS &&
            reference.kind == "function body" &&
            reference.toSchema == "public" &&
            reference.target == "hmac"

    /** Every reference of every object of the schema outside [allowed], and the named functions missing or misowned. */
    private fun findings(connection: Connection): List<String> {
        val functions = SchemaReferences.functions(connection, EvidenceDb.SCHEMA)
        val missing =
            INSTALLER_BINDING_EXCEPTIONS
                .filter { functions[it] != EvidenceDb.SYS }
                .map { "installer function $it is missing or not owned by ${EvidenceDb.SYS}" }
        val outside =
            SchemaReferences
                .outside(SchemaReferences.references(connection, EvidenceDb.SCHEMA), allowed)
                .filterNot { excepted(it) }
                .map { "${it.from} (${it.kind}, owner ${it.owner}) refers to ${it.toSchema}.${it.target}" }
        return missing + outside
    }

    /** The findings after the real migrations, [probe] as a scratch migration and [change] run as superuser. */
    private fun probed(
        probe: String? = null,
        change: (Connection) -> Unit = {},
    ): List<String> =
        EvidenceDb.withScratchDatabase(foreign, probe) { url ->
            EvidenceDb.superuserOf(url).use {
                change(it)
                findings(it)
            }
        }

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.definition(function: String): String =
        createStatement().use { statement ->
            statement.executeQuery("select pg_get_functiondef('evidence.$function'::regprocedure)").use {
                it.next()
                it.getString(1)
            }
        }

    @Test
    fun `INV-AC-7871 the unmodified installer text and migrations refer only to evidence and pg_catalog`() {
        EvidenceDb.superuser().use { connection ->
            findings(connection).shouldBeEmpty()
            val references = SchemaReferences.references(connection, EvidenceDb.SCHEMA)
            references.shouldNotBeEmpty()
            val others = SchemaReferences.outside(references, allowed)
            others.map { it.from }.toSet() shouldBe INSTALLER_BINDING_EXCEPTIONS
            others.all { excepted(it) } shouldBe true
        }
    }

    private fun List<String>.shouldName(vararg parts: String) {
        shouldNotBeEmpty()
        any { line -> parts.all { it in line } } shouldBe true
    }

    @Test
    fun `INV-AC-7871 a foreign key to a table of operational_probe fails the catalogue test`() {
        probed(
            "create table evidence.probe (id uuid primary key, tenant_id uuid not null, " +
                "item_id uuid references operational_probe.item (id));",
        ).shouldName("foreign key", "operational_probe")
    }

    @Test
    fun `INV-AC-7871 a view over a table of operational_probe fails the catalogue test`() {
        probed("create view evidence.probe_view as select id from operational_probe.item;")
            .shouldName("probe_view", "operational_probe")
    }

    @Test
    fun `INV-AC-7871 a reference to the look-alike schema evidence_x fails the catalogue test`() {
        probed("create view evidence.probe_view as select id from evidence_x.item;")
            .shouldName("probe_view", "evidence_x")
        probed(
            "create function evidence.probe_fn() returns bigint language plpgsql as " +
                "\$\$ begin return (select count(*) from evidence_x.item); end \$\$;",
        ).shouldName("probe_fn()", "evidence_x")
    }

    @Test
    fun `INV-AC-7871 a trigger running a function of another schema fails the catalogue test`() {
        probed(
            "create table evidence.probe (id uuid primary key, tenant_id uuid not null);" +
                "create trigger probe_trigger before insert on evidence.probe " +
                "for each row execute function operational_probe.noop();",
        ).shouldName("probe_trigger", "operational_probe")
    }

    private val callHmac =
        "language plpgsql as \$\$ begin perform public.hmac('a'::bytea, 'b'::bytea, 'sha256'); end \$\$;"

    @Test
    fun `INV-AC-7871 another function calling public hmac fails the catalogue test`() {
        probed { it.run("create function evidence.helper() returns void $callHmac") }
            .shouldName("function helper()", "public.hmac")
        probed {
            it.run("create function evidence.bound_tenant_x() returns void $callHmac")
            it.run("create function evidence.bound_tenant(text) returns void $callHmac")
        }.apply {
            shouldName("function bound_tenant_x()", "public.hmac")
            shouldName("function bound_tenant(25)", "public.hmac")
        }
        probed {
            it.run(
                "create or replace function evidence.bound_actor() returns text language sql as " +
                    "\$\$ select encode(public.hmac('a'::bytea, 'b'::bytea, 'sha256'), 'hex') \$\$",
            )
        }.shouldName("function bound_actor()", "public")
    }

    @Test
    fun `INV-AC-7871 a named function with another reference fails the catalogue test`() {
        probed { it.run(it.definition("bound_tenant()").replace("public.hmac", "public.digest")) }
            .shouldName("function bound_tenant()", "public.digest")
        probed {
            it.run("create schema extensions")
            it.run(it.definition("bound_tenant()").replace("public.hmac", "extensions.hmac"))
        }.shouldName("function bound_tenant()", "extensions.hmac")
        probed {
            it.run("create or replace function operational_probe.ping() returns void language sql as 'select 1'")
            it.run(
                it.definition("binding_verified()").replace(
                    "IF sig IS NULL THEN RETURN NULL; END IF;",
                    "PERFORM operational_probe.ping(); IF sig IS NULL THEN RETURN NULL; END IF;",
                ),
            )
        }.shouldName("function binding_verified()", "operational_probe.ping")
    }

    @Test
    fun `INV-AC-7871 a changed or missing named function fails the catalogue test`() {
        probed {
            it.run("grant create on schema evidence to evidence_migrate")
            it.run("alter function evidence.bound_tenant() owner to evidence_migrate")
        }.shouldName("function bound_tenant()", "evidence_migrate")
        probed { it.run("alter function evidence.binding_verified() rename to binding_verified_x") }
            .shouldName("function binding_verified()", "missing")
    }
}
