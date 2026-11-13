package com.dynokore.gst.payload.adapters.http

import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.application.override.VALIDATION_OVERRIDE
import com.dynokore.gst.payload.application.version.GetLatestDocumentVersion
import com.dynokore.gst.payload.application.version.LatestDocumentVersions
import com.dynokore.gst.payload.application.version.LatestVersionFacts
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.ResponseEntity
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.util.UUID

class QueriesControllerTest {
    private val json = JsonMapper.builder().build()
    private val d = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val stored = mutableMapOf<UUID, MutableList<Int>>()
    private var caller: String? = "A1"
    private var holders = setOf("A1")

    private fun sut(): QueriesController {
        val http =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                val key = args?.firstOrNull()
                if (method.name == "getAttribute" && key == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) {
                    caller?.let { EndUserPrincipal(it, null, emptySet()) }
                } else {
                    null
                }
            } as HttpServletRequest
        val versions =
            LatestDocumentVersions { doc -> stored[doc]?.maxOrNull()?.let { LatestVersionFacts(it, "E-001") } }
        val authority =
            OverrideAuthority { userId, permission, _ ->
                permission == VALIDATION_OVERRIDE &&
                    userId in holders
            }
        return QueriesController(
            GetLatestDocumentVersion(versions, authority),
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            StaticListableBeanFactory(mapOf("request" to http)).getBeanProvider(HttpServletRequest::class.java),
        )
    }

    private fun ResponseEntity<*>.problem(
        status: Int,
        code: String?,
    ): String {
        statusCode.value() shouldBe status
        val text = body as String
        json.readTree(text)["code"]?.asString() shouldBe code
        return text
    }

    @Test
    fun `INV-AC-21135 200 with the maximum version for a holder`() {
        stored[d] = mutableListOf(4, 1, 2)
        val answer = sut().getLatestDocumentVersion(d)
        answer.statusCode.value() shouldBe 200
        answer.body!!.docUuid shouldBe d
        answer.body!!.version shouldBe 4
    }

    @Test
    fun `INV-AC-21135 404 SRT-005 for a document with no row`() {
        sut().getLatestDocumentVersion(UUID.randomUUID()).problem(404, "SRT-005")
    }

    @Test
    fun `INV-AC-21135 403 SRT-004 for a denied caller and the body names no version`() {
        stored[d] = mutableListOf(4)
        holders = emptySet()
        val text = sut().getLatestDocumentVersion(d).problem(403, "SRT-004")
        text shouldNotContain "\"version\""
    }

    @Test
    fun `INV-AC-21135 401 without a verified principal`() {
        stored[d] = mutableListOf(4)
        caller = null
        val answer: ResponseEntity<*> = sut().getLatestDocumentVersion(d)
        answer.statusCode.value() shouldBe 401
        (answer.body as String) shouldNotContain "\"version\""
    }
}
