package com.dynokore.platform.tenancyjooq

import org.jooq.DataType
import org.jooq.Record
import org.jooq.Schema
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.impl.SchemaImpl
import org.jooq.impl.TableImpl
import java.util.UUID

/** The test tables in the shape jOOQ's code generator emits (a SchemaImpl and TableImpl subclasses). */
object TestSchema : SchemaImpl(DSL.name("tenancy_kernel_test"), null) {
    override fun getTables(): List<Table<*>> = listOf(Customers, Orders, Currencies, Countries)
}

/** A generated-style table of [TestSchema]. */
abstract class TestTable(
    name: String,
    schema: Schema = TestSchema,
) : TableImpl<Record>(DSL.name(name), schema) {
    protected fun <T> column(
        name: String,
        type: DataType<T>,
    ): TableField<Record, T> = createField(DSL.name(name), type, this)
}

object Customers : TestTable("customers") {
    val ID: TableField<Record, UUID> = column("id", SQLDataType.UUID)
    val TENANT_ID: TableField<Record, UUID> = column("tenant_id", SQLDataType.UUID)
    val NAME: TableField<Record, String> = column("name", SQLDataType.CLOB)
}

object Orders : TestTable("orders") {
    val ID: TableField<Record, UUID> = column("id", SQLDataType.UUID)
    val TENANT_ID: TableField<Record, UUID> = column("tenant_id", SQLDataType.UUID)
    val CUSTOMER_ID: TableField<Record, UUID> = column("customer_id", SQLDataType.UUID)
    val AMOUNT: TableField<Record, Int> = column("amount", SQLDataType.INTEGER)
}

object Currencies : TestTable("currencies") {
    val CODE: TableField<Record, String> = column("code", SQLDataType.CLOB)
    val TENANT_ID: TableField<Record, UUID> = column("tenant_id", SQLDataType.UUID)
    val NAME: TableField<Record, String> = column("name", SQLDataType.CLOB)
}

object Countries : TestTable("countries") {
    val CODE: TableField<Record, String> = column("code", SQLDataType.CLOB)
    val NAME: TableField<Record, String> = column("name", SQLDataType.CLOB)
}

/** A table of the schema that the migrations never created and the declaration does not know. */
object Unknown : TestTable("unknown") {
    val ID: TableField<Record, UUID> = column("id", SQLDataType.UUID)
}

/** The declaration the tests use: countries are platform-scoped, currencies a reference table. */
val TEST_TABLES: TenancyTables =
    TableClasses
        .of(
            platformScoped = listOf("tenancy_kernel_test.countries"),
            reference = listOf("tenancy_kernel_test.currencies"),
        ).verify(TestSchema)
