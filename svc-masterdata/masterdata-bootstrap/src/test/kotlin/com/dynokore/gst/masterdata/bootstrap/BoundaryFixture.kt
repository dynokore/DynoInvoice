package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.SqlIdentifier
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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

/** The roles of the test subjects, answered through service-runtime's [PolicyRoleResolver] port. */
object TestRoles {
    val roles: ConcurrentHashMap<String, Set<String>> =
        ConcurrentHashMap(
            mapOf(
                "IDN-ADMIN" to setOf("entity.admin"),
                "IDN-ADMIN-2" to setOf("tenant.admin"),
                "IDN-ADVISOR" to setOf("tax.approver"),
                "IDN-OUTSIDER" to setOf("entity.viewer"),
            ),
        )
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
class BoundaryTestConfiguration {
    @Bean
    fun policyRoleResolver(): PolicyRoleResolver =
        PolicyRoleResolver { actor, _, _, _, _ -> TestRoles.roles[actor].orEmpty() }

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
    private const val RUNTIME_PASSWORD = "runtime-password-for-tests"
    val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17").withLabel("inv.agent", "c-inv-t-4942")

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
            Files.list(migrationsDir).use { files ->
                files
                    .filter {
                        it.fileName.toString().endsWith(
                            ".sql",
                        )
                    }.sorted()
                    .forEach { runFile(it) }
            }
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
    private fun runFile(file: Path) {
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
                container.databaseName,
                "-f",
                target,
            )
        check(result.exitCode == 0) { "$file failed: ${result.stderr}" }
    }

    /** A superuser connection, for the assertions that must see every row. */
    fun <T> asSuperuser(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use(block)

    /** A connection as the runtime login with nothing bound: forced row-level security hides every row. */
    fun <T> asRuntime(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, "masterdata_runtime", RUNTIME_PASSWORD).use(block)

    fun runner(): WebApplicationContextRunner =
        WebApplicationContextRunner()
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
                "service-runtime.datasource.credential=db/masterdata_runtime",
            )
}
