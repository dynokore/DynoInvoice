package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** One operation of `gst/contracts/canonical/openapi.yaml` with the fields the criteria read. */
data class DocumentOperation(
    val id: String,
    val method: String,
    val path: String,
    val principal: String?,
    val callerAuth: String?,
    val edges: List<String>,
)

/** One handler method the application context maps: HTTP method and path pattern. */
data class MappedHandler(
    val method: String,
    val path: String,
    val handler: String,
)

/** The contract document as shipped in the `canonical-contract` module. */
object ContractDocument {
    private const val RESOURCE = "gst/contracts/canonical/openapi.yaml"
    private val methods = setOf("get", "put", "post", "delete", "patch", "head", "options")

    fun text(): String =
        checkNotNull(ContractDocument::class.java.classLoader.getResourceAsStream(RESOURCE)) { "$RESOURCE is missing" }
            .use { String(it.readAllBytes(), Charsets.UTF_8) }

    @Suppress("UNCHECKED_CAST")
    fun operations(document: String): List<DocumentOperation> {
        val paths = Yaml().load<Map<String, Any?>>(document)["paths"] as Map<String, Map<String, Any?>>
        return paths.flatMap { (path, item) ->
            item.filterKeys { it in methods }.mapNotNull { (method, raw) ->
                val operation = raw as Map<String, Any?>
                val edge = operation["x-sync-edge"]
                DocumentOperation(
                    id = operation["operationId"] as String,
                    method = method.uppercase(),
                    path = path,
                    principal = operation["x-principal"] as? String,
                    callerAuth = operation["x-caller-auth"] as? String,
                    edges =
                        when (edge) {
                            is String -> listOf(edge)
                            is List<*> -> edge.map { it as String }
                            else -> emptyList()
                        },
                )
            }
        }
    }

    /** The operation with this method and path template, by equality; none is a failure of the caller's test. */
    fun find(
        operations: List<DocumentOperation>,
        handler: MappedHandler,
    ): DocumentOperation? = operations.singleOrNull { it.method == handler.method && it.path == handler.path }
}

/** What a handler saw when the request reached it. */
data class Reach(
    val tenant: TenantId?,
    val actor: String?,
)

/** Counts the requests that reach a handler (the use case double of the criteria) and what was bound then. */
class ReachCounter(
    private val tenants: () -> TenantContext,
) : HandlerInterceptor {
    val reaches = mutableListOf<Reach>()

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler is HandlerMethod) {
            val context = tenants()
            val tenant = runCatching { context.current() }.getOrNull()
            reaches += Reach(tenant, runCatching { context.currentActor() }.getOrNull())
        }
        return true
    }
}

class ReachConfiguration(
    private val counter: ReachCounter,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(counter)
    }
}

/** The tokens of the doubles: `Bearer T-A` is user-a of tenant-a; every other token is refused. */
object EndUserDoubles {
    val tenantA = TenantId(UUID.nameUUIDFromBytes("tenant-a".toByteArray()))
    const val USER_A = "Bearer T-A"
    const val REFUSED = "Bearer T-REFUSED"
    val calls = AtomicInteger()

    val resolver =
        EndUserPrincipalResolver { header ->
            calls.incrementAndGet()
            if (header == USER_A) EndUserPrincipal("user-a", tenantA, setOf(EntityRef("E-001"))) else null
        }
}

/** The services the workload-token double maps, one subject each; the verifier accepts `W:<subject>` tokens. */
class WorkloadDoubles(
    val services: List<String>,
) {
    /** A name that is a named caller plus a suffix; no `x-sync-edge` holds it. */
    val lookalike = services.first() + "-two"

    private val subjects = services.associateWith { "subject-$it" } + (lookalike to "subject-lookalike")

    val verifier =
        WorkloadTokenVerifier { token ->
            if (token == null) {
                WorkloadVerification.Refused("missing")
            } else {
                WorkloadVerification.Verified(token.value.removePrefix("W:"))
            }
        }

    /** `service-runtime.workload-identity.callers`: the subject of each service. */
    fun properties(): List<String> =
        subjects.entries.flatMapIndexed { index, (service, subject) ->
            listOf(
                "service-runtime.workload-identity.callers[$index].service=$service",
                "service-runtime.workload-identity.callers[$index].subject=$subject",
            )
        }

    fun tokenOf(service: String): String = "W:" + checkNotNull(subjects[service]) { "no subject for $service" }

    /** A verified token that no entry maps. */
    val unmappedToken = "W:subject-nobody"
}

/** The answer of one call. */
data class Answer(
    val status: Int,
    val body: String,
)

/** The application context with the doubles, the container's filters in order, and the handlers it maps. */
class ServedService(
    private val mvc: MockMvc,
    val counter: ReachCounter,
    val tenants: TenantContext,
    val handlers: List<MappedHandler>,
    val classLoader: ClassLoader,
) {
    fun call(
        handler: MappedHandler,
        workload: String?,
        authorization: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): Answer {
        val uri = handler.path.replace(Regex("\\{[^}]+}"), "x1")
        val request =
            MockMvcRequestBuilders
                .request(
                    org.springframework.http.HttpMethod
                        .valueOf(handler.method),
                    uri,
                ).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .apply {
                    if (handler.method != "GET") content("""{"entityRef":"E-001"}""")
                    if (workload != null) header("X-Workload-Token", workload)
                    if (authorization != null) header("Authorization", authorization)
                    extra.forEach { (name, value) -> header(name, value) }
                }
        val response = mvc.perform(request).andReturn().response
        return Answer(response.status, response.contentAsString)
    }

    /** Rows of every table of the service schema, the outbox included: a refused call must leave it unchanged. */
    fun state(): Long =
        CanonicalDb.superuser().use { c ->
            c
                .createStatement()
                .executeQuery(
                    "select coalesce(sum((xpath('/row/c/text()', query_to_xml(" +
                        "format('select count(*) as c from %I.%I', table_schema, table_name), " +
                        "false, true, '')))[1]::text::bigint), 0) " +
                        "from information_schema.tables " +
                        "where table_schema = 'canonical' and table_type = 'BASE TABLE'",
                ).use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
        }

    fun nothingBound(): Boolean = runCatching { tenants.current() }.isFailure

    companion object {
        /** The mapped handlers of the service's own controllers (not the framework's error endpoint). */
        private fun mappedHandlers(context: WebApplicationContext): List<MappedHandler> =
            context
                .getBeansOfType(RequestMappingHandlerMapping::class.java)
                .values
                .flatMap { mapping ->
                    mapping.handlerMethods.flatMap { (info, method) ->
                        if (!method.beanType.name.startsWith("com.dynokore.gst.canonical.")) {
                            emptyList()
                        } else {
                            val patterns = checkNotNull(info.pathPatternsCondition).patternValues
                            val verbs = info.methodsCondition.methods.map { it.name }
                            patterns.flatMap { pattern -> verbs.map { MappedHandler(it, pattern, method.toString()) } }
                        }
                    }
                }.sortedWith(compareBy({ it.path }, { it.method }))

        /** MockMvc over the context with every servlet filter the container registers, in their order. */
        private fun mockMvcOf(
            context: org.springframework.context.ApplicationContext,
            web: WebApplicationContext,
        ): MockMvc {
            val filters =
                context
                    .getBeansOfType(FilterRegistrationBean::class.java)
                    .values
                    .sortedBy { it.order }
                    .map { it.filter as Filter }
            return MockMvcBuilders
                .webAppContextSetup(web)
                .also { it.addFilters<DefaultMockMvcBuilder>(*filters.toTypedArray()) }
                .build()
        }

        /**
         * Runs [block] over the service's context: the resolver and workload-token doubles, the container's own
         * filters in their order, [removed] resources hidden from the context's class loader.
         */
        fun run(
            workload: WorkloadDoubles,
            removed: List<String> = emptyList(),
            block: (ServedService) -> Unit,
        ) {
            val mountRoot = Files.createTempDirectory("canonical-served")
            lateinit var tenantsHolder: TenantContext
            val counter = ReachCounter { tenantsHolder }
            var runner =
                CanonicalContext
                    .web(mountRoot)
                    .withBean(EndUserPrincipalResolver::class.java, { EndUserDoubles.resolver })
                    .withBean(WorkloadTokenVerifier::class.java, { workload.verifier })
                    .withBean(ReachConfiguration::class.java, { ReachConfiguration(counter) })
                    .withPropertyValues(
                        "service-runtime.idempotency.schema=canonical",
                        *workload.properties().toTypedArray(),
                    )
            if (removed.isNotEmpty()) {
                val hidden = removed.map { ClassPathResource(it) }.toTypedArray()
                runner = runner.withClassLoader(FilteredClassLoader(*hidden))
            }
            runner.run { context ->
                context.startupFailure?.let { throw it }
                val web = context.sourceApplicationContext as WebApplicationContext
                tenantsHolder = context.getBean(TenantContext::class.java)
                val mvc = mockMvcOf(context, web)
                block(
                    ServedService(
                        mvc,
                        counter,
                        tenantsHolder,
                        mappedHandlers(web),
                        checkNotNull(context.classLoader),
                    ),
                )
            }
        }
    }
}

/** The class of a mapped operation by its contract markers, compared by equality: (a) left alone, (b) end-user. */
internal fun isLeftAlone(operation: DocumentOperation): Boolean =
    operation.principal == "workload" || operation.callerAuth == "signed-link" || operation.callerAuth == "service-job"

/** The caller names the contract documents name, for the workload-token double. */
internal fun callersOf(operations: List<DocumentOperation>): List<String> =
    operations
        .flatMap { it.edges }
        .flatMap { edge -> edge.substringBefore("->").split(',') }
        .map { it.trim().removePrefix("any-except:") }
        .filter { it != "any" }
        .distinct()
        .sorted()

/** The callers an `x-sync-edge` of the operation allows, the text before `->` of each element. */
internal fun allowedCallers(
    operation: DocumentOperation,
    universe: List<String>,
): Set<String> =
    operation.edges
        .map { it.substringBefore("->").trim() }
        .flatMap { element ->
            when {
                element == "any" -> universe
                element.startsWith("any-except:") -> universe - element.removePrefix("any-except:")
                else -> listOf(element)
            }
        }.toSet()
