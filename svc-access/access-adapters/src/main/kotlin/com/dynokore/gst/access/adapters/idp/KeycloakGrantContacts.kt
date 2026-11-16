package com.dynokore.gst.access.adapters.idp

import com.dynokore.gst.access.application.grant.GrantContacts
import com.dynokore.gst.access.domain.grant.IdentityRef
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The production adapter of [GrantContacts] (INV-REQ-893; the directory is the system of record of addresses, data
 * minimisation): reads `GET /admin/realms/{realm}/users?username=<name>&exact=true` of the Keycloak Admin REST API with
 * the client-credentials token of the read-only client of [KeycloakDirectoryGroupMembers] (the same settings).
 *
 * The address is answered only when exactly one user comes back, its `username` equals the name by equality (Keycloak
 * lower-cases user names, so a look-alike that differs in case is no match) and its `email` is not blank; otherwise
 * none. An HTTP error or a body that is not a list throws: a failed read is never an unknown address. Nothing is
 * logged here, so no address reaches a log.
 */
class KeycloakGrantContacts(
    baseUri: String,
    private val realm: String,
    private val clientId: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
    private val clientSecret: () -> String,
) : GrantContacts {
    private val base = baseUri.trimEnd('/')
    private val mapper = JsonMapper.builder().build()

    override fun addressOf(identity: IdentityRef): String? {
        val name = identity.value
        val users = getJson(accessToken(), "/admin/realms/${enc(realm)}/users?username=${enc(name)}&exact=true")
        check(users.isArray) { "the user search did not come back as a list" }
        val user = if (users.size() == 1) users[0] else null
        return user
            ?.takeIf { it.path("username").asString() == name }
            ?.path("email")
            ?.asString()
            ?.takeIf { it.isNotBlank() }
    }

    private fun accessToken(): String {
        val form = "grant_type=client_credentials&client_id=${enc(clientId)}&client_secret=${enc(clientSecret())}"
        val request =
            HttpRequest
                .newBuilder(URI.create("$base/realms/${enc(realm)}/protocol/openid-connect/token"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val token = mapper.readTree(send(request)).path("access_token").asString()
        check(token.isNotEmpty()) { "the token endpoint returned no access token" }
        return token
    }

    private fun getJson(
        token: String,
        path: String,
    ): JsonNode =
        mapper.readTree(
            send(
                HttpRequest
                    .newBuilder(URI.create(base + path))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build(),
            ),
        )

    private fun send(request: HttpRequest): String {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(
            response.statusCode() == HTTP_OK,
        ) { "the directory answered ${response.statusCode()} to ${request.method()}" }
        return response.body()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        const val HTTP_OK = 200
    }
}
