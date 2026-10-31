package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.access.AuthoriseOperatorPermissions
import com.dynokore.gst.controlPlane.adapters.access.AuthoriseOperatorRoles
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.PublishTrainCommand
import com.dynokore.gst.controlPlane.application.release.SetSupportWindow
import com.dynokore.gst.controlPlane.application.release.SetSupportWindowCommand
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.SupportWindow
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenSource
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenUnavailable
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path

/**
 * The OperatorRoles and OperatorPermissions bindings in the profiles `test`, `saas`, `private`: live with the base URL,
 * closed without. A stub access-svc answers 401 unless both credentials carry the expected values.
 */
class OperatorRolesProfilesTest {
    @TempDir
    lateinit var mount: Path

    private val queries = ArrayList<String>()
    private var handler: (HttpExchange) -> Pair<Int, String> = { 200 to ALLOW_PUBLISH }
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/internal/v1/access/authorise") { exchange ->
                val ok =
                    exchange.requestHeaders.getFirst("X-Workload-Token") == WORKLOAD &&
                        exchange.requestHeaders.getFirst("Authorization") == OPERATOR
                if (ok) queries += exchange.requestURI.rawQuery
                val (status, body) = if (ok) handler(exchange) else 401 to "{}"
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    @AfterEach
    fun stop() {
        server.stop(0)
        RequestContextHolder.resetRequestAttributes()
    }

    private val request = RequestContext("corr", "cause")
    private val version = TrainVersion("2026.10.0")
    private val publishCommand = PublishTrainCommand(ActorId("rita"), version, TrainKind.TRAIN, false, false, request)
    private val windowCommand =
        SetSupportWindowCommand(ActorId("rita"), SupportWindow("2026.2", listOf(version)), request)

    private fun operatorRequest(authorization: String?) {
        val servlet =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                if (method.name == "getHeader" && args[0] == "Authorization") authorization else null
            } as HttpServletRequest
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(servlet))
    }

    private fun everyProfile(
        withUrl: Boolean,
        workload: WorkloadTokenSource = WorkloadTokenSource { WorkloadToken(WORKLOAD) },
        authorization: String? = OPERATOR,
        probe: (PublishTrain, SetSupportWindow, OperatorPermissions, OperatorRoles) -> Unit,
    ) = listOf("test", "saas", "private").map { profile ->
        DynamicTest.dynamicTest(profile) {
            queries.clear()
            operatorRequest(authorization)
            val extra =
                if (withUrl) {
                    arrayOf(
                        "controlplane.access.base-url=http://127.0.0.1:${server.address.port}",
                    )
                } else {
                    emptyArray()
                }
            controlPlaneRunner(mount, "spring.profiles.active=$profile", *extra)
                .withAllowBeanDefinitionOverriding(true)
                .withBean("workloadTokenSource", WorkloadTokenSource::class.java, { workload })
                .run { context ->
                    context.startupFailure shouldBe null
                    probe(
                        context.getBean(PublishTrain::class.java),
                        context.getBean(SetSupportWindow::class.java),
                        context.getBean(OperatorPermissions::class.java),
                        context.getBean(OperatorRoles::class.java),
                    )
                }
        }
    }

    private fun bothPermit(
        expected: Boolean,
        vararg observed: String,
        configure: () -> Unit = {},
        authorization: String? = OPERATOR,
        workload: WorkloadTokenSource = WorkloadTokenSource { WorkloadToken(WORKLOAD) },
    ) = everyProfile(true, workload, authorization) { publish, window, permissions, _ ->
        configure()
        (permissions is AuthoriseOperatorPermissions) shouldBe true
        publish.permits("rita", publishCommand) shouldBe expected
        window.permits("rita", windowCommand) shouldBe expected
        queries shouldBe observed.toList()
    }

    private val publishQuery = "principalRef=rita&permission=release-train.publish&scope=platform"

    @TestFactory
    fun `with the property the live roles and permissions are bound`() =
        everyProfile(true) { _, _, permissions, roles ->
            (roles is AuthoriseOperatorRoles) shouldBe true
            (permissions is AuthoriseOperatorPermissions) shouldBe true
        }

    @TestFactory
    fun `without the property both are the fail-closed defaults and make no call`() =
        everyProfile(false) { publish, window, permissions, roles ->
            (roles is AuthoriseOperatorRoles) shouldBe false
            (permissions is AuthoriseOperatorPermissions) shouldBe false
            AuthoriseOperatorRoles.ROLE_PERMISSIONS.keys.forEach { roles.holds("rita", it) shouldBe false }
            publish.permits("rita", publishCommand) shouldBe false
            window.permits("rita", windowCommand) shouldBe false
            queries.size shouldBe 0
        }

    @TestFactory
    fun `publishTrain and setSupportWindow are accepted for a stub that allows release-train publish`() =
        bothPermit(true, publishQuery, publishQuery)

    @TestFactory
    fun `a stub that answers DENY refuses both`() =
        bothPermit(false, publishQuery, publishQuery, configure = { handler = { 200 to """{"decision":"DENY"}""" } })

    @TestFactory
    fun `a stub that allows only release-train certify refuses both`() =
        bothPermit(
            false,
            publishQuery,
            publishQuery,
            configure = {
                handler = { x ->
                    if (x.requestURI.rawQuery.contains("permission=release-train.certify")) {
                        200 to ALLOW_PUBLISH
                    } else {
                        200 to """{"decision":"DENY"}"""
                    }
                }
            },
        )

    @TestFactory
    fun `a stub that allows only a look-alike key refuses both`() =
        bothPermit(
            false,
            publishQuery,
            publishQuery,
            configure = {
                handler = { x ->
                    val q = x.requestURI.rawQuery
                    val lookAlike = q.contains("permission=release-train.publish%20") || q.contains("Release-Train")
                    if (lookAlike) 200 to ALLOW_PUBLISH else 200 to """{"decision":"DENY"}"""
                }
            },
        )

    @TestFactory
    fun `a stub that answers 500 refuses both`() =
        bothPermit(false, publishQuery, publishQuery, configure = { handler = { 500 to "{}" } })

    @TestFactory
    fun `a stub that times out refuses both`() =
        bothPermit(
            false,
            publishQuery,
            publishQuery,
            configure = {
                handler = {
                    Thread.sleep(900)
                    200 to ALLOW_PUBLISH
                }
            },
        )

    @TestFactory
    fun `a stub that returns an unreadable body refuses both`() =
        bothPermit(false, publishQuery, publishQuery, configure = { handler = { 200 to "<html>" } })

    @TestFactory
    fun `an allow without a role field is still accepted`() =
        bothPermit(true, publishQuery, publishQuery, configure = { handler = { 200 to """{"decision":"ALLOW"}""" } })

    @TestFactory
    fun `a missing workload token header is refused by the stub 401`() =
        everyProfile(true, workload = WorkloadTokenSource { WorkloadToken("other") }) { publish, _, _, _ ->
            publish.permits("rita", publishCommand) shouldBe false
        }

    @TestFactory
    fun `a missing Authorization gives false without a call`() = bothPermit(false, authorization = null)

    @TestFactory
    fun `a blank Authorization gives false without a call`() = bothPermit(false, authorization = "  ")

    @TestFactory
    fun `no current request gives false without a call`() =
        everyProfile(true) { publish, window, _, _ ->
            RequestContextHolder.resetRequestAttributes()
            publish.permits("rita", publishCommand) shouldBe false
            window.permits("rita", windowCommand) shouldBe false
            queries.size shouldBe 0
        }

    @TestFactory
    fun `an unavailable workload token gives false without a call`() =
        bothPermit(false, workload = WorkloadTokenSource { throw WorkloadTokenUnavailable(mount) })

    private companion object {
        const val WORKLOAD = "workload-secret"
        const val OPERATOR = "Bearer operator-secret"
        const val ALLOW_PUBLISH = """{"decision":"ALLOW","role":"release.manager"}"""
    }
}
