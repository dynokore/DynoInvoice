package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.session.JooqSessionStore
import com.dynokore.gst.consoleBff.domain.session.SessionId
import com.dynokore.gst.consoleBff.domain.session.SessionIdHash
import com.dynokore.gst.consoleBff.domain.session.SessionIdParse
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancyjooq.TenantPredicateViolation
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * The binding never escapes the store (INV-T-14458 session store ruling (c)): `find` returns the row's tenant as data,
 * and a tenant-scoped statement then sees only the tenant bound by the caller. The test-only tenant table is created
 * here by test SQL (never by a migration), with a tenant row-level-security policy, and queried through a tenancy-jooq
 * DSL that declares it as a tenant table.
 */
class SessionTenancyTest {
    private val a = "11111111-1111-1111-1111-11111111aaaa"
    private val b = "22222222-2222-2222-2222-22222222bbbb"
    private val table = DSL.name("console_bff", "tenant_probe")

    private fun prepare() {
        ConsoleDb.superuser().use { c ->
            c.createStatement().execute(
                """
                create table if not exists console_bff.tenant_probe (tenant_id uuid not null, note text not null);
                alter table console_bff.tenant_probe enable row level security;
                grant select, insert, delete on console_bff.tenant_probe to console_bff_runtime;
                truncate console_bff.tenant_probe;
                insert into console_bff.tenant_probe values ('$a', 'note-a'), ('$b', 'note-b');
                """.trimIndent(),
            )
            c.createStatement().execute(
                "drop policy if exists tenant_isolation on console_bff.tenant_probe; " +
                    "create policy tenant_isolation on console_bff.tenant_probe " +
                    "using (tenant_id = current_setting('app.tenant_id')::uuid)",
            )
            c.createStatement().execute("truncate console_bff.console_session")
        }
    }

    @Test
    fun `INV-AC-14457 after find the bound row tenant sees only its rows and the unbound statement is refused`(
        @TempDir dir: Path,
    ) {
        prepare()
        ConsoleContext.online(dir).run { context ->
            context.startupFailure.shouldBeNull()
            val store = context.getBean(JooqSessionStore::class.java)
            val tenants = context.getBean(TenantContext::class.java)
            val runner = context.getBean(TransactionRunner::class.java)
            val dsl =
                DSL.using(
                    TenancyJooq.configure(
                        DefaultConfiguration().set(SQLDialect.POSTGRES),
                        TenancyTables.declared(mapOf(table to TableClass.TENANT)),
                        tenants,
                        runner,
                    ),
                )

            fun notes() =
                dsl
                    .select(DSL.field(DSL.name("console_bff", "tenant_probe", "note"), String::class.java))
                    .from(DSL.table(table))
                    .fetch()
                    .map { it.value1() }

            val created = store.create(a, "p1", 1, Instant.now())
            val hash = SessionIdHash.of((SessionId.parse(created.cookieValue) as SessionIdParse.Parsed).id)
            val row = checkNotNull(store.find(hash))
            row.tenantId shouldBe a

            (runCatching { notes() }.exceptionOrNull() is TenantPredicateViolation) shouldBe true
            tenants.bind(TenantId.of(row.tenantId), "test-actor") { runner.inTransaction { notes() } } shouldBe
                listOf("note-a")
            tenants.bind(TenantId.of(b), "test-actor") { runner.inTransaction { notes() } } shouldBe listOf("note-b")
            (runCatching { notes() }.exceptionOrNull() is TenantPredicateViolation) shouldBe true
        }
    }

    @Test
    fun `INV-AC-14457 the application with console_session not declared platform-scoped fails the start`(
        @TempDir dir: Path,
    ) {
        ConsoleContext
            .online(dir, "inv.tenancy.platform-scoped-tables=")
            .run { context -> (context.startupFailure != null) shouldBe true }
    }
}
