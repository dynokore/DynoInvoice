package com.dynokore.gst.access.bootstrap.served

import com.dynokore.gst.access.bootstrap.BoundaryFixture
import com.dynokore.gst.access.bootstrap.CapturedEvents
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.Filter
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One operation of the contract document `gst/contracts/access/openapi.yaml` of access-contract. */
data class ContractOperation(
    val method: String,
    val path: String,
    val operationId: String,
    val principal: String?,
    val callerAuth: String?,
    val syncEdge: List<String>,
    val raw: Map<String, Any?>,
) {
    val key: String get() = "$method $path"

    /** Class (a): no end-user token is read (owner answer INV-Q-8201, INV-AC-8260). */
    val untouched: Boolean get() = principal == "workload" || callerAuth == "signed-link" || callerAuth == "service-job"

    /** The text before `->` of each `x-sync-edge` element. */
    val edgeCallers: List<String> get() = syncEdge.map { it.substringBefore("->") }
}

/** The contract document the code was generated from, as shipped in access-contract. */
object ContractDocument {
    const val RESOURCE = "gst/contracts/access/openapi.yaml"
    private val METHODS = setOf("get", "put", "post", "delete", "patch", "head", "options", "trace")

    fun read(text: String = Probes.documentText() ?: resourceText()): List<ContractOperation> {
        val root = Yaml().load<Map<String, Any?>>(text)
        val paths = root["paths"] as Map<*, *>
        return paths.flatMap { (path, item) ->
            (item as Map<*, *>).filterKeys { it in METHODS }.map { (method, op) ->
                @Suppress("UNCHECKED_CAST")
                val o = op as Map<String, Any?>
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
                    o,
                )
            }
        }
    }

    private fun resourceText(): String =
        checkNotNull(ContractDocument::class.java.classLoader.getResourceAsStream(RESOURCE)) {
            "no $RESOURCE on the classpath"
        }.use { String(it.readAllBytes(), Charsets.UTF_8) }
}

/**
 * Probe switches, read from the untracked file `build/probe.properties` of the module (absent in a normal run):
 * `document=<path>` replaces the contract document the test reads, `hide=<resource>,<resource>` removes classpath
 * resources from the application context, `properties=<k=v;k=v>` adds test properties.
 */
object Probes {
    private val file: Path = Path.of("build/probe.properties")
    private val values: Map<String, String> =
        if (Files.isRegularFile(file)) {
            Files
                .readAllLines(file)
                .filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        } else {
            emptyMap()
        }

    fun documentText(): String? = values["document"]?.let { Files.readString(Path.of(it)) }

    fun hidden(): List<String> = values["hide"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    fun properties(): List<String> = values["properties"]?.split(';')?.filter { it.isNotBlank() }.orEmpty()
}

/** What a request that reached a use case double left behind. */
data class Reached(
    val operation: String,
    val tenant: String?,
    val actor: String?,
)

/** The counting doubles of the test: the end-user resolver and the use cases (the controller beans). */
object Doubles {
    val resolverCalls = AtomicInteger()
    val reached = CopyOnWriteArrayList<Reached>()

    fun reset() {
        resolverCalls.set(0)
        reached.clear()
    }
}

val TENANT_A: TenantId = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
const val USER_A_TOKEN = "Bearer token-user-a"
const val REFUSED_TOKEN = "Bearer token-refused"
const val UNMAPPED_WORKLOAD_TOKEN = "wl-unmapped"

/** The workload token of a service; the subject a verified token carries is `subject-of-<service>`. */
fun workloadToken(service: String) = "wl-$service"

fun subjectOf(service: String) = "subject-of-$service"

/** Verifies a test token `wl-<service>` as the subject `subject-of-<service>`; refuses every other value. */
private val TEST_VERIFIER =
    WorkloadTokenVerifier { token: WorkloadToken? ->
        val value = token?.value
        if (value != null && value.startsWith("wl-")) {
            WorkloadVerification.Verified(subjectOf(value.removePrefix("wl-")))
        } else {
            WorkloadVerification.Refused("no test token")
        }
    }

/** Maps the token of user-a to tenant-a and refuses every other token; counts its calls. */
private val TEST_RESOLVER =
    EndUserPrincipalResolver { authorization ->
        Doubles.resolverCalls.incrementAndGet()
        if (authorization == USER_A_TOKEN) EndUserPrincipal("user-a", TENANT_A, emptySet()) else null
    }

/**
 * The doubles, not annotated `@Configuration` on purpose: the component scan of the application would otherwise pick
 * this class up in every other test context. The resolver maps one token to tenant-a and user-a and refuses every
 * other token; the verifier maps a test token to a subject; every controller bean is replaced by a double that counts
 * the calls of its operations and records the tenant and actor bound at that moment (the controllers hold the use cases
 * and their own checks, which are proven by their own tests).
 */
class ServedDoubles {
    @Bean
    fun controllerDoubles(tenants: ObjectProvider<TenantContext>): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessBeforeInitialization(
                bean: Any,
                beanName: String,
            ): Any {
                // the verifier and the resolver of the composition root are replaced, not joined by a second bean
                return when {
                    bean is WorkloadTokenVerifier -> TEST_VERIFIER
                    bean is EndUserPrincipalResolver -> TEST_RESOLVER
                    isController(bean) -> controllerDouble(bean)
                    else -> bean
                }
            }

            private fun isController(bean: Any) =
                AnnotatedElementUtils.hasAnnotation(bean.javaClass, RestController::class.java)

            private fun controllerDouble(bean: Any): Any =
                Mockito.mock(
                    bean.javaClass,
                    Answer<Any?> { call ->
                        if (AnnotatedElementUtils.hasAnnotation(call.method, RequestMapping::class.java)) {
                            val context = tenants.getObject()
                            Doubles.reached.add(
                                Reached(
                                    call.method.name,
                                    runCatching { context.current().toString() }.getOrNull(),
                                    runCatching { context.currentActor() }.getOrNull(),
                                ),
                            )
                        }
                        null
                    },
                )
        }
}

/** The handler methods the application context maps, with the way a test sends a request to an operation. */
class ServedContext(
    private val context: AssertableWebApplicationContext,
) {
    val tenants: TenantContext = context.getBean(TenantContext::class.java)
    private val mvc: MockMvc =
        MockMvcBuilders
            .webAppContextSetup(context.sourceApplicationContext as WebApplicationContext)
            .addFilters<DefaultMockMvcBuilder>(*filters(context).toTypedArray())
            .build()
    private val events = context.getBean(CapturedEvents::class.java)

    /** The (method, path pattern) of each handler method the context maps. */
    fun mappedHandlers(): List<MappedHandler> =
        context
            .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java)
            .handlerMethods
            .flatMap { (info, handler) ->
                val patterns = info.pathPatternsCondition?.patternValues.orEmpty()
                // a mapping with no method accepts every method: it matches no operation (the framework list only)
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
    ): MockHttpServletResponse {
        var path = operation.path
        val parameters = (operation.raw["parameters"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        parameters.filter { it["in"] == "path" }.forEach { path = path.replace("{" + it["name"] + "}", "PROBE-1") }
        val request = MockMvcRequestBuilders.request(HttpMethod.valueOf(operation.method), path)
        parameters.filter { it["in"] == "query" && it["required"] == true }.forEach {
            request.queryParam(it["name"].toString(), "probe")
        }
        if (parameters.any { (it["\$ref"] as? String)?.endsWith("IdempotencyKey") == true }) {
            request.header("Idempotency-Key", UUID.randomUUID().toString())
        }
        if (operation.raw["requestBody"] != null) {
            val body = JsonMapper().writeValueAsString(exampleBody(operation))
            request.contentType(MediaType.APPLICATION_JSON).content(body)
        }
        workload?.let { request.header("X-Workload-Token", it) }
        authorization?.let { request.header("Authorization", it) }
        extra.forEach { (k, v) -> request.header(k, v) }
        return mvc.perform(request).andReturn().response
    }

    @Suppress("UNCHECKED_CAST")
    private fun exampleBody(operation: ContractOperation): Any {
        val body = operation.raw["requestBody"] as Map<String, Any?>
        val json = (body["content"] as Map<String, Map<String, Any?>>)["application/json"].orEmpty()
        val examples = json["examples"] as? Map<String, Map<String, Any?>>
        return checkNotNull(examples?.values?.firstOrNull()?.get("value")) { "${operation.key}: no request example" }
    }

    /** The rows of every table of the access schema, and the events captured, as one comparable value. */
    fun stored(): Map<String, Long> =
        BoundaryFixture.asSuperuser { connection ->
            val counts = sortedMapOf<String, Long>()
            connection.createStatement().use { s ->
                s
                    .executeQuery(
                        "SELECT table_name, (xpath('/row/c/text()', query_to_xml(" +
                            "format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))" +
                            "[1]" +
                            "::text::bigint FROM information_schema.tables " +
                            "WHERE table_schema = 'access' AND table_type = 'BASE TABLE'",
                    ).use {
                        while (it.next()) counts[it.getString(1)] = it.getLong(2)
                    }
            }
            check(counts.isNotEmpty()) { "the access schema has no table" }
            counts + ("captured events" to events.envelopes.size.toLong())
        }

    private companion object {
        /** Every filter registration of the context, in the order the container applies them. */
        fun filters(context: AssertableWebApplicationContext): List<Filter> =
            context
                .getBeansOfType(FilterRegistrationBean::class.java)
                .values
                .filter { it.isEnabled }
                .sortedBy { it.order }
                .map { it.filter as Filter }
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
 * The framework handlers that are not operations of the contract: the Spring Boot error page. Deny by default (P140):
 * any other mapped handler outside the service's own controllers fails the test.
 */
private val FRAMEWORK_HANDLERS = setOf("org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController")

/** The contract operation each mapped handler serves, matched by method and path template by equality. */
fun operationOf(
    handler: MappedHandler,
    document: List<ContractOperation>,
): ContractOperation? {
    val found = document.filter { it.method == handler.method && it.path == handler.path }
    check(found.size <= 1) { "${handler.key} matches ${found.size} operations of the document" }
    return found.singleOrNull()
}

/** The mapped handlers that serve operations, each with its operation; only a listed framework handler is skipped. */
fun servedOperations(
    served: ServedContext,
    document: List<ContractOperation>,
): List<Pair<MappedHandler, ContractOperation>> =
    served.mappedHandlers().mapNotNull { mapped ->
        if (mapped.handler.beanType.name in FRAMEWORK_HANDLERS) return@mapNotNull null
        mapped to
            checkNotNull(operationOf(mapped, document)) {
                "the mapped handler ${mapped.key} (${mapped.handler.beanType.name}) has no operation in the document"
            }
    }

/**
 * Runs [block] over the service's application context with the doubles of [ServedDoubles], the workload callers
 * [callers] mapped to the subjects `subject-of-<service>` (`service-runtime.workload-identity.callers`) and the
 * resources of the probe file hidden from the class path.
 */
fun withServedService(
    callers: Collection<String>,
    block: (ServedContext) -> Unit,
) {
    val hidden = Probes.hidden().map { ClassPathResource(it) }
    val properties =
        callers.sorted().flatMapIndexed { i, service ->
            listOf(
                "service-runtime.workload-identity.callers[$i].service=$service",
                "service-runtime.workload-identity.callers[$i].subject=${subjectOf(service)}",
            )
        } + Probes.properties()
    var runner =
        BoundaryFixture
            .runner()
            .withUserConfiguration(ServedDoubles::class.java)
            .withPropertyValues(*properties.toTypedArray())
    if (hidden.isNotEmpty()) runner = runner.withClassLoader(FilteredClassLoader(*hidden.toTypedArray()))
    runner.run { context ->
        context.startupFailure?.let { throw it }
        Doubles.reset()
        block(ServedContext(context))
    }
}
