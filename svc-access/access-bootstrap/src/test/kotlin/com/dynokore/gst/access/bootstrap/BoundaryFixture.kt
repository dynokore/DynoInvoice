package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.GrantManagerCheck
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.testcontainers.containers.PostgreSQLContainer
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

    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?) = this

    override fun instant(): Instant = Instant.now().plus(offset)
}

/** The permission names and rows of the tests. Test data: the production catalogue is empty until it is ruled. */
object FixtureMatrix {
    val VIEW = Permission("doc.view")
    val EXPORT = Permission("doc.export")
    val RESOLVE = Permission("exception.resolve")
    val ANNOTATE = Permission("doc.annotate")
    val APPROVE = Permission("pack.approve")
    val EDIT_PROFILE = Permission("profile.edit")

    val matrix =
        PermissionMatrix(
            definitions =
                listOf(
                    PermissionDefinition(VIEW, PermissionKind.READ),
                    PermissionDefinition(EXPORT, PermissionKind.READ),
                    PermissionDefinition(RESOLVE, PermissionKind.WRITE),
                    PermissionDefinition(ANNOTATE, PermissionKind.WRITE),
                    PermissionDefinition(APPROVE, PermissionKind.WRITE),
                    PermissionDefinition(EDIT_PROFILE, PermissionKind.WRITE),
                ),
            rows =
                mapOf(
                    Role.AUDITOR to mapOf(VIEW to Allowance.PERMITTED, EXPORT to Allowance.PERMITTED),
                    Role.TAX_APPROVER to
                        mapOf(
                            VIEW to Allowance.PERMITTED,
                            RESOLVE to Allowance.PERMITTED,
                            APPROVE to Allowance.PERMITTED,
                        ),
                    Role.ENTITY_ADMIN to mapOf(VIEW to Allowance.PERMITTED, EDIT_PROFILE to Allowance.PERMITTED),
                    Role.AGENT_OPERATOR to mapOf(VIEW to Allowance.WITHIN_SCOPE),
                ),
        )
}

/** Captures what the outbox writer returned, so the tests can feed the replica exactly what the service published. */
class CapturedEvents {
    val envelopes = CopyOnWriteArrayList<Envelope>()
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
class BoundaryTestConfiguration {
    @Bean
    fun clock(): Clock = MovableClock()

    @Bean
    fun permissionMatrix(): PermissionMatrix = FixtureMatrix.matrix

    @Bean
    fun grantManagerCheck(): GrantManagerCheck = GrantManagerCheck { true }

    @Bean
    fun consentSwitch(): ConsentSwitch = ConsentSwitch()

    @Bean
    fun consentCheck(switch: ConsentSwitch): ConsentCheck = switch.check()

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
    val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17").withLabel("inv.agent", "c-inv-t-4934")

    private val migrationsDir: Path = Path.of("..", "access-adapters", "src", "main", "resources", "db", "migration")
    private val provisioning: Path =
        Path.of(
            "..",
            "access-adapters",
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
            script.execute(Files.readString(provisioning))
            script.execute("CREATE SCHEMA IF NOT EXISTS access")
            Files.list(migrationsDir).use { files ->
                files
                    .filter {
                        it.fileName.toString().endsWith(
                            ".sql",
                        )
                    }.sorted()
                    .forEach { script.execute(Files.readString(it)) }
            }
            script.execute("ALTER ROLE access_runtime LOGIN PASSWORD '$RUNTIME_PASSWORD'")
            script.execute("GRANT CONNECT ON DATABASE ${container.databaseName} TO access_runtime")
            script.execute("GRANT USAGE ON SCHEMA access TO access_runtime")
            val key = TestSecrets.BINDING_KEY
            script.execute(
                "INSERT INTO access.binding_key (slot, secret) VALUES ('current', convert_to('$key', 'UTF8'))",
            )
        }
    }

    /** A superuser connection, for the assertions that must see every row. */
    fun <T> asSuperuser(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use(block)

    /** A connection as the runtime login with nothing bound: forced row-level security hides every row. */
    fun <T> asRuntime(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, "access_runtime", RUNTIME_PASSWORD).use(block)

    fun runner(): WebApplicationContextRunner =
        WebApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BoundaryTestConfiguration::class.java, AccessApplication::class.java)
            .withPropertyValues(
                "spring.datasource.url=${container.jdbcUrl}",
                "spring.datasource.username=access_runtime",
                "service-runtime.workload-identity.audience=access-svc",
                "gst.service=access-svc",
                "gst.instance-id=access-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.event-loops.enabled=false",
                "access.gateway.workload-subject=system:serviceaccount:gateway:api-gateway",
                "gst.bindings.secret-store.mount-root=${TestSecrets.mount(RUNTIME_PASSWORD)}",
                "service-runtime.datasource.credential=db/access_runtime",
            )

    fun entityNode(structureVersion: Long = 1): StructureNode =
        StructureNode(
            StructureNodeKind.ENTITY,
            null,
            retired = false,
            name = "entity",
            code = null,
            profile = emptyList(),
            structureVersion = structureVersion,
        )
}

/** A live consent lookup the tests flip: it reads its state at every question, so a withdrawal shows at the next check. */
class ConsentSwitch {
    private val live: MutableSet<EntityRef> = ConcurrentHashMap.newKeySet()

    fun grant(entity: EntityRef) {
        live += entity
    }

    fun withdraw(entity: EntityRef) {
        live -= entity
    }

    fun check(): ConsentCheck = ConsentCheck { it in live }
}
