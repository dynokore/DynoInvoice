package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.configresolver.AcknowledgeRelaxationCommand
import com.dynokore.platform.configresolver.Acknowledger
import com.dynokore.platform.configresolver.AcknowledgerCondition
import com.dynokore.platform.configresolver.Bound
import com.dynokore.platform.configresolver.ConfigSchema
import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.DeploymentForm
import com.dynokore.platform.configresolver.Direction
import com.dynokore.platform.configresolver.JdbcPolicyWriter
import com.dynokore.platform.configresolver.OptionSpec
import com.dynokore.platform.configresolver.PolicyChange
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.RelaxationStatus
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.Strictness
import com.dynokore.platform.configresolver.TypedValueSpec
import com.dynokore.platform.configresolver.ValueType
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointDefinition
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpClass
import com.dynokore.platform.configresolver.VpModifier
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.testing.TestDatabase
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One PostgreSQL container with the fixture service `policy-test-svc`: outbox-and-queue's outbox, the API-15
 * idempotency table and config-resolver's tables, over the fixture roles; the real [JdbcPolicyWriter] over the fixture
 * variation points of [Fixtures] (as config-resolver's own write-path tests use them), wrapped in a [RecordingWriter].
 */
class PolicyHttpHarness(
    image: String,
) : AutoCloseable {
    val fixture =
        OutboxPostgresFixture(
            image,
            OutboxPostgresFixture.serviceInitSql(
                SCHEMA,
                IdempotencySchema.ddl(SCHEMA, OutboxPostgresFixture.APP, OutboxPostgresFixture.SYS) +
                    "\n" +
                    ConfigSchema.ddl(SCHEMA, OutboxPostgresFixture.APP, OutboxPostgresFixture.SYS),
            ),
        )
    val clock: Clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)

    /** The tables the tests and the policy writer reach through jOOQ: the idempotency keys and config-resolver's tables. */
    private val db =
        TestDatabase(
            fixture.jdbcUrl(),
            mapOf(
                "$SCHEMA.idempotency_key" to TableClass.TENANT,
                "$SCHEMA.config_setting" to TableClass.TENANT,
                "$SCHEMA.config_relaxation_acknowledgement" to TableClass.TENANT,
                "$SCHEMA.config_installation_setting" to TableClass.PLATFORM_SCOPED,
            ),
        )
    private val app = db.login(OutboxPostgresFixture.APP, OutboxPostgresFixture.APP_PASSWORD)
    val tenants = db.tenants
    val dsl: DSLContext = app.dsl
    private val tx = app.runner
    val scope = TenantScope(tenants, tx, tx, WorkloadPrincipal(SERVICE).value)

    private val outbox =
        JdbcOutboxWriter(
            TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), tenants, tx),
            SCHEMA,
            SERVICE,
            "policy-1",
            CATALOGUE,
            clock,
        )

    val real: PolicyWriter =
        JdbcPolicyWriter(
            dsl,
            Fixtures.catalogue,
            DeploymentForm.SAAS,
            SCHEMA,
            OutboxAuditWriter(outbox),
            outbox,
            clock,
            DelegationProbe { _, _ -> false },
        )

    /** Rows of config-resolver's settings table for [tenant] (nothing stored means zero). */
    fun settings(tenant: java.util.UUID): Long =
        app.asTenant(TenantId(tenant)) {
            selectCount().from(DSL.table(DSL.name(SCHEMA, "config_setting"))).fetchOne(0, Long::class.java) ?: 0L
        }

    override fun close() {
        db.close()
        fixture.close()
    }

    companion object {
        const val SCHEMA = "submission"
        const val SERVICE = "policy-test-svc"
        private const val AUDIT = "com.dynokore.gst.platform.AuditEntryRecorded.v1"

        val CATALOGUE =
            EventCatalogue(
                listOf(
                    CatalogueEntry(
                        AUDIT,
                        "gst.platform.audit-entry-recorded.v1",
                        setOf(CatalogueEntry.EVERY_SERVICE),
                        false,
                        true,
                        retainedWhileResolvable = false,
                    ),
                    CatalogueEntry(
                        JdbcPolicyWriter.POLICY_CHANGED,
                        "gst.platform.policy-changed.v1",
                        setOf(CatalogueEntry.EVERY_SERVICE),
                        false,
                        retainedWhileResolvable = false,
                    ),
                ),
            )
    }
}

/** A [PolicyWriter] that records every command it receives and can hold or fail a call, then delegates. */
class RecordingWriter(
    private val delegate: PolicyWriter,
) : PolicyWriter by delegate {
    val sets = CopyOnWriteArrayList<SetPolicyCommand>()
    val acknowledgements = CopyOnWriteArrayList<AcknowledgeRelaxationCommand>()

    /** Run before the delegate on every call (hold a call, or throw). */
    @Volatile var before: () -> Unit = {}

    /** Applied to the delegate's [PolicyChange] (a test pins the prior the library returns). */
    @Volatile var change: (PolicyChange) -> PolicyChange = { it }

    val calls: Int get() = sets.size + acknowledgements.size

    override fun setPolicy(command: SetPolicyCommand): PolicyChange {
        sets += command
        before()
        return change(delegate.setPolicy(command))
    }

    override fun acknowledgeControlRelaxation(command: AcknowledgeRelaxationCommand): RelaxationStatus {
        acknowledgements += command
        before()
        return delegate.acknowledgeControlRelaxation(command)
    }
}

/** Fixture variation points, shaped on the rows of config-resolver's write-path tests (WriteCatalogue). */
object Fixtures {
    /** VP-62-like: entity and client entity; the looser option needs tax.approver, and client.signatory if delegated. */
    val DUE_DATE = VariationPointKey("VP-62")

    /** VP-20-like despatch timing: entity only, a POLICY. */
    val DESPATCH = VariationPointKey("VP-20")

    /** A CONTROL at tenant and group, looser option acknowledged by tenant.admin. */
    val TENANT_CONTROL = VariationPointKey("VP-07")

    /** Non-waivable CONTROL whose catalogue still lists a looser option: never offered. */
    val NON_WAIVABLE = VariationPointKey("VP-05")

    /** A tenant PARAMETER with a whole number bounded 1 to 4. */
    val BOUNDED = VariationPointKey("VP-42")

    /** A POLICY allowed at every level. */
    val EVERY_LEVEL = VariationPointKey("VP-12")

    /** An installation-only POLICY set by the platform role. */
    val INSTALLATION_ONLY = VariationPointKey("VP-72")

    private fun def(
        key: VariationPointKey,
        levels: Set<ScopeLevel>,
        options: List<OptionSpec>,
        vpClass: VpClass,
        setBy: Set<String>,
        modifiers: Set<VpModifier> = emptySet(),
    ) = VariationPointDefinition(key, vpClass, modifiers, VpStatus.AVAILABLE, levels, setBy, options)

    private val rows =
        listOf(
            def(
                DUE_DATE,
                setOf(ScopeLevel.ENTITY, ScopeLevel.CLIENT_ENTITY),
                listOf(
                    OptionSpec("NO_MOVE", Strictness.DEFAULT),
                    OptionSpec(
                        "NEXT_WORKING_DAY",
                        Strictness.LOOSER,
                        acknowledgers =
                            listOf(
                                Acknowledger("tax.approver"),
                                Acknowledger("client.signatory", AcknowledgerCondition.UNDER_DELEGATION),
                            ),
                    ),
                ),
                VpClass.CONTROL,
                setOf("tax.approver"),
            ),
            def(
                DESPATCH,
                setOf(ScopeLevel.ENTITY),
                listOf(OptionSpec("CONTINUOUS", Strictness.DEFAULT), OptionSpec("DAILY_WINDOW", Strictness.NEUTRAL)),
                VpClass.POLICY,
                setOf("tax.approver"),
            ),
            def(
                TENANT_CONTROL,
                setOf(ScopeLevel.TENANT, ScopeLevel.GROUP),
                listOf(
                    OptionSpec("SECURITY_OFFICER", Strictness.DEFAULT),
                    OptionSpec(
                        "TENANT_ADMIN_SOLE",
                        Strictness.LOOSER,
                        acknowledgers = listOf(Acknowledger("tenant.admin")),
                    ),
                ),
                VpClass.CONTROL,
                setOf("tenant.admin"),
            ),
            def(
                NON_WAIVABLE,
                setOf(ScopeLevel.ENTITY),
                listOf(
                    OptionSpec("SECOND_CONFIRMATION", Strictness.DEFAULT),
                    OptionSpec(
                        "SOLE_OPERATOR",
                        Strictness.LOOSER,
                        acknowledgers = listOf(Acknowledger("tenant.admin")),
                    ),
                ),
                VpClass.CONTROL,
                setOf("tenant.admin"),
                setOf(VpModifier.NON_WAIVABLE),
            ),
            def(
                BOUNDED,
                setOf(ScopeLevel.TENANT),
                listOf(
                    OptionSpec("LAST_PERIOD", Strictness.DEFAULT),
                    OptionSpec(
                        "LAST_N",
                        Strictness.STRICTER,
                        true,
                        TypedValueSpec(
                            ValueType.INT,
                            unit = "periods",
                            min = Bound.of(1),
                            max = Bound.of(4),
                            direction = Direction.UP_STRICTER,
                        ),
                    ),
                ),
                VpClass.PARAMETER,
                setOf("security.officer"),
            ),
            def(
                EVERY_LEVEL,
                ScopeLevel.entries.toSet(),
                listOf(OptionSpec("A", Strictness.DEFAULT), OptionSpec("B", Strictness.NEUTRAL)),
                VpClass.POLICY,
                setOf("tenant.admin", "platform.engineer"),
            ),
            def(
                INSTALLATION_ONLY,
                setOf(ScopeLevel.INSTALLATION),
                listOf(OptionSpec("X", Strictness.DEFAULT), OptionSpec("Y", Strictness.NEUTRAL)),
                VpClass.POLICY,
                setOf("platform.engineer"),
            ),
        ).associateBy { it.key }

    val catalogue =
        object : VariationPointCatalogue {
            override fun find(key: VariationPointKey) = rows[key]

            override fun keys() = rows.keys
        }
}
