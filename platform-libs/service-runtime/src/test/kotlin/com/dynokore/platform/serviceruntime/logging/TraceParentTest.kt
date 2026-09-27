package com.dynokore.platform.serviceruntime.logging

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TraceParentTest {
    private val header = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

    @Test
    fun `parses a well-formed W3C traceparent header`() {
        val parsed = TraceParent.parse(header).shouldNotBeNull()
        parsed.version shouldBe "00"
        parsed.traceId shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
        parsed.parentId shouldBe "00f067aa0ba902b7"
        parsed.traceFlags shouldBe "01"
    }

    @Test
    fun `round-trips back to the same wire format`() {
        TraceParent.parse(header)!!.toString() shouldBe header
    }

    @Test
    fun `an all-zero trace id or parent id is not well-formed`() {
        TraceParent.parse("00-00000000000000000000000000000000-00f067aa0ba902b7-01").shouldBeNull()
        TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01").shouldBeNull()
    }

    @Test
    fun `malformed input is not well-formed`() {
        TraceParent.parse("not-a-traceparent").shouldBeNull()
        TraceParent.parse("").shouldBeNull()
    }
}
