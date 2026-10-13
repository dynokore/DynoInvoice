package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancyjooq.Seed.CUSTOMER_A
import org.jooq.Context
import org.jooq.DSLContext
import org.jooq.impl.CustomCondition
import org.jooq.impl.CustomField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Every refused shape throws [TenantPredicateViolation] before any SQL reaches the server: the runtime login's
 * statement log holds nothing after the transaction's own binding statement (INV-T-4133; coordinator ruling
 * INV-Q-4146). Plain-SQL parts are built here only to prove their refusal (coordinator ruling INV-Q-4181).
 */
class RefusalTest {
    private fun refusedAsA(
        image: String,
        configured: (TenancyJooqFixture) -> DSLContext = { dsl(it) },
        statement: (DSLContext) -> Any?,
    ): TenantPredicateViolation {
        val fixture = Databases.of(image)
        Seed.reset(fixture)
        val mark = fixture.statementLog().size
        val violation =
            assertThrows<TenantPredicateViolation> {
                fixture.asTenant(Tenants.A) { statement(configured(fixture)) }
            }
        assertEquals(emptyList<String>(), fixture.statementsSince(mark), "no SQL may reach the server")
        return violation
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `plain SQL query parts are refused`(image: String) {
        refusedAsA(image) { it.fetch("select 1") }
        refusedAsA(image) { it.resultQuery("select id from tenancy_kernel_test.orders").fetch() }
        refusedAsA(image) { it.execute("delete from tenancy_kernel_test.orders") }
        refusedAsA(image) { it.select(DSL.field("id")).from(Orders).fetch() }
        refusedAsA(image) { it.selectFrom(DSL.table("tenancy_kernel_test.orders")).fetch() }
        refusedAsA(image) { it.selectFrom(Orders).where(DSL.condition("true")).fetch() }
        refusedAsA(image) { it.selectFrom(Orders).where(DSL.condition(DSL.sql("true"))).fetch() }
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `plain SQL is refused whatever its text, operator characters and comment tokens included`(image: String) {
        for (text in listOf("--", "/* */", "<>", ";", "*", "+")) {
            assertPlainSql(refusedAsA(image) { it.selectFrom(Orders).where(DSL.condition(text)).fetch() })
            assertPlainSql(refusedAsA(image) { it.select(Orders.ID, DSL.field(text)).from(Orders).fetch() })
        }
        assertPlainSql(refusedAsA(image) { it.select(DSL.count(DSL.field("*"))).from(Orders).fetch() })
        assertPlainSql(
            refusedAsA(image) { it.select(Orders.AMOUNT.plus(DSL.field("+", Int::class.java))).from(Orders).fetch() },
        )
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `unquoted names and custom query parts are refused`(image: String) {
        val injected = DSL.field(DSL.unquotedName("1 <> 2 or true"), Boolean::class.java)
        refusedAsA(image) { it.selectFrom(Orders).where(injected.isTrue).fetch() }
        val system = DSL.field(DSL.systemName("1 <> 2 or true"), Boolean::class.java)
        refusedAsA(image) { it.selectFrom(Orders).where(system.isTrue).fetch() }
        refusedAsA(image) { it.select(DSL.function("now", OffsetDateTime::class.java)).from(Orders).fetch() }
        refusedAsA(image) { it.selectFrom(Orders).where(CustomCondition.of { context -> context.sql("true") }).fetch() }
        val comment = CommentPart()
        refusedAsA(image) { it.select(Orders.ID, comment).from(Orders).fetch() }
        val commented = CustomCondition.of { context -> context.sql("true --") }
        refusedAsA(image) { it.selectFrom(Orders).where(commented).fetch() }
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `the ExecuteListener alone refuses a rendered comment, separator or unbalanced fragment`(image: String) {
        val fragments =
            mapOf(
                "true --" to "line comment",
                "true /* */" to "block comment",
                "true; select 1" to "statement separator",
                "'a' = 'a" to "unterminated",
                "(true" to "unbalanced",
                "true = \$\$a\$\$" to "dollar",
            )
        for ((fragment, problem) in fragments) {
            val violation =
                refusedAsA(image, { dslWithoutInjector(it) }) {
                    it.selectFrom(Orders).where(DSL.condition(fragment)).fetch()
                }
            assertTrue(violation.message!!.contains(problem), violation.message)
        }
    }

    private fun assertPlainSql(violation: TenantPredicateViolation) {
        val message = violation.message!!
        assertTrue(message.contains("plain-SQL query part is refused, whatever its text"), message)
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `TRUNCATE and DDL are refused`(image: String) {
        refusedAsA(image) { it.truncate(Orders).execute() }
        refusedAsA(image) { it.dropTable(Orders).execute() }
        refusedAsA(image) {
            it.createTable(DSL.name("tenancy_kernel_test", "created")).column("id", SQLDataType.UUID).execute()
        }
        refusedAsA(image) { it.alterTable(Orders).dropColumn(Orders.AMOUNT).execute() }
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a table outside the declared schema is refused`(image: String) {
        refusedAsA(image) { it.selectFrom(Unknown).fetch() }
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `the ExecuteListener alone refuses a tenant table without the injected predicate`(image: String) {
        refusedAsA(image, { dslWithoutInjector(it) }) { it.selectFrom(Orders).fetch() }
        refusedAsA(image, { dslWithoutInjector(it) }) {
            it
                .select(Orders.ID)
                .from(Orders)
                .leftJoin(Customers)
                .on(Customers.ID.eq(Orders.CUSTOMER_ID))
                .fetch()
        }
        refusedAsA(image, { dslWithoutInjector(it) }) { it.update(Orders).set(Orders.AMOUNT, 1).execute() }
        refusedAsA(image, { dslWithoutInjector(it) }) { it.deleteFrom(Orders).execute() }
        refusedAsA(image, { dslWithoutInjector(it) }) {
            it
                .insertInto(Orders, Orders.ID, Orders.CUSTOMER_ID, Orders.AMOUNT)
                .values(UUID.randomUUID(), CUSTOMER_A, 1)
                .execute()
        }
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `writes that cross the bound tenant are refused`(image: String) {
        refusedAsA(image) { it.update(Orders).set(Orders.TENANT_ID, Tenants.B.value).execute() }
        refusedAsA(image) {
            it
                .insertInto(Orders, Orders.ID, Orders.TENANT_ID, Orders.CUSTOMER_ID, Orders.AMOUNT)
                .values(UUID.randomUUID(), Tenants.B.value, CUSTOMER_A, 1)
                .execute()
        }
        refusedAsA(image) {
            it
                .insertInto(Orders, Orders.ID, Orders.CUSTOMER_ID, Orders.AMOUNT)
                .values(Seed.ORDER_A1, CUSTOMER_A, 1)
                .onConflict(Orders.ID)
                .doUpdate()
                .set(Orders.TENANT_ID, Tenants.B.value)
                .execute()
        }
        refusedAsA(image) {
            it
                .insertInto(Orders, Orders.ID, Orders.TENANT_ID, Orders.CUSTOMER_ID, Orders.AMOUNT)
                .select(DSL.select(DSL.uuid(), Orders.TENANT_ID, Orders.CUSTOMER_ID, Orders.AMOUNT).from(Orders))
                .execute()
        }
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a PLATFORM_SCOPED write under a tenant binding is refused`(image: String) {
        refusedAsA(image) { it.insertInto(Countries, Countries.CODE, Countries.NAME).values("IT", "Italy").execute() }
        refusedAsA(image) { it.deleteFrom(Countries).execute() }
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `an outer join without ON whose nullable side reads a tenant table is refused`(image: String) {
        refusedAsA(image) {
            it
                .select(Orders.ID)
                .from(Orders)
                .naturalLeftOuterJoin(Customers)
                .fetch()
        }
        refusedAsA(image) {
            it
                .select(Orders.ID)
                .from(Orders)
                .leftJoin(Customers)
                .using(Orders.ID)
                .fetch()
        }
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a batch is refused`(image: String) {
        refusedAsA(image) {
            it
                .batch(
                    it
                        .insertInto(Orders, Orders.ID, Orders.CUSTOMER_ID, Orders.AMOUNT)
                        .values(UUID.randomUUID(), CUSTOMER_A, 1),
                    it
                        .insertInto(Orders, Orders.ID, Orders.CUSTOMER_ID, Orders.AMOUNT)
                        .values(UUID.randomUUID(), CUSTOMER_A, 2),
                ).execute()
        }
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a statement without a binding or without a transaction is refused`(image: String) {
        val fixture = Databases.of(image)
        val mark = fixture.statementLog().size
        assertThrows<TenantPredicateViolation> { dsl(fixture).selectFrom(Orders).fetch() }
        assertThrows<TenantPredicateViolation> {
            fixture.tenants.bind(Tenants.A, "tenancy-jooq-test") { dsl(fixture).selectFrom(Orders).fetch() }
        }
        assertEquals(emptyList<String>(), fixture.statementsSince(mark))
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `a nested binding mismatch is refused`(image: String) {
        val fixture = Databases.of(image)
        val mark = fixture.statementLog().size
        assertThrows<TenantPredicateViolation> {
            fixture.asTenant(Tenants.A) {
                fixture.tenants.bind(Tenants.B, "tenancy-jooq-test") { dsl(fixture).selectFrom(Orders).fetch() }
            }
        }
        assertEquals(emptyList<String>(), fixture.statementsSince(mark))
    }
}

/** A consumer's custom field that renders a comment: refused like every consumer custom part (review of !61). */
private class CommentPart : CustomField<Int>(DSL.name("comment_part"), SQLDataType.INTEGER) {
    override fun accept(ctx: Context<*>) {
        ctx.sql("1 --")
    }
}
