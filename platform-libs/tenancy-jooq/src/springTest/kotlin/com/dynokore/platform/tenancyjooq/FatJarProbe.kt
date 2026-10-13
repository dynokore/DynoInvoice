package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.Context
import org.jooq.Field
import org.jooq.QueryPart
import org.jooq.SQLDialect
import org.jooq.impl.CustomField
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.SQLDataType
import org.jooq.impl.SpoofField
import org.jooq.impl.SpoofWindow
import java.util.UUID

/**
 * The Start-Class of FatJarTest's Spring Boot executable jar (review of !61): it renders, on a tenancy-jooq
 * DSLContext, a window function and the consumer parts that must be refused, and prints each outcome and the class
 * loader it runs in. Rendering needs no database, so the transaction runner hands out no connection.
 */
object FatJarProbe {
    private object RenderOnly : TransactionRunner, CurrentConnection {
        override fun <T> inTransaction(block: () -> T): T = block()

        override fun require(): Nothing = throw UnsupportedOperationException("FatJarProbe only renders")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val orders = DSL.table(DSL.name("s", "orders"))
        val amount = DSL.field(DSL.name("s", "orders", "amount"), Int::class.java)
        val tenants = TenantContextImpl()
        val tables = TenancyTables.declared(mapOf(DSL.name("s", "orders") to TableClass.TENANT))
        val dsl =
            TenancyJooq.configure(DefaultConfiguration().set(SQLDialect.POSTGRES), tables, tenants, RenderOnly).dsl()

        // Each consumer part sits directly below a jOOQ window function, where jOOQ's own OVER clause part sits.
        fun belowWindow(part: Field<*>) = DSL.lag(part).over(DSL.orderBy(amount))
        val cases: Map<String, () -> QueryPart> =
            linkedMapOf(
                "window function" to { dsl.select(DSL.rowNumber().over(DSL.orderBy(amount))).from(orders) },
                "consumer custom part" to { dsl.select(belowWindow(ConsumerPart())).from(orders) },
                "custom part in org.jooq.impl" to { dsl.select(belowWindow(SpoofField())).from(orders) },
                "subclass of a jOOQ window function" to {
                    dsl.select(SpoofWindow().over(DSL.orderBy(amount))).from(orders)
                },
            )
        println("loader: ${FatJarProbe::class.java.classLoader.javaClass.name}")
        tenants.bind(TenantId(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")), "fat-jar probe") {
            for ((name, statement) in cases) {
                val outcome =
                    try {
                        "accepted: ${dsl.render(statement())}"
                    } catch (violation: TenantPredicateViolation) {
                        "refused: ${violation.message}"
                    }
                println("$name: $outcome")
            }
        }
    }
}

/** A consumer's custom field that renders a comment. */
class ConsumerPart : CustomField<Int>(DSL.name("consumer_part"), SQLDataType.INTEGER) {
    override fun accept(ctx: Context<*>) {
        ctx.sql("1 --")
    }
}
