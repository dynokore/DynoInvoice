package com.dynokore.gst.masterdata.bootstrap

import com.sun.net.httpserver.HttpServer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A certification-kit registry simulator (INV-T-2880) started once per JVM from the local image `certkit/<image>:dev`,
 * which `ci/pull-simulator.sh` pulls by the pinned digest. A missing image fails the test with that instruction, never
 * skips it. The control API resets the state, injects an outage and counts the business calls.
 */
internal class Simulator(
    private val image: String,
) {
    private val mapper = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("certkit/$image:dev"))
            .withExposedPorts(PORT)
            .withLabel("inv.agent", System.getenv("INV_AGENT_NAME") ?: "c-inv-t-4946")
            .waitingFor(Wait.forHttp("/__control/status").forStatusCode(OK))
            .also {
                try {
                    it.start()
                } catch (e: org.testcontainers.containers.ContainerFetchException) {
                    throw IllegalStateException(
                        "the certification-kit image certkit/$image:dev is not in the local Docker daemon: " +
                            "run `sh ci/pull-simulator.sh $image $image.digest`",
                        e,
                    )
                }
                Runtime.getRuntime().addShutdownHook(Thread { it.stop() })
            }
    }

    val baseUrl: String get() = "http://${container.host}:${container.getMappedPort(PORT)}"

    fun reset() {
        send("/__control/reset", "POST", null)
    }

    fun outage(enabled: Boolean) {
        send("/__control/faults/outage", "POST", "{\"enabled\":$enabled}")
    }

    /** The business requests the simulator has served since the last reset. */
    fun calls(): Int = mapper.readTree(send("/__control/status", "GET", null)).get("callCount").asInt()

    private fun send(
        path: String,
        method: String,
        body: String?,
    ): String {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in OK..NO_CONTENT) { "control call $path answered ${response.statusCode()}" }
        return response.body()
    }

    private companion object {
        const val PORT = 8080
        const val OK = 200
        const val NO_CONTENT = 204
    }
}

/** The two simulators the registry tests bind to. */
internal object Simulators {
    val directory = Simulator("peppol-directory-simulator")
    val register = Simulator("gst-register-simulator")

    /** The Peppol ID of the simulator's registered participant and of its not-registered one (contract fixtures). */
    const val PEPPOL_FOUND = "0195:201812345K"
    const val PEPPOL_NOT_FOUND = "0195:200000001A"

    /** The UENs of the register simulator's registered, deregistered and not-registered entries. */
    const val UEN_REGISTERED = "201812345K"
    const val UEN_NOT_REGISTERED = "201900003F"

    fun resetAll() {
        directory.reset()
        register.reset()
        directory.outage(false)
        register.outage(false)
    }
}

/**
 * A contract stub of a registry (the recorded wire shape `GET /lookup/{value}`) for the answers the simulators'
 * fixtures cannot give: an entry that changes, an AP reference, a registered name. It counts the GETs and every other
 * method, so a test can assert that the System never writes to a directory.
 */
internal class RegistryStub : AutoCloseable {
    private class Answer(
        val status: Int,
        val body: String,
    )

    private val answers = ConcurrentHashMap<String, Answer>()
    private val gets = AtomicInteger()
    private val writes = AtomicInteger()

    @Volatile private var down = false
    private val unavailable = ConcurrentHashMap.newKeySet<String>()
    private val delays = ConcurrentHashMap<String, Long>()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0).also { server ->
            server.createContext("/") { exchange ->
                if (exchange.requestMethod == "GET") gets.incrementAndGet() else writes.incrementAndGet()
                val value = exchange.requestURI.path.removePrefix("/lookup/")
                delays[value]?.let { Thread.sleep(it) }
                val answer =
                    if (down ||
                        value in unavailable
                    ) {
                        Answer(UNAVAILABLE, "")
                    } else {
                        answers[value]
                            ?: Answer(NOT_FOUND, NOT_FOUND_BODY)
                    }
                val bytes = answer.body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(answer.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
        }

    /** The base URL as seen from this JVM (the stub runs in the test process). */
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun found(
        value: String,
        body: String,
    ) {
        answers[value] = Answer(OK, body)
    }

    fun notFound(value: String) {
        answers.remove(value)
    }

    /** The register is unreachable for [value] only (a 503), the others answer as configured. */
    fun unavailable(value: String) {
        unavailable += value
    }

    /** The answer for [value] comes [millis] late. */
    fun delay(
        value: String,
        millis: Long,
    ) {
        delays[value] = millis
    }

    fun down(enabled: Boolean) {
        down = enabled
    }

    fun gets(): Int = gets.get()

    fun writes(): Int = writes.get()

    override fun close() = server.stop(0)

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val UNAVAILABLE = 503
        const val NOT_FOUND_BODY = "{\"status\":\"NOT_REGISTERED\"}"
    }
}
