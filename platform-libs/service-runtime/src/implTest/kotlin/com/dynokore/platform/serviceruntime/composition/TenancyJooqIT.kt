package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.postgres
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.query
import com.dynokore.platform.serviceruntime.datasource.CredentialRotationIT.Companion.sql
import com.dynokore.platform.serviceruntime.datasource.tpl18Service
import com.dynokore.platform.serviceruntime.datasource.withMountedLogin
import com.dynokore.platform.tenancyjooq.TenantPredicateViolation
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.util.UUID

/**
 * INV-T-4134 on PostgreSQL (INV-ADR-3968 item 4a, owner rulings INV-Q-4113, INV-Q-4115): a TPL-style service fixture
 * (the full TPL-18 composition) with a TENANT table `item` and a PLATFORM_SCOPED table `flag`, row-level security off
 * on both so the framework's jOOQ predicate alone is proved. A repository query that names no tenant returns only the
 * bound tenant's rows, and a tenant table read under the platform-tenant binding returns no tenant row.
 */
class TenancyJooqIT {
    private val a = TenantId(UUID(1L, 1L))
    private val b = TenantId(UUID(2L, 2L))

    @Configuration(proxyBeanMethods = false)
    class ServiceSchema {
        @Bean
        fun submissionSchema(): FixtureSchema = FixtureSchema(Tables.SCHEMA, Tables.SERVICE)
    }

    private fun run(
        mountRoot: Path,
        block: (AssertableApplicationContext, FixtureSchema, com.dynokore.platform.tenancykernel.TenantContext) -> Unit,
    ) {
        postgres("postgres:17").use { db ->
            db.sql(
                "create role $RUNTIME login password '$PASSWORD'",
                "create schema submission",
                "create table submission.item (id uuid primary key, tenant_id uuid not null, label text)",
                "create table submission.flag (id uuid primary key, label text)",
                "grant usage on schema submission to $RUNTIME",
                "grant select, insert, update, delete on submission.item, submission.flag to $RUNTIME",
                "insert into submission.item values ('${FixtureTable.id(1)}', '${a.value}', 'a1')," +
                    "('${FixtureTable.id(2)}', '${a.value}', 'a2'), ('${FixtureTable.id(3)}', '${b.value}', 'b1')",
                "insert into submission.flag values ('${FixtureTable.id(9)}', 'f')",
            )
            tpl18Service()
                .withPropertyValues(
                    "spring.datasource.url=${db.jdbcUrl}",
                    "spring.datasource.username=$RUNTIME",
                    "service-runtime.modules.schema=${Tables.SCHEMA}",
                    "inv.tenancy.platform-scoped-tables=${Tables.SCHEMA}.flag",
                ).withMountedLogin(mountRoot, PASSWORD)
                .withUserConfiguration(ServiceSchema::class.java)
                .run { context ->
                    context.startupFailure shouldBe null
                    val schema = context.getBean(FixtureSchema::class.java)
                    block(context, schema, context.getBean(TenantContext::class.java))
                    // the ground truth, as the database owner (no row-level security): nothing else was written
                    db.query("select count(*) from submission.item where tenant_id = '${b.value}'") shouldBe "1"
                }
        }
    }

    private fun <T> AssertableApplicationContext.asTenant(
        tenant: TenantId,
        block: (DSLContext) -> T,
    ): T =
        getBean(TenantContext::class.java).bind(tenant, ACTOR) {
            getBean(TransactionRunner::class.java).inTransaction { block(getBean(DSLContext::class.java)) }
        }

    @Test
    fun `a repository query without a tenant predicate returns only the bound tenant's rows`(
        @TempDir mountRoot: Path,
    ) {
        run(mountRoot) { context, schema, _ ->
            val item = schema.table("item")
            context.asTenant(a) {
                it
                    .select(item.label)
                    .from(item)
                    .orderBy(item.label)
                    .fetch(item.label)
            } shouldBe
                listOf("a1", "a2")
            context.asTenant(b) { it.select(item.label).from(item).fetch(item.label) } shouldBe listOf("b1")
            // a write names no tenant either: it lands under the bound tenant and reaches no other tenant's row
            context.asTenant(a) { it.update(item).set(item.label, "changed").execute() } shouldBe 2
            context.asTenant(b) { it.select(item.label).from(item).fetch(item.label) } shouldBe listOf("b1")
        }
    }

    @Test
    fun `under the platform binding a tenant table returns no tenant row and a platform-scoped table its rows`(
        @TempDir mountRoot: Path,
    ) {
        run(mountRoot) { context, schema, tenants ->
            val item = schema.table("item")
            val flag = schema.table("flag")
            val platform = tenants as PlatformTenantBinding
            val runner = context.getBean(TransactionRunner::class.java)
            val dsl = context.getBean(DSLContext::class.java)
            platform.bindPlatform(ACTOR) {
                runner.inTransaction {
                    dsl.select(item.label).from(item).fetch(item.label) shouldBe emptyList()
                    dsl.select(flag.label).from(flag).fetch(flag.label) shouldBe listOf("f")
                }
            }
            // a platform-scoped table is read under any binding and written only under the platform binding
            context.asTenant(a) { it.select(flag.label).from(flag).fetch(flag.label) } shouldBe listOf("f")
            shouldThrow<TenantPredicateViolation> {
                context.asTenant(a) { it.update(flag).set(flag.label, "x").execute() }
            }
            platform.bindPlatform(ACTOR) {
                runner.inTransaction { dsl.update(flag).set(flag.label, "x").execute() shouldBe 1 }
            }
        }
    }

    private companion object {
        const val RUNTIME = "submission_runtime"
        const val PASSWORD = "runtime-pw"
        const val ACTOR = "user:integration-test"
    }
}
