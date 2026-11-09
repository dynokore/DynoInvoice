package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.permission.OperationPermissionKeys
import com.dynokore.gst.masterdata.application.imports.ImportEvidence
import com.dynokore.gst.masterdata.application.permission.MasterdataPermissions
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.SqlIdentifier
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A clock the tests move forward; it never runs behind the database clock (the signed binding expires by it). */
class MovableClock : Clock() {
    @Volatile private var offset: Duration = Duration.ZERO

    fun advance(by: Duration) {
        offset = offset.plus(by)
    }

    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    override fun instant(): Instant = Instant.now().plus(offset)
}

/** Captures what the outbox writer returned, so the tests can feed the replica exactly what the service published. */
class CapturedEvents {
    val envelopes = CopyOnWriteArrayList<Envelope>()
}

/** A test-side roles lookup by actor and entity; the runner feeds it to the replica fixture [TestAuthority]. */
fun interface TestRoleResolver {
    fun rolesOf(
        actor: String,
        entity: String,
    ): Set<String>
}

/** The permission keys of the test subjects: the two operators hold all of them, any other person none. */
object TestPermissions {
    private val all =
        setOf(
            MasterdataPermissions.GAP_REPORT_VIEW,
            MasterdataPermissions.GAP_REPORT_EXPORT,
            MasterdataPermissions.COUNTERPARTY_IMPORT_RUN,
            MasterdataPermissions.MASTERDATA_EXCEPTION_RESOLVE,
            OperationPermissionKeys.EXCHANGE_RATE_ENTER,
            OperationPermissionKeys.EXCHANGE_RATE_IMPORT,
            OperationPermissionKeys.CONNECTION_CONFIGURE,
            OperationPermissionKeys.CONNECTION_VIEW,
            OperationPermissionKeys.CONNECTION_TEST,
        )

    /** A second person holding the same keys, for the criteria that compare a starter with another principal. */
    const val OTHER_OPERATOR = "IDN-OPERATOR-2"

    /** A person holding only the look-alike key `exchange-rate.imports` (plural), which is not `exchange-rate.import`. */
    const val LOOKALIKE_IMPORTER = "IDN-LOOKALIKE-IMPORTER"

    fun keysOf(person: String): Set<String> =
        when (person) {
            OPERATOR, OTHER_OPERATOR -> all
            LOOKALIKE_IMPORTER -> setOf("exchange-rate.imports")
            else -> emptySet()
        }
}

/**
 * The roles of the test subjects, held as grants of the replica fixture [TestAuthority] (the decision is
 * `entity-config.confirm` through `AuthorityReplica.decide`).
 */
object TestRoles {
    val roles: ConcurrentHashMap<String, Set<String>> =
        ConcurrentHashMap(
            mapOf(
                "IDN-ADMIN" to setOf("entity.admin"),
                "IDN-ADMIN-2" to setOf("tenant.admin"),
                "IDN-ADVISOR" to setOf("tax.approver"),
                "IDN-INTEGRATOR" to setOf("integration.engineer"),
                "IDN-OUTSIDER" to setOf("entity.viewer"),
            ),
        )
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
class BoundaryTestConfiguration {
    @Bean
    fun importFaults(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any =
                if (bean is ImportEvidence) {
                    ImportEvidence { entity, importId, hash ->
                        ImportFault.delayMillis[entity]?.let { Thread.sleep(it) }
                        check(entity !in ImportFault.failing) { "import apply made to fail" }
                        bean.recorded(entity, importId, hash)
                    }
                } else {
                    bean
                }
        }

    @Bean
    fun clock(): Clock = MovableClock()

    @Bean
    fun capturedEvents(): CapturedEvents = CapturedEvents()

    @Bean
    fun capturingOutbox(captured: CapturedEvents): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any =
                if (bean is OutboxWriter &&
                    bean !is CapturingOutboxWriter
                ) {
                    CapturingOutboxWriter(bean, captured)
                } else {
                    bean
                }
        }
}

/** The faults a test arms on the last step of an import apply, per entity: a delay past `x-timeout`, or a throw. */
object ImportFault {
    val delayMillis = ConcurrentHashMap<String, Long>()
    val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()
}

class CapturingOutboxWriter(
    private val delegate: OutboxWriter,
    private val captured: CapturedEvents,
) : OutboxWriter {
    override fun append(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<Envelope> = delegate.append(events, context).also { captured.envelopes.addAll(it) }
}

/** One PostgreSQL with the service schema migrated as the installation does, and the context runner over it. */
object BoundaryFixture {
    internal const val RUNTIME_PASSWORD = "runtime-password-for-tests"
    val container: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:17")
            .withStartupTimeout(Duration.ofMinutes(PG_START_TIMEOUT_MINUTES))
            .withStartupAttempts(PG_START_ATTEMPTS)
            .withCommand(*pgFastCommand)
            .withLabel("inv.agent", "c-inv-t-4942")

    private val migrationsDir: Path =
        Path.of(
            "..",
            "masterdata-adapters",
            "src",
            "main",
            "resources",
            "db",
            "migration",
        )
    private val provisioning: Path =
        Path.of(
            "..",
            "masterdata-adapters",
            "src",
            "test",
            "resources",
            "db",
            "provisioning.sql",
        )

    init {
        container.start()
        Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.autoCommit = true
            val script = connection.createStatement()
            runFile(provisioning)
            runFile(bindingFunctions())
            migrate()
            script.execute("ALTER ROLE masterdata_runtime LOGIN PASSWORD '$RUNTIME_PASSWORD'")
            val database = SqlIdentifier.require(container.databaseName, "database")
            script.execute("GRANT CONNECT ON DATABASE $database TO masterdata_runtime")
            script.execute("GRANT USAGE ON SCHEMA masterdata TO masterdata_runtime")
            val key = TestSecrets.BINDING_KEY
            script.execute(
                "INSERT INTO masterdata.binding_key (slot, secret) VALUES ('current', convert_to('$key', 'UTF8'))",
            )
        }
    }

    /**
     * The migration files applied by Flyway as the superuser of the container, the tool production uses (twelve-factor
     * dev/prod parity, INV-T-10865): a psql run would keep a raw U+FEFF that Flyway drops. The binding functions the
     * installer left in the schema are baselined at 0, so V1 still runs.
     */
    private fun migrate(
        jdbcUrl: String = container.jdbcUrl,
        target: String? = null,
    ) {
        Flyway
            .configure()
            .dataSource(jdbcUrl, container.username, container.password)
            .also { if (target != null) it.target(target) }
            .schemas("masterdata")
            .defaultSchema("masterdata")
            .locations("filesystem:" + migrationsDir.toAbsolutePath().normalize())
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()
    }

    /**
     * The signed-binding functions of the schema, installed before the migrations as the installation does and as the
     * jOOQ codegen does (INV-Q-7418): the migrations never create them, so the text is tenancy-kernel's own.
     */
    private fun bindingFunctions(): Path {
        val file = Files.createTempDirectory("masterdata-binding").resolve("binding-functions.sql")
        val ddl = BindingKeyMigration.ddl("masterdata", "masterdata_sys", "masterdata_runtime")
        Files.writeString(
            file,
            "CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;\n" +
                "CREATE SCHEMA IF NOT EXISTS masterdata AUTHORIZATION masterdata_sys;\n" +
                "SET ROLE masterdata_sys;\n$ddl\nRESET ROLE;\n",
        )
        file.toFile().deleteOnExit()
        file.parent.toFile().deleteOnExit()
        return file
    }

    /** Runs a SQL file as the container's superuser through psql, stopping at the first error. */
    private fun runFile(
        file: Path,
        database: String = container.databaseName,
    ) {
        val target = "/tmp/${file.fileName}"
        container.copyFileToContainer(MountableFile.forHostPath(file), target)
        val result =
            container.execInContainer(
                "psql",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                container.username,
                "-d",
                database,
                "-f",
                target,
            )
        check(result.exitCode == 0) { "$file failed: ${result.stderr}" }
    }

    /**
     * A second, empty database of the container migrated up to [target] (all migrations when null), with the binding
     * functions installed first as for the main one; the JDBC URL is returned (INV-T-8118 migration test).
     */
    fun migratedDatabase(
        name: String,
        target: String?,
    ): String {
        asSuperuser { it.createStatement().execute("CREATE DATABASE $name") }
        runFile(bindingFunctions(), name)
        val url = container.jdbcUrl.replaceAfterLast('/', name)
        migrate(url, target)
        return url
    }

    /** A superuser connection, for the assertions that must see every row. */
    fun <T> asSuperuser(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use(block)

    /** A connection as the runtime login with nothing bound: forced row-level security hides every row. */
    fun <T> asRuntime(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, "masterdata_runtime", RUNTIME_PASSWORD).use(block)

    /** The resolver of the test subjects' roles; `null` registers none, as production code does (INV-Q-8121 A). */
    val testResolver = TestRoleResolver { actor, _ -> TestRoles.roles[actor].orEmpty() }

    fun runner(resolver: TestRoleResolver? = testResolver): WebApplicationContextRunner =
        WebApplicationContextRunner()
            .let {
                if (resolver != null) {
                    val clock = MovableClock()
                    val replica = TestAuthority.newReplica(clock)
                    val feeder = TestAuthority.Feeder(replica, clock)
                    it
                        .withBean(AuthorityReplica::class.java, { replica })
                        .withBean(
                            TestAuthority.FeedingProcessor::class.java,
                            { TestAuthority.FeedingProcessor(feeder, resolver) },
                        )
                } else {
                    it
                }
            }.withBean(TenantPermission::class.java, {
                TenantPermission { person, key ->
                    key in
                        TestPermissions.keysOf(person)
                }
            })
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BoundaryTestConfiguration::class.java, MasterdataApplication::class.java)
            .withPropertyValues(
                "spring.datasource.url=${container.jdbcUrl}",
                "spring.datasource.username=masterdata_runtime",
                "service-runtime.workload-identity.audience=masterdata-svc",
                "gst.service=masterdata-svc",
                "gst.instance-id=masterdata-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.event-loops.enabled=false",
                "gst.bindings.secret-store.mount-root=${TestSecrets.mount(RUNTIME_PASSWORD)}",
                "gst.bindings.telemetry-export=bundled-otel",
                "gst.bindings.telemetry-export-otlp-endpoint=http://localhost:4318",
                "service-runtime.datasource.credential=db/masterdata_runtime",
                "inv.masterdata.job-scheduling.enabled=false",
            )
}

// CI-FAST: a loaded CI host can need more than the 60 s Testcontainers default for initdb and its restart; a failed
// start is retried with a fresh container, and the throwaway test database skips fsync.
private const val PG_START_TIMEOUT_MINUTES = 3L
private const val PG_START_ATTEMPTS = 3
private val pgFastCommand =
    arrayOf("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
