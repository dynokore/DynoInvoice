package com.dynokore.gst.transport.bootstrap

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The certification-kit Access Point simulator (`simulators/access-point`, INV-T-2879) as the adapter under test,
 * started once per JVM from the image its `bootBuildImage` tags `certkit/access-point-simulator:dev`. It stands in
 * for the AP and the IRAS behind it. A missing image fails the test with that instruction, never skips it.
 */
object ApSimulator {
    private const val IMAGE = "certkit/access-point-simulator:dev"
    private const val PORT = 8080
    private val HTTP_SUCCESS = 200..299
    private val mapper = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(IMAGE))
            .withExposedPorts(PORT)
            .withLabel("inv.agent", System.getenv("INV_AGENT_NAME") ?: "svc-transport-tests")
            .waitingFor(Wait.forHttp("/__control/status").forStatusCode(200))
            .also {
                try {
                    it.start()
                } catch (e: org.testcontainers.containers.ContainerFetchException) {
                    throw IllegalStateException(
                        "the certification-kit AP simulator image $IMAGE is not in the local Docker daemon: " +
                            "build it with `gradle :simulators:access-point:bootBuildImage` in certification-kit",
                        e,
                    )
                }
            }
    }

    /** The base URL of the simulator as the adapter configuration names it. */
    val baseUrl: String get() = "http://${container.host}:${container.getMappedPort(PORT)}"

    fun reset() {
        send("POST", "/__control/reset", null)
    }

    fun control(
        path: String,
        body: String,
    ) {
        val status = send("POST", "/__control$path", body).statusCode()
        check(status in HTTP_SUCCESS) { "control call $path answered $status" }
    }

    fun state(): JsonNode = mapper.readTree(send("GET", "/__control/ap/state", null).body())

    /** Business requests the simulator handled since the last reset. */
    fun callCount(): Int = mapper.readTree(send("GET", "/__control/status", null).body()).get("callCount").asInt()

    /** Submissions the simulator accepted, newest last. */
    fun submissions(): List<JsonNode> = state().get("submissions").toList()

    private fun send(
        method: String,
        path: String,
        body: String?,
    ): HttpResponse<String> {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
