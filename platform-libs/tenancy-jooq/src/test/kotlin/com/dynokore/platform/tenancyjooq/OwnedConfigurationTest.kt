package com.dynokore.platform.tenancyjooq

import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.ExecuteListener
import org.jooq.RecordContext
import org.jooq.RecordListener
import org.jooq.SQLDialect
import org.jooq.TableField
import org.jooq.UniqueKey
import org.jooq.VisitListener
import org.jooq.conf.InvocationOrder
import org.jooq.conf.MappedSchema
import org.jooq.conf.MappedTable
import org.jooq.conf.ParamType
import org.jooq.conf.RenderKeywordCase
import org.jooq.conf.RenderMapping
import org.jooq.conf.RenderNameCase
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.RenderTable
import org.jooq.conf.Settings
import org.jooq.conf.StatementType
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.Internal
import org.jooq.impl.SQLDataType
import org.jooq.impl.TableImpl
import org.jooq.impl.TableRecordImpl
import org.jooq.impl.UpdatableRecordImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import java.util.UUID
import java.util.regex.Pattern

/**
 * tenancy-jooq owns the jOOQ settings, the dialect and the VisitListeners of its configurations (review of !61): a
 * setting that can change a table's identity or how a statement or its tenant predicate renders is refused when the
 * configuration is configured and, set afterwards on the same Settings object, before any SQL of a statement reaches
 * the server; so are another dialect and a listener that could rewrite the statement. Mapped and renamed schemata and
 * tables appear here only to prove their refusal.
 */
class OwnedConfigurationTest {
    private val fixture = Databases.of(Images.PG18)

    class Case(
        private val name: String,
        val change: (Settings) -> Settings,
    ) {
        override fun toString(): String = name
    }

    private fun configured(
        settings: Settings,
        dialect: SQLDialect = SQLDialect.POSTGRES,
    ): Configuration =
        TenancyJooq.configure(
            DefaultConfiguration().set(dialect).set(settings),
            TEST_TABLES,
            fixture.tenants,
            fixture.transactions,
        )

    private fun refusedOnRun(configuration: Configuration) {
        Seed.reset(fixture)
        val mark = fixture.statementLog().size
        assertThrows<TenantPredicateViolation> {
            fixture.asTenant(Tenants.A) { configuration.dsl().selectFrom(Orders).fetch() }
        }
        assertEquals(emptyList<String>(), fixture.statementsSince(mark), "no SQL may reach the server")
    }

    @ParameterizedTest
    @FieldSource("OWNED")
    fun `a setting tenancy-jooq owns is refused when the configuration is configured`(case: Case) {
        assertThrows<TenantPredicateViolation> { configured(case.change(Settings())) }
    }

    @ParameterizedTest
    @FieldSource("OWNED")
    fun `a setting tenancy-jooq owns is refused when it is set afterwards and a statement runs`(case: Case) {
        val configuration = fixture.configuration(TEST_TABLES)
        case.change(configuration.settings())
        refusedOnRun(configuration)
    }

    @Test
    fun `execution limits and logging may be set`() {
        val settings =
            Settings()
                .withQueryTimeout(30)
                .withFetchSize(100)
                .withMaxRows(1000)
                .withExecuteLogging(false)
        val configuration = configured(settings)
        Seed.reset(fixture)
        val rows = fixture.asTenant(Tenants.A) { configuration.dsl().selectFrom(Orders).fetch() }
        assertEquals(Seed.orderTenants(fixture).values.count { it == Tenants.A.value }, rows.size)
    }

    @ParameterizedTest
    @FieldSource("OPTIMISTIC_LOCKING")
    fun `a record-level optimistic locking setting is accepted when configured and when set afterwards`(case: Case) {
        val rowsOf = { configuration: Configuration ->
            fixture.asTenant(Tenants.A) { configuration.dsl().selectFrom(Orders).fetch() }
        }
        Seed.reset(fixture)
        val expected = Seed.orderTenants(fixture).values.count { it == Tenants.A.value }
        assertEquals(expected, rowsOf(configured(case.change(Settings()))).size)
        val configuration = fixture.configuration(TEST_TABLES)
        case.change(configuration.settings())
        assertEquals(expected, rowsOf(configuration).size)
    }

    @ParameterizedTest
    @FieldSource("LOOK_ALIKES")
    fun `a look-alike of the record-level settings is refused when configured`(case: Case) {
        assertThrows<TenantPredicateViolation> { configured(case.change(Settings())) }
    }

    @ParameterizedTest
    @FieldSource("LOOK_ALIKES")
    fun `a look-alike of the record-level settings is refused when set afterwards and a statement runs`(case: Case) {
        val configuration = fixture.configuration(TEST_TABLES)
        case.change(configuration.settings())
        refusedOnRun(configuration)
    }

    /** A TableRecord of the `orders` table, so that a RecordListener sees the record of an insert. */
    class OrderRecord : TableRecordImpl<OrderRecord>(OrderRecords)

    object OrderRecords : TableImpl<OrderRecord>(DSL.name("orders"), TestSchema) {
        val ID: TableField<OrderRecord, UUID> = createField(DSL.name("id"), SQLDataType.UUID, this)
        val TENANT_ID: TableField<OrderRecord, UUID> = createField(DSL.name("tenant_id"), SQLDataType.UUID, this)
        val CUSTOMER_ID: TableField<OrderRecord, UUID> = createField(DSL.name("customer_id"), SQLDataType.UUID, this)
        val AMOUNT: TableField<OrderRecord, Int> = createField(DSL.name("amount"), SQLDataType.INTEGER, this)

        override fun getRecordType() = OrderRecord::class.java
    }

    /** An UpdatableRecord of the `orders` table (primary key `id`), for batchStore. */
    class KeyedOrderRecord : UpdatableRecordImpl<KeyedOrderRecord>(KeyedOrderRecords)

    object KeyedOrderRecords : TableImpl<KeyedOrderRecord>(DSL.name("orders"), TestSchema) {
        val ID: TableField<KeyedOrderRecord, UUID> = createField(DSL.name("id"), SQLDataType.UUID.nullable(false), this)
        val TENANT_ID: TableField<KeyedOrderRecord, UUID> = createField(DSL.name("tenant_id"), SQLDataType.UUID, this)
        val CUSTOMER_ID: TableField<KeyedOrderRecord, UUID> =
            createField(DSL.name("customer_id"), SQLDataType.UUID, this)
        val AMOUNT: TableField<KeyedOrderRecord, Int> = createField(DSL.name("amount"), SQLDataType.INTEGER, this)

        override fun getRecordType() = KeyedOrderRecord::class.java

        override fun getPrimaryKey(): UniqueKey<KeyedOrderRecord> =
            Internal.createUniqueKey(this, DSL.name("orders_pkey"), arrayOf(ID), true)
    }

    private fun orderOf(
        dsl: DSLContext,
        tenant: UUID,
    ) = dsl.newRecord(OrderRecords).also {
        it.set(OrderRecords.ID, UUID.randomUUID())
        it.set(OrderRecords.TENANT_ID, tenant)
        it.set(OrderRecords.CUSTOMER_ID, Seed.CUSTOMER_A)
        it.set(OrderRecords.AMOUNT, 1)
    }

    @Test
    fun `batchInsert of table records is refused with no INSERT in the statement log`() {
        val configuration = fixture.configuration(TEST_TABLES)
        Seed.reset(fixture)
        val mark = fixture.statementLog().size
        assertThrows<TenantPredicateViolation> {
            fixture.asTenant(Tenants.A) {
                val dsl = configuration.dsl()
                dsl.batchInsert(orderOf(dsl, Tenants.A.value), orderOf(dsl, Tenants.A.value)).execute()
            }
        }
        assertEquals(emptyList<String>(), fixture.statementsSince(mark).filter { it.startsWith("insert", true) })
    }

    @Test
    fun `batchStore of table records is refused with no INSERT or UPDATE in the statement log`() {
        val configuration = fixture.configuration(TEST_TABLES)
        Seed.reset(fixture)
        val mark = fixture.statementLog().size
        assertThrows<TenantPredicateViolation> {
            fixture.asTenant(Tenants.A) {
                val dsl = configuration.dsl()
                val records =
                    (1..2).map {
                        dsl.newRecord(KeyedOrderRecords).also { r ->
                            r.set(KeyedOrderRecords.ID, UUID.randomUUID())
                            r.set(KeyedOrderRecords.TENANT_ID, Tenants.A.value)
                            r.set(KeyedOrderRecords.CUSTOMER_ID, Seed.CUSTOMER_A)
                            r.set(KeyedOrderRecords.AMOUNT, it)
                        }
                    }
                dsl.batchStore(records).execute()
            }
        }
        assertEquals(
            emptyList<String>(),
            fixture.statementsSince(mark).filter { it.startsWith("insert", true) || it.startsWith("update", true) },
        )
    }

    private class Marker(
        val name: String,
    ) : RecordListener

    @Test
    fun `the listeners given to configure come back in order`() {
        val first = Marker("first")
        val second = Marker("second")
        val configuration =
            TenancyJooq.configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TEST_TABLES,
                fixture.tenants,
                fixture.transactions,
                listOf(first, second),
            )
        assertEquals(listOf<RecordListener>(first, second), TenancyJooq.recordListeners(configuration))
    }

    @Test
    fun `install on a DefaultConfiguration registers the listeners in order`() {
        val first = Marker("first")
        val second = Marker("second")
        val configuration = DefaultConfiguration().apply { set(SQLDialect.POSTGRES) }
        TenancyJooq.install(configuration, TEST_TABLES, fixture.tenants, fixture.transactions, listOf(first, second))
        assertEquals(listOf<RecordListener>(first, second), TenancyJooq.recordListeners(configuration))
        assertEquals(null, TenancyJooq.problem(configuration))
    }

    @Test
    fun `with no listeners the list holds none of the caller's`() {
        val caller = Marker("caller")
        val configured = fixture.configuration(TEST_TABLES)
        assertTrue(TenancyJooq.recordListeners(configured).none { it === caller })
        val installed = DefaultConfiguration().apply { set(SQLDialect.POSTGRES) }
        TenancyJooq.install(installed, TEST_TABLES, fixture.tenants, fixture.transactions)
        assertEquals(emptyList<RecordListener>(), TenancyJooq.recordListeners(installed))
    }

    @Test
    fun `a RecordListener that points an insert at another tenant still has the statement refused before SQL`() {
        val crossTenant =
            object : RecordListener {
                override fun insertStart(ctx: RecordContext) {
                    ctx.record().set(OrderRecords.TENANT_ID, Tenants.B.value)
                }
            }
        val configuration =
            TenancyJooq.configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TEST_TABLES,
                fixture.tenants,
                fixture.transactions,
                listOf(crossTenant),
            )
        Seed.reset(fixture)
        val mark = fixture.statementLog().size
        assertThrows<TenantPredicateViolation> {
            fixture.asTenant(Tenants.A) {
                val record = configuration.dsl().newRecord(OrderRecords)
                record.set(OrderRecords.ID, UUID.randomUUID())
                record.set(OrderRecords.TENANT_ID, Tenants.A.value)
                record.set(OrderRecords.CUSTOMER_ID, Seed.CUSTOMER_A)
                record.set(OrderRecords.AMOUNT, 1)
                record.insert()
            }
        }
        assertEquals(emptyList<String>(), fixture.statementsSince(mark), "no SQL may reach the server")
    }

    @Test
    fun `another dialect is refused when configured and when derived afterwards`() {
        assertThrows<TenantPredicateViolation> { configured(Settings(), SQLDialect.H2) }
        refusedOnRun(fixture.configuration(TEST_TABLES).derive(SQLDialect.H2))
    }

    @Test
    fun `a VisitListener of the consumer's is refused when configured and when appended afterwards`() {
        val listener = object : VisitListener {}
        assertThrows<TenantPredicateViolation> {
            TenancyJooq.configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES).set(listener),
                TEST_TABLES,
                fixture.tenants,
                fixture.transactions,
            )
        }
        refusedOnRun(fixture.configuration(TEST_TABLES).deriveAppending(listener))
    }

    @Test
    fun `an ExecuteListener after the statement check is refused`() {
        refusedOnRun(fixture.configuration(TEST_TABLES).deriveAppending(object : ExecuteListener {}))
    }

    companion object {
        private const val SCHEMA = "tenancy_kernel_test"

        @JvmField
        val OPTIMISTIC_LOCKING: List<Case> =
            listOf(
                Case("executeWithOptimisticLocking") { it.withExecuteWithOptimisticLocking(true) },
                Case("executeWithOptimisticLockingExcludeUnversioned") {
                    it.withExecuteWithOptimisticLockingExcludeUnversioned(true)
                },
                Case("updateRecordVersion") { it.withUpdateRecordVersion(false) },
                Case("updatablePrimaryKeys") { it.withUpdatablePrimaryKeys(true) },
            )

        @JvmField
        val LOOK_ALIKES: List<Case> =
            listOf(
                Case("updateRecordTimestamp") { it.withUpdateRecordTimestamp(false) },
                Case("returnAllOnUpdatableRecord") { it.withReturnAllOnUpdatableRecord(true) },
                Case("returnRecordToPojo") { it.withReturnRecordToPojo(false) },
                Case("reflectionCaching") { it.withReflectionCaching(false) },
                Case("returnIdentityOnUpdatableRecord") { it.withReturnIdentityOnUpdatableRecord(false) },
            )

        @JvmField
        val OWNED: List<Case> =
            listOf(
                Case("renderMapping default schema") {
                    it.withRenderMapping(RenderMapping().withDefaultSchema(SCHEMA))
                },
                Case("renderMapping mapped schema") {
                    it.withRenderMapping(
                        RenderMapping().withSchemata(MappedSchema().withInput(SCHEMA).withOutput("public")),
                    )
                },
                Case("renderMapping mapped schema expression") {
                    it.withRenderMapping(
                        RenderMapping().withSchemata(
                            MappedSchema().withInputExpression(Pattern.compile(".*")).withOutput("public"),
                        ),
                    )
                },
                Case("renderMapping mapped table") {
                    it.withRenderMapping(
                        RenderMapping().withSchemata(
                            MappedSchema()
                                .withInput(SCHEMA)
                                .withTables(MappedTable().withInput("orders").withOutput("customers")),
                        ),
                    )
                },
                Case("renderSchema") { it.withRenderSchema(it.isRenderSchema != true) },
                Case("renderCatalog") { it.withRenderCatalog(it.isRenderCatalog != true) },
                Case("renderTable") { it.withRenderTable(RenderTable.NEVER) },
                Case("renderQuotedNames") { it.withRenderQuotedNames(RenderQuotedNames.ALWAYS) },
                Case("renderNameCase") { it.withRenderNameCase(RenderNameCase.UPPER) },
                Case("renderFormatted") { it.withRenderFormatted(it.isRenderFormatted != true) },
                Case("renderPlainSQLTemplatesAsRaw") {
                    it.withRenderPlainSQLTemplatesAsRaw(it.isRenderPlainSQLTemplatesAsRaw != true)
                },
                Case("statementType") { it.withStatementType(StatementType.STATIC_STATEMENT) },
                Case("paramType") { it.withParamType(ParamType.INLINED) },
                Case("inlineThreshold") { it.withInlineThreshold(1) },
                // jOOQ OSS refuses the transformation settings itself (a commercial-only feature)
                Case("renderKeywordCase") { it.withRenderKeywordCase(RenderKeywordCase.UPPER) },
                Case("visitListenerStartInvocationOrder") {
                    it.withVisitListenerStartInvocationOrder(InvocationOrder.REVERSE)
                },
                Case("executeListenerEndInvocationOrder") {
                    it.withExecuteListenerEndInvocationOrder(InvocationOrder.REVERSE)
                },
            )
    }
}
