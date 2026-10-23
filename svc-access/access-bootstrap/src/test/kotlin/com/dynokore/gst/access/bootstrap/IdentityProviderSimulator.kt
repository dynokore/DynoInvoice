package com.dynokore.gst.access.bootstrap

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties

/**
 * The certification-kit identity-provider simulator (INV-T-8824): the pinned Keycloak with the realm `inv-cert`,
 * started
 * from the published image named by digest in `simulators.properties`. A bootstrap administrator is added through the
 * container environment so that a test can move a user between groups; the production adapter only ever holds the
 * read-only client `access-svc-directory-reader`.
 */
class IdentityProviderSimulator : AutoCloseable {
    val container: GenericContainer<*> =
        GenericContainer(DockerImageName.parse(imageReference()))
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN_USER)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withExposedPorts(PORT)
            .waitingFor(
                Wait
                    .forHttp("/realms/$REALM/.well-known/openid-configuration")
                    .forPort(PORT)
                    .withStartupTimeout(Duration.ofMinutes(3)),
            )

    private val http = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()

    fun start(): IdentityProviderSimulator = also { container.start() }

    val baseUri: String get() = "http://${container.host}:${container.getMappedPort(PORT)}"
    val issuer: String get() = "$baseUri/realms/$REALM"

    /** Removes [username] from the top-level group [group] through the administrator API. */
    fun removeFromGroup(
        username: String,
        group: String,
    ) {
        val token = adminToken()
        val user = get(token, "/admin/realms/$REALM/users?exact=true&username=${enc(username)}")
        val groups = get(token, "/admin/realms/$REALM/groups?exact=true&search=${enc(group)}")
        val delete =
            HttpRequest
                .newBuilder(
                    URI.create(
                        "$baseUri/admin/realms/$REALM/users/${user[0].path("id").asString()}/groups/" +
                            groups[0].path("id").asString(),
                    ),
                ).header("Authorization", "Bearer $token")
                .DELETE()
                .build()
        check(http.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode() == 204) { "the removal failed" }
    }

    /**
     * Configures the realm's test client to put [audience] in the tokens it gets, as an installation configures its
     * directory for the audience this service requires (STK-27).
     */
    fun addAudience(audience: String) {
        val admin = adminToken()
        val client = get(admin, "/admin/realms/$REALM/clients?clientId=cert-kit-test-client")[0].path("id").asString()
        val mapperJson =
            """{"name":"access-svc-audience","protocol":"openid-connect","protocolMapper":"oidc-audience-mapper",""" +
                """"config":{"included.custom.audience":"$audience","id.token.claim":"false","access.token.claim":"true"}}"""
        val request =
            HttpRequest
                .newBuilder(URI.create("$baseUri/admin/realms/$REALM/clients/$client/protocol-mappers/models"))
                .header("Authorization", "Bearer $admin")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapperJson))
                .build()
        check(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 201) { "the audience failed" }
    }

    /** An access token of a directory user signing in through the directory (password grant of the test client). */
    fun userToken(
        username: String,
        password: String = "TestPass!1",
    ): String {
        val form = "grant_type=password&client_id=cert-kit-test-client&username=${enc(
            username,
        )}&password=${enc(password)}"
        val request =
            HttpRequest
                .newBuilder(URI.create("$baseUri/realms/$REALM/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "the sign-in failed: ${response.statusCode()} ${response.body()}" }
        return mapper.readTree(response.body()).path("access_token").asString()
    }

    private fun adminToken(): String {
        val form = "grant_type=password&client_id=admin-cli&username=$ADMIN_USER&password=$ADMIN_PASSWORD"
        val request =
            HttpRequest
                .newBuilder(URI.create("$baseUri/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "the administrator token failed: ${response.statusCode()}" }
        return mapper.readTree(response.body()).path("access_token").asString()
    }

    private fun get(
        token: String,
        path: String,
    ) = mapper.readTree(
        http
            .send(
                HttpRequest
                    .newBuilder(URI.create(baseUri + path))
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body(),
    )

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    override fun close() = container.stop()

    companion object {
        const val REALM = "inv-cert"
        const val PORT = 8080
        const val CLIENT_ID = "access-svc-directory-reader"

        /** The documented test constant of the fixture: the container holds disposable data only. */
        const val CLIENT_SECRET = "access-svc-directory-reader-test-secret"
        private const val ADMIN_USER = "admin"
        private const val ADMIN_PASSWORD = "admin-test-password"

        fun imageReference(): String =
            Properties()
                .also {
                    checkNotNull(IdentityProviderSimulator::class.java.getResourceAsStream("/simulators.properties")) {
                        "simulators.properties is missing"
                    }.use(it::load)
                }.getProperty("identity-provider.image")
                .also { check(!it.isNullOrBlank()) { "identity-provider.image is not set" } }
    }
}
