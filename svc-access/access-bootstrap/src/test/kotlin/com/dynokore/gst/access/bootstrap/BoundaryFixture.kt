package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.StagedOutboxRow
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.ApplicationContext
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A clock the tests move forward; it never runs behind the database clock (the signed binding expires by it). */
class MovableClock : Clock() {
    @Volatile private var offset: Duration = Duration.ZERO

    @Volatile private var frozenAt: Instant? = null

    fun advance(by: Duration) {
        offset = offset.plus(by)
        frozenAt = frozenAt?.plus(by)
    }

    /** Stops the clock at its present reading, so a test can state an instant exactly; [advance] still moves it. */
    fun freeze(): Instant =
        Instant.now().plus(offset).truncatedTo(java.time.temporal.ChronoUnit.MICROS).also {
            frozenAt =
                it
        }

    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    override fun instant(): Instant = frozenAt ?: Instant.now().plus(offset)
}

/** The permission names and rows of the tests. Test data: the production catalogue is empty until it is ruled. */
object FixtureMatrix {
    val VIEW = Permission("doc.view")
    val EXPORT = Permission("doc.export")
    val RESOLVE = Permission("exception.resolve")
    val ANNOTATE = Permission("doc.annotate")
    val APPROVE = Permission("pack.approve")
    val EDIT_PROFILE = Permission("profile.edit")
    val VIEW_CONTENT = Permission("doc.view.content")
    val EXPORT_SUPPORT_BUNDLE = Permission("support.bundle.export")
    val USER_MANAGE = Permission("user.manage")

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
                    PermissionDefinition(VIEW_CONTENT, PermissionKind.READ),
                    PermissionDefinition(EXPORT_SUPPORT_BUNDLE, PermissionKind.READ),
                    PermissionDefinition(USER_MANAGE, PermissionKind.WRITE),
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
                    Role.DEPLOYMENT_OPERATOR to mapOf(EXPORT_SUPPORT_BUNDLE to Allowance.PERMITTED),
                    Role.TENANT_ADMIN to mapOf(USER_MANAGE to Allowance.PERMITTED),
                    Role.AGENT_OPERATOR to mapOf(VIEW to Allowance.WITHIN_SCOPE),
                ),
        )
}

/** Captures what the outbox writer returned, so the tests can feed the replica exactly what the service published. */
class CapturedEvents {
    val envelopes = CopyOnWriteArrayList<Envelope>()
}

/** The fixture rows in place of the catalogue table, and every role grantable (test data, INV-T-4934's tests). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("access.test.fixture-matrix", havingValue = "true", matchIfMissing = true)
class FixtureMatrixConfiguration {
    @Bean
    fun permissionMatrix(): PermissionMatrix = FixtureMatrix.matrix

    @Bean
    fun roleCatalogue(): RoleCatalogue = RoleCatalogue.ALL
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
class BoundaryTestConfiguration {
    @Bean
    fun clock(): Clock = MovableClock()

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

    // staging (an installation value's PolicyChanged) passes through unchanged
    override fun stage(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<StagedOutboxRow> = delegate.stage(events, context)
}

/** One PostgreSQL with the service schema migrated as the installation does, and the context runner over it. */
object BoundaryFixture {
    private const val RUNTIME_PASSWORD = "runtime-password-for-tests"
    val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17").withLabel("inv.agent", "c-inv-t-4938")

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
            script.execute("ALTER ROLE access_runtime LOGIN PASSWORD '$RUNTIME_PASSWORD'")
            val database = SqlIdentifier.require(container.databaseName, "database")
            script.execute("GRANT CONNECT ON DATABASE $database TO access_runtime")
            script.execute("GRANT USAGE ON SCHEMA access TO access_runtime")
            val key = TestSecrets.BINDING_KEY
            script.execute(
                "INSERT INTO access.binding_key (slot, secret) VALUES ('current', convert_to('$key', 'UTF8'))",
            )
        }
    }

    /**
     * The signed-binding functions of the schema, installed before the migrations as the installation does and as the
     * jOOQ codegen does (INV-Q-7418): the migrations never create them, so the text is tenancy-kernel's own.
     */
    private fun bindingFunctions(): Path {
        val file = Files.createTempDirectory("access-binding").resolve("binding-functions.sql")
        val ddl = BindingKeyMigration.ddl("access", "access_sys", "access_runtime")
        Files.writeString(
            file,
            "CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;\n" +
                "CREATE SCHEMA IF NOT EXISTS access AUTHORIZATION access_sys;\n" +
                "SET ROLE access_sys;\n$ddl\nRESET ROLE;\n",
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
        DriverManager.getConnection(container.jdbcUrl, "access_runtime", RUNTIME_PASSWORD).use(block)

    /**
     * The context over the real database. [catalogueMatrix] builds the matrix and the role catalogue from the
     * gst-catalogue table of [form] (INV-T-4935) instead of the fixture rows.
     */
    fun runner(
        catalogueMatrix: Boolean = false,
        form: String = "SAAS",
    ): WebApplicationContextRunner =
        WebApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(
                BoundaryTestConfiguration::class.java,
                AccessApplication::class.java,
            ).withPropertyValues(
                "access.test.fixture-matrix=${!catalogueMatrix}",
                "gst.form=$form",
                "spring.datasource.url=${container.jdbcUrl}",
                "spring.datasource.username=access_runtime",
                "service-runtime.workload-identity.audience=access-svc",
                "gst.service=access-svc",
                "gst.instance-id=access-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.event-loops.enabled=false",
                "access.gateway.workload-subject=system:serviceaccount:gateway:api-gateway",
                "access.identity-provider.issuer-uri=http://127.0.0.1:1/realms/boundary-test",
                "access.identity-provider.directory.base-uri=http://127.0.0.1:1",
                "access.identity-provider.directory.realm=boundary-test",
                "access.identity-provider.directory.client-id=access-svc-directory-reader",
                "access.identity-provider.directory.client-secret-ref=idp/directory-reader-secret",
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

/**
 * A live consent lookup the tests flip: it reads its state at every question, so a withdrawal shows at the next check.
 */
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

/** One grant to seed: the scope defaults to the whole tenant, the window to yesterday until thirty days ahead. */
data class SeedSpec(
    val identity: String,
    val role: Role,
    val scope: Scope? = null,
    val status: GrantStatus = GrantStatus.ACTIVE,
    val window: ((Instant) -> ValidityWindow)? = null,
)

/**
 * Seeds grants straight through the repository, the way an installation's first administrator exists before anyone can
 * call grantRole (the real caller check is bound in every test; no test binds an allow-all).
 */
object Seeds {
    fun grant(
        context: ApplicationContext,
        tenant: TenantId,
        spec: SeedSpec,
    ): String {
        val tenants = context.getBean(TenantContext::class.java)
        val tx = context.getBean(TransactionRunner::class.java)
        val grants = context.getBean(GrantRepository::class.java)
        val now = context.getBean(Clock::class.java).instant()
        val ref = IdentityRef(spec.identity)
        val role = spec.role
        val status = spec.status
        val scope = spec.scope ?: Scope.Tenant(TenantRef(tenant.toString()))
        val grantRef = "GRT-" + UUID.randomUUID().toString().take(8)
        val issued =
            Grant.issue(
                GrantHeader(
                    GrantRef(grantRef),
                    TenantRef(tenant.toString()),
                    ref,
                ),
                role,
                scope,
                spec.window?.invoke(now)
                    ?: ValidityWindow(now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30))),
            )
        return tenants.bind(tenant, "seed") {
            tx.inTransaction {
                val existing = grants.findByRepeatKey(ref, role.wire, scope)
                if (existing != null && existing.status == status) {
                    existing.ref.value
                } else {
                    grants.lockIdentity(ref, GranteeKind.STAFF)
                    grants.insert(issued)
                    if (status == GrantStatus.REVOKED) grants.update(issued, issued.revoked())
                    grantRef
                }
            }
        }
    }
}
