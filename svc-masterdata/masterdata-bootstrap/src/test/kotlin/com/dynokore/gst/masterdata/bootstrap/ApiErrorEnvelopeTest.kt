package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper

/**
 * INV-Q-17119 A: every response that Spring MVC refuses before or while binding a request carries the API-20 envelope
 * (RFC 9457 `application/problem+json`), a code of the published catalogue and a fixed detail that echoes nothing the
 * caller sent (API-22). The catalogue holds no 415 or 406 row; SRT-001 is the nearest existing code.
 */
class ApiErrorEnvelopeTest {
    private val base = "/internal/v1/masterdata"
    private val secret = "ZZ-SENT-VALUE-ZZ"

    private fun run(block: (org.springframework.test.web.servlet.MockMvc) -> Unit) {
        BoundaryFixture
            .runner()
            .withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } })
            .withPropertyValues("service-runtime.idempotency.schema=masterdata")
            .run { context: AssertableWebApplicationContext ->
                context.startupFailure?.let { throw it }
                block(
                    MockMvcBuilders
                        .webAppContextSetup(context.sourceApplicationContext as WebApplicationContext)
                        .build(),
                )
            }
    }

    private data class Row(
        val label: String,
        val method: HttpMethod,
        val path: String,
        val contentType: MediaType? = null,
        val accept: MediaType? = null,
        val body: String? = null,
        val status: Int,
        val code: String,
    )

    private val rows =
        listOf(
            Row(
                "wrongly typed query value",
                HttpMethod.GET,
                "$base/counterparties/CTP-1/history?limit=$secret",
                status = 400,
                code = "SRT-001",
            ),
            Row(
                "missing required query parameter",
                HttpMethod.GET,
                "$base/counterparties/resolution",
                status = 400,
                code = "SRT-001",
            ),
            Row(
                "unreadable body",
                HttpMethod.POST,
                "$base/counterparties/bulk-resolution",
                MediaType.APPLICATION_JSON,
                body = "{\"$secret\": ",
                status = 400,
                code = "SRT-001",
            ),
            Row(
                "media type the operation does not accept",
                HttpMethod.POST,
                "$base/counterparties/bulk-resolution",
                MediaType.TEXT_PLAIN,
                body = secret,
                status = 400,
                code = "SRT-001",
            ),
            Row(
                "accept it cannot serve",
                HttpMethod.GET,
                "$base/counterparties/CTP-1/history",
                accept = MediaType.parseMediaType("application/x-$secret"),
                status = 400,
                code = "SRT-001",
            ),
            Row("unknown path", HttpMethod.GET, "$base/nope-$secret", status = 404, code = "SRT-005"),
            Row(
                "wrong method on a known path",
                HttpMethod.DELETE,
                "$base/counterparties/CTP-1/history",
                status = 405,
                code = "SRT-006",
            ),
        )

    private fun send(
        mvc: org.springframework.test.web.servlet.MockMvc,
        row: Row,
    ): MockHttpServletResponse =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .request(row.method, java.net.URI(row.path))
                    .apply {
                        row.contentType?.let { contentType(it) }
                        row.accept?.let { accept(it) }
                        row.body?.let { content(it) }
                    },
            ).andReturn()
            .response

    @Test
    fun `a request Spring MVC refuses is answered with the API-20 envelope`() =
        run { mvc ->
            rows.forEach { row ->
                val response = send(mvc, row)
                val text = response.contentAsString
                assertThat(response.status).describedAs("${row.label}: $text").isEqualTo(row.status)
                assertThat(response.contentType).describedAs(row.label).isEqualTo("application/problem+json")
                val json = JsonMapper().readTree(text)
                assertThat(json["code"].asString()).describedAs(row.label).isEqualTo(row.code)
                assertThat(json["type"].asString()).describedAs(row.label)
                    .isEqualTo("https://errors.invoice.internal/${row.code}")
                assertThat(json["status"].asInt()).describedAs(row.label).isEqualTo(row.status)
                assertThat(text).describedAs(row.label).doesNotContain(secret)
                if (row.status == 405) {
                    assertThat(response.getHeader("Allow")).describedAs(row.label).isNotBlank()
                }
            }
        }
}
