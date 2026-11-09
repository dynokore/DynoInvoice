package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.rate.ReferenceRateLoaded
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.time.LocalDate

/** INV-T-18775: the `ReferenceRateLoaded` mapping and its schema in the pinned bundle. INV-REQ-506. */
class ReferenceRateLoadedOutboxTest {
    private val schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(
            checkNotNull(
                javaClass.classLoader.getResourceAsStream(
                    "gst/contracts/masterdata/schemas/reference-rate-loaded-payload.schema.json",
                ),
            ).use { it.readBytes().toString(Charsets.UTF_8) },
        )

    private inline fun <reified T : Any> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
            error("not expected: ${m.name}")
        } as T

    private val outbox =
        ReferenceRateLoadedOutbox(stub(), JsonMapper.builder().build(), stub(), stub(), stub())

    @Test
    fun `INV-AC-8626 the payload validates against the ReferenceRateLoaded schema with source, pairs, range`() {
        val payload =
            outbox.payload(
                ReferenceRateLoaded(
                    "MAS",
                    setOf("EUR/SGD", "USD/SGD"),
                    LocalDate.parse("2026-03-10"),
                    LocalDate.parse("2026-03-20"),
                    "IMP-1",
                ),
            )
        assertThat(payload.propertyNames().toSet()).isEqualTo(setOf("source", "currencyPairs", "dateRange"))
        assertThat(payload.get("dateRange").get("from").asString()).isEqualTo("2026-03-10")
        assertThat(schema.validate(payload.toString(), InputFormat.JSON)).isEmpty()
        assertThat(ReferenceRateLoadedOutbox.CHANNEL).isEqualTo("masterdataReferenceRateLoaded")
    }
}
