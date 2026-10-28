package com.dynokore.gst.rules.served

import com.dynokore.gst.rules.adapters.web.EvaluationController
import com.dynokore.gst.rules.adapters.web.PacksController
import com.dynokore.gst.rules.bootstrap.RulesConfiguration
import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.Filter
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.web.client.RestClient
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.yaml.snakeyaml.Yaml
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One operation of the contract document (`contract/openapi.yaml` of rules-contract). */
data class ContractOperation(
    val method: String,
    val path: String,
    val operationId: String,
    val principal: String?,
    val callerAuth: String?,
    val syncEdge: List<String>,
) {
    val key: String get() = "$method $path"

    /** (a): no end-user token is read (owner answer INV-Q-8201, INV-AC-8260). */
    val untouched: Boolean get() = principal == "workload" || callerAuth == "signed-link" || callerAuth == "service-job"

    /** The text before `->` of each `x-sync-edge` element. */
    val edgeCallers: List<String> get() = syncEdge.map { it.substringBefore("->") }
}

/** The contract document the service's code was generated from, as shipped in rules-contract. */
object ContractDocument {
    const val RESOURCE = "contract/openapi.yaml"
    private val METHODS = setOf("get", "put", "post", "delete", "patch", "head", "options", "trace")

    fun read(text: String = Probes.documentText() ?: resourceText()): List<ContractOperation> {
        val root = Yaml().load<Map<String, Any?>>(text)
        val paths = root["paths"] as Map<*, *>
        return paths.flatMap { (path, item) ->
            (item as Map<*, *>).filterKeys { it in METHODS }.map { (method, op) ->
                val o = op as Map<*, *>
                val edge =
                    when (val raw = o["x-sync-edge"]) {
                        null -> emptyList()
                        is String -> listOf(raw)
                        is List<*> -> raw.map { it as String }
                        else -> error("x-sync-edge of $method $path is neither a string nor a list")
                    }
                ContractOperation(
                    (method as String).uppercase(),
                    path as String,
                    o["operationId"] as String,
                    o["x-principal"] as String?,
                    o["x-caller-auth"] as String?,
                    edge,
                )
            }
        }
    }

    private fun resourceText(): String =
        checkNotNull(
            ContractDocument::class.java.classLoader.getResourceAsStream(RESOURCE),
        ) { "no $RESOURCE on the classpath" }
            .use { String(it.readAllBytes(), Charsets.UTF_8) }
}

/**
 * Probe switches (test source set only). Honoured only when the environment variable `SERVED_PROBES=1` is set and the
 * untracked file `build/probe.properties` of the module exists; a normal or CI run ignores the file:
 * `document=<path>` replaces the contract document, `hide=<resource>,<resource>` removes classpath resources,
 * `properties=<k=v;k=v>` adds test properties.
 */
object Probes {
    private val file: Path = Path.of("build/probe.properties")
    private val values: Map<String, String> =
        if (System.getenv("SERVED_PROBES") == "1" && Files.isRegularFile(file)) {
            Files.readAllLines(file).filter { it.contains('=') }.associate {
                it.substringBefore('=') to
                    it.substringAfter('=')
            }
        } else {
            emptyMap()
        }

    fun documentText(): String? = values["document"]?.let { Files.readString(Path.of(it)) }

    fun hidden(): Set<String> =
        values["hide"]
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    fun properties(): List<String> = values["properties"]?.split(';')?.filter { it.isNotBlank() }.orEmpty()
}

private class HidingClassLoader(
    parent: ClassLoader,
    private val hidden: Set<String>,
) : ClassLoader(parent) {
    override fun getResource(name: String): URL? = if (name in hidden) null else super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> =
        if (name in hidden) Collections.emptyEnumeration() else super.getResources(name)

    override fun getResourceAsStream(name: String) = if (name in hidden) null else super.getResourceAsStream(name)
}

/** What a request that reached the handler boundary left behind. */
data class Reached(
    val method: String,
    val path: String,
    val tenant: String?,
    val actor: String?,
)

/** The counting doubles and the recording of the handler boundary. */
object Doubles {
    val resolverCalls = AtomicInteger()
    val reached = CopyOnWriteArrayList<Reached>()

    fun reset() {
        resolverCalls.set(0)
        reached.clear()
    }
}

val TENANT_A: TenantId = TenantId.of("0a000000-0000-0000-0000-00000000000a")
const val USER_A_TOKEN = "Bearer token-user-a"
const val REFUSED_TOKEN = "Bearer token-refused"

/** The workload token of a service; the subject a verified token carries is `subject-of-<service>`. */
fun workloadToken(service: String) = "wl-$service"

fun subjectOf(service: String) = "subject-of-$service"

/** A verified token whose subject no `callers` entry maps. */
const val UNMAPPED_WORKLOAD_TOKEN = "wl-unmapped"

/**
 * The doubles, a configuration of their own that is registered before [ServedApplication]: a configuration's imports
 * are processed before its own bean methods, so the `@ConditionalOnMissingBean` resolver and verifier of
 * service-runtime only see these when they come first.
 */
@Configuration(proxyBeanMethods = false)
class ServedDoubles {
    @Bean
    fun testEndUserPrincipalResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            Doubles.resolverCalls.incrementAndGet()
            if (authorization == USER_A_TOKEN) EndUserPrincipal("user-a", TENANT_A, emptySet()) else null
        }

    @Bean
    fun workloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token: WorkloadToken? ->
            val value = token?.value
            if (value != null && value.startsWith("wl-")) {
                WorkloadVerification.Verified(subjectOf(value.removePrefix("wl-")))
            } else {
                WorkloadVerification.Refused("no test token")
            }
        }
}

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(
    ServiceRuntimeConfiguration::class,
    ProductRuntimeConfiguration::class,
    RulesConfiguration::class,
    EvaluationController::class,
    PacksController::class,
)
class ServedApplication {
    /** After the binding (-50) and the caller filters: records the request that reached the handler boundary. */
    @Bean
    fun handlerBoundaryRecorder(tenants: TenantContext): FilterRegistrationBean<Filter> =
        FilterRegistrationBean<Filter>(
            Filter { request, response, chain ->
                val http = request as jakarta.servlet.http.HttpServletRequest
                val tenant = runCatching { tenants.current().toString() }.getOrNull()
                // currentActor() throws with nothing bound, like current()
                val actor = runCatching { tenants.currentActor() }.getOrNull()
                Doubles.reached.add(Reached(http.method, http.requestURI, tenant, actor))
                chain.doFilter(request, response)
            },
        ).apply { order = 0 }

    @Bean fun testMeterRegistry(): MeterRegistry = SimpleMeterRegistry()

    @Bean fun testInternalRestClientBuilder(): RestClient.Builder = RestClient.builder()
}

/** The service started as a servlet application on a free port, with the test doubles. */
class ServedService(
    callers: Collection<String>,
) : AutoCloseable {
    private val secrets: Path =
        Files.createTempDirectory("rules-secrets").also { root ->
            Files.createDirectories(root.resolve("tenancy"))
            Files.writeString(root.resolve("tenancy/binding-key-current"), "test-only-binding-key-0123456789ab")
        }
    private val context: ConfigurableApplicationContext
    val port: Int
    private val client = HttpClient.newHttpClient()

    init {
        val properties =
            mutableListOf(
                "gst.service=rules-svc",
                "gst.instance-id=rules-svc-0",
                "server.port=0",
                "management.server.port=-1",
                "service-runtime.workload-identity.audience=rules-svc",
                "service-runtime.modules.schema=rules",
                "service-runtime.idempotency.schema=rules",
                "inv.tenancy.reference-tables=rules.pack_registry,rules.pack_entry_source",
                "spring.datasource.url=jdbc:postgresql://localhost:1/rules",
                "spring.datasource.username=rules_runtime",
                "service-runtime.pool.profile=minimal",
                "gst.bindings.secret-store.mount-root=$secrets",
            )
        callers.sorted().forEachIndexed { i, service ->
            properties += "service-runtime.workload-identity.callers[$i].service=$service"
            properties += "service-runtime.workload-identity.callers[$i].subject=${subjectOf(service)}"
        }
        properties += Probes.properties()
        val loader = HidingClassLoader(ServedService::class.java.classLoader, Probes.hidden())
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        try {
            context =
                SpringApplicationBuilder(ServedDoubles::class.java, ServedApplication::class.java)
                    .web(WebApplicationType.SERVLET)
                    .resourceLoader(DefaultResourceLoader(loader))
                    .run(*properties.map { "--$it" }.toTypedArray()) // command line: wins over application.yaml
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
        port = (context as WebServerApplicationContext).webServer!!.port
    }

    /** The (method, path pattern) of each handler method the context maps, with the class that serves it. */
    fun mappedHandlers(): List<MappedHandler> =
        context
            .getBean(
                "requestMappingHandlerMapping",
                RequestMappingHandlerMapping::class.java,
            ).handlerMethods
            .flatMap { (info, handler) ->
                val patterns = info.pathPatternsCondition?.patternValues.orEmpty()
                // no method means every method: it can match no operation, so only the framework list passes it
                val methods =
                    info.methodsCondition.methods
                        .map { it.name }
                        .ifEmpty { listOf("ANY") }
                check(patterns.isNotEmpty()) { "handler ${handler.shortLogMessage} has no path pattern" }
                patterns.flatMap { p -> methods.map { m -> MappedHandler(m, p, handler) } }
            }

    fun call(
        operation: ContractOperation,
        workload: String?,
        authorization: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val path = operation.path.replace(Regex("\\{[^}]+}"), "x1")
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        builder.method(operation.method, HttpRequest.BodyPublishers.noBody())
        workload?.let { builder.header("X-Workload-Token", it) }
        authorization?.let { builder.header("Authorization", it) }
        extra.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    override fun close() {
        context.close()
        secrets.toFile().deleteRecursively()
    }
}

data class MappedHandler(
    val method: String,
    val path: String,
    val handler: HandlerMethod,
) {
    val key: String get() = "$method $path"
}

/**
 * The mapped handlers that are not operations of the contract, by exact fully-qualified class name, each with its
 * reason. Deny by default (P140): any other mapped handler without an operation fails the test. The test context is
 * the production composition, and production serves /error, so the entry stays instead of switching the page off.
 */
val FRAMEWORK_HANDLERS: Map<String, String> =
    mapOf(
        "org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController" to
            "Spring Boot error page /error, served in production, not an operation of the contract",
    )

/** The operation a mapped handler serves, by method and path template equality; null when none. */
fun operationOf(
    handler: MappedHandler,
    document: List<ContractOperation>,
): ContractOperation? {
    val found = document.filter { it.method == handler.method && it.path == handler.path }
    check(found.size <= 1) { "${handler.key} matches ${found.size} operations of the document" }
    return found.singleOrNull()
}

/** The mapped handlers with their operations; only a listed framework handler may lack one. */
fun servedOperations(
    service: ServedService,
    document: List<ContractOperation>,
): List<Pair<MappedHandler, ContractOperation>> = operationsOf(service.mappedHandlers(), document)

/** The pairing rule of [servedOperations] on a given handler list. */
fun operationsOf(
    handlers: List<MappedHandler>,
    document: List<ContractOperation>,
): List<Pair<MappedHandler, ContractOperation>> =
    handlers.mapNotNull { mapped ->
        if (mapped.handler.beanType.name in FRAMEWORK_HANDLERS.keys) return@mapNotNull null
        mapped to
            checkNotNull(operationOf(mapped, document)) {
                "the mapped handler ${mapped.key} (${mapped.handler.beanType.name}) " +
                    "has no operation in the contract document"
            }
    }
