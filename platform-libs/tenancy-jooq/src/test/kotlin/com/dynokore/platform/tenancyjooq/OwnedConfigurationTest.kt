package com.dynokore.platform.tenancyjooq

import org.jooq.Configuration
import org.jooq.ExecuteListener
import org.jooq.SQLDialect
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
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
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
