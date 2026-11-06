package com.dynokore.gst.controlPlane.adapters.access

import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.pack.SignPack
import com.dynokore.gst.controlPlane.application.pack.SignPackCommand
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenSource
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenUnavailable
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/** The live `authorise` binding of OperatorRoles against a stub that answers per (principalRef, permission, scope). */
class AuthoriseOperatorRolesTest {
    /** Allowed (principalRef, permission, scope) triples, each answered ALLOW with the role it maps to. */
    private val allowed = LinkedHashMap<Triple<String, String, String>, String>()
    private val requests = ArrayList<Triple<String, String, String>>()
    private var status = 200
    private var rawBody: String? = null
    private var delayMillis = 0L
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext(AuthoriseClient.PATH) { exchange ->
                val q =
                    exchange.requestURI.rawQuery
                        .split("&")
                        .associate {
                            it.substringBefore("=") to
                                URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8)
                        }
                val key = Triple(q.getValue("principalRef"), q.getValue("permission"), q.getValue("scope"))
                if (exchange.requestHeaders.getFirst("X-Workload-Token") != WORKLOAD ||
                    exchange.requestHeaders.getFirst("Authorization") != OPERATOR
                ) {
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                    return@createContext
                }
                requests += key
                Thread.sleep(delayMillis)
                val role = allowed[key]
                val decided = if (role != null) """{"decision":"ALLOW","role":"$role"}""" else """{"decision":"DENY"}"""
                val answer = (rawBody ?: decided).toByteArray()
                exchange.sendResponseHeaders(status, answer.size.toLong())
                exchange.responseBody.use { it.write(answer) }
            }
            start()
        }

    @AfterEach
    fun stop() = server.stop(0)

    private fun roles(timeout: Duration = Duration.ofSeconds(2)) = AuthoriseOperatorRoles(client(timeout))

    private fun client(
        timeout: Duration = Duration.ofSeconds(2),
        tokens: WorkloadTokenSource = WorkloadTokenSource { WorkloadToken(WORKLOAD) },
        authorization: () -> String? = { OPERATOR },
    ) = AuthoriseClient(URI.create("http://127.0.0.1:${server.address.port}"), tokens, authorization, timeout)

    private fun allow(
        actor: String,
        role: String,
        vararg keys: String,
    ) = keys.forEach { allowed[Triple(actor, it, "platform")] = role }

    private fun allowAll(
        actor: String,
        role: String,
    ) = allow(actor, role, *AuthoriseOperatorRoles.ROLE_PERMISSIONS.getValue(role).toTypedArray())

    @Test
    fun `the default timeout equals the x-timeout of authorise`() {
        AuthoriseClient.TIMEOUT shouldBe Duration.ofMillis(500)
    }

    @Test
    fun `the table equals the catalogue keys of the ruled roles`() {
        AuthoriseOperatorRoles.ROLE_PERMISSIONS shouldBe
            mapOf(
                "commercial.admin" to listOf("plan.manage", "entitlement.manage", "licence.manage"),
                "release.manager" to listOf("release-train.certify", "release-train.publish"),
                "regulatory.author" to listOf("regulatory-pack.author"),
                "regulatory.approver" to listOf("regulatory-pack.approve"),
                "platform.engineer" to listOf("audit-entry.purge"),
            )
    }

    @Test
    fun `every key of release manager allowed gives true for it and false for commercial admin`() {
        allowAll("rita", "release.manager")
        roles().holds("rita", "release.manager") shouldBe true
        roles().holds("rita", "commercial.admin") shouldBe false
    }

    @Test
    fun `two of three commercial keys allowed gives false`() {
        allow("carl", "commercial.admin", "plan.manage", "entitlement.manage")
        roles().holds("carl", "commercial.admin") shouldBe false
    }

    @Test
    fun `every request carries the scope platform and never an entity scope`() {
        allowAll("rita", "release.manager")
        roles().holds("rita", "release.manager")
        requests.map { it.third }.toSet() shouldBe setOf("platform")
        requests.map { it.second } shouldBe listOf("release-train.certify", "release-train.publish")
    }

    @Test
    fun `an unknown role is false without a call`() {
        allowAll("rita", "release.manager")
        listOf("platform.support", "", "release.manager ", "Release.Manager").forEach {
            roles().holds("rita", it) shouldBe false
        }
        requests.size shouldBe 0
    }

    @Test
    fun `DENY is false`() {
        roles().holds("rita", "release.manager") shouldBe false
    }

    @Test
    fun `a status other than 200 is false`() {
        allowAll("rita", "release.manager")
        listOf(201, 204, 400, 401, 403, 404, 500, 503).forEach {
            status = it
            roles().holds("rita", "release.manager") shouldBe false
        }
    }

    @Test
    fun `a timeout is false`() {
        allowAll("rita", "release.manager")
        delayMillis = 1500
        roles(Duration.ofMillis(200)).holds("rita", "release.manager") shouldBe false
    }

    @Test
    fun `an unreadable body is false`() {
        allowAll("rita", "release.manager")
        listOf(
            "",
            "not json",
            "[]",
            """{"decision":"ALLOW"}""",
            """{"decision":"allow","role":"release.manager"}""",
        ).forEach {
            rawBody = it
            roles().holds("rita", "release.manager") shouldBe false
        }
    }

    @Test
    fun `a role that differs from the argument is false`() {
        listOf("Release.Manager", "release.manager ", "commercial.admin").forEach { answered ->
            allowed.clear()
            allow("rita", answered, "release-train.certify", "release-train.publish")
            roles().holds("rita", "release.manager") shouldBe false
        }
    }

    @Test
    fun `a connection error is false`() {
        val dead = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val port = dead.address.port
        dead.stop(0)
        val client = AuthoriseClient(URI.create("http://127.0.0.1:$port"), { WorkloadToken(WORKLOAD) }, { OPERATOR })
        AuthoriseOperatorRoles(client).holds("rita", "release.manager") shouldBe false
    }

    @Test
    fun `an actor longer than 64 characters is false without a call`() {
        val long = "a".repeat(65)
        allowAll(long, "release.manager")
        roles().holds(long, "release.manager") shouldBe false
        requests.size shouldBe 0
        val exact = "a".repeat(64)
        allowAll(exact, "release.manager")
        roles().holds(exact, "release.manager") shouldBe true
    }

    @Test
    fun `the regulatory roles follow their own key`() {
        allow("ann", "regulatory.author", "regulatory-pack.author")
        roles().holds("ann", OperatorRole.AUTHOR) shouldBe true
        roles().holds("ann", OperatorRole.APPROVER) shouldBe false
        allow("pat", "regulatory.approver", "regulatory-pack.approve")
        roles().holds("pat", OperatorRole.APPROVER) shouldBe true
        roles().holds("pat", OperatorRole.AUTHOR) shouldBe false
    }

    @Test
    fun `signPack is permitted for the stub-allowed approver and refused for an author-only identity`() {
        allow("pat", "regulatory.approver", "regulatory-pack.approve")
        allow("ann", "regulatory.author", "regulatory-pack.author")
        val sign = SignPack(unused(), roles(), unused(), unused(), unused(), Clock.systemUTC())

        fun command(actor: String) =
            SignPackCommand(ActorId(actor), PackRef("PACK-1"), "KEY", null, false, true, RequestContext("c", "k"))
        sign.permits("pat", command("pat")) shouldBe true
        sign.permits("ann", command("ann")) shouldBe false
    }

    @Test
    fun `look-alike regulatory keys allowed give false for both roles`() {
        allow("lia", "regulatory.author", "regulatory-pack.authorize", "regulatory-pack.approver")
        roles().holds("lia", "regulatory.author") shouldBe false
        roles().holds("lia", "regulatory.approver") shouldBe false
    }

    @Test
    fun `audit-entry purge gives platform engineer and not release manager`() {
        allow("pete", "platform.engineer", "audit-entry.purge")
        roles().holds("pete", "platform.engineer") shouldBe true
        roles().holds("pete", "release.manager") shouldBe false
    }

    @Test
    fun `look-alike purge keys give false for platform engineer`() {
        allow("pete", "platform.engineer", "audit-entries.purge", "audit-entry.purge ")
        roles().holds("pete", "platform.engineer") shouldBe false
    }

    @Test
    fun `permission holds is true for ALLOW whatever the role field and sends no role`() {
        allowed[Triple("rita", "release-train.publish", "platform")] = "anything"
        rawBody = """{"decision":"ALLOW"}"""
        val permissions = AuthoriseOperatorPermissions(client())
        permissions.holds("rita", "release-train.publish") shouldBe true
        requests shouldBe listOf(Triple("rita", "release-train.publish", "platform"))
    }

    @Test
    fun `permission holds is false for DENY, other statuses, a bad body and a long actor`() {
        val permissions = AuthoriseOperatorPermissions(client())
        permissions.holds("rita", "release-train.publish") shouldBe false
        allowed[Triple("rita", "release-train.publish", "platform")] = "release.manager"
        permissions.holds("rita", "release-train.publish ") shouldBe false
        permissions.holds("rita", "Release-Train.publish") shouldBe false
        status = 500
        permissions.holds("rita", "release-train.publish") shouldBe false
        status = 200
        rawBody = "not json"
        permissions.holds("rita", "release-train.publish") shouldBe false
        rawBody = null
        permissions.holds("a".repeat(65), "release-train.publish") shouldBe false
    }

    @Test
    fun `permission holds is false on a timeout`() {
        allowed[Triple("rita", "release-train.publish", "platform")] = "release.manager"
        delayMillis = 1500
        AuthoriseOperatorPermissions(client(Duration.ofMillis(200))).holds("rita", "release-train.publish") shouldBe
            false
    }

    @Test
    fun `both credentials present and expected give true`() {
        allowAll("rita", "release.manager")
        roles().holds("rita", "release.manager") shouldBe true
    }

    @Test
    fun `a wrong workload token gives false through the 401`() {
        allowAll("rita", "release.manager")
        val wrong = client(tokens = { WorkloadToken("other") })
        AuthoriseOperatorRoles(wrong).holds("rita", "release.manager") shouldBe false
    }

    @Test
    fun `a missing, blank or absent Authorization gives false without a call`() {
        allowAll("rita", "release.manager")
        listOf<String?>(null, "", "  ").forEach {
            AuthoriseOperatorRoles(client(authorization = { it })).holds("rita", "release.manager") shouldBe false
            AuthoriseOperatorPermissions(client(authorization = { it })).holds("rita", "release-train.publish") shouldBe
                false
        }
        requests.size shouldBe 0
    }

    @Test
    fun `an unavailable workload token gives false without a call`() {
        allowAll("rita", "release.manager")
        val unavailable = client(tokens = { throw WorkloadTokenUnavailable(Path.of("/none")) })
        AuthoriseOperatorRoles(unavailable).holds("rita", "release.manager") shouldBe false
        AuthoriseOperatorPermissions(unavailable).holds("rita", "release-train.publish") shouldBe false
        requests.size shouldBe 0
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
            error("not used by permits")
        } as T

    private companion object {
        const val WORKLOAD = "workload-secret"
        const val OPERATOR = "Bearer operator-secret"
    }
}
