package com.dynokore.gst.access.adapters.idp

import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
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
 * The production adapter of [DirectoryGroupMembers] (INV-Q-8833 A, INV-Q-8835 A): reads the members of a Keycloak group
 * through the Admin REST API (`GET /admin/realms/{realm}/groups/{id}/members`) with the client-credentials token of the
 * read-only service-account client. A directory that is not Keycloak is federated or brokered into Keycloak, so this is
 * the only reader. A group is named by its top-level group name; the id is looked up, and a name that matches no group,
 * or any failed call, is an error: a failed read never reads as an empty group, so no grant is removed by it.
 *
 * The secret is read through [clientSecret] at every token request (STK-29): a rotated secret needs no restart.
 */
class KeycloakDirectoryGroupMembers(
    baseUri: String,
    private val realm: String,
    private val clientId: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
    private val clientSecret: () -> String,
) : DirectoryGroupMembers {
    private val base = baseUri.trimEnd('/')
    private val mapper = JsonMapper.builder().build()

    override fun members(idpGroup: String): Set<IdentityRef> {
        val token = accessToken()
        val id = groupId(token, idpGroup)
        val users = mutableSetOf<IdentityRef>()
        var first = 0
        while (true) {
            val page = getJson(token, "/admin/realms/${enc(realm)}/groups/${enc(id)}/members?first=$first&max=$PAGE")
            check(page.isArray) { "the members of group '$idpGroup' did not come back as a list" }
            page.forEach { users += IdentityRef(it.path("username").asString()) }
            if (page.size() < PAGE) return users
            first += PAGE
        }
    }

    private fun groupId(
        token: String,
        name: String,
    ): String {
        val found = getJson(token, "/admin/realms/${enc(realm)}/groups?exact=true&search=${enc(name)}")
        check(found.isArray) { "the group search did not come back as a list" }
        val match = found.firstOrNull { it.path("name").asString() == name && it.path("path").asString() == "/$name" }
        return checkNotNull(match) { "the directory has no group '$name'" }.path("id").asString()
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
        val token = parse(send(request)).path("access_token").asString()
        check(token.isNotEmpty()) { "the token endpoint returned no access token" }
        return token
    }

    private fun getJson(
        token: String,
        path: String,
    ): JsonNode =
        parse(
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

    private fun parse(body: String): JsonNode = mapper.readTree(body)

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        const val PAGE = 100
        const val HTTP_OK = 200
    }
}
