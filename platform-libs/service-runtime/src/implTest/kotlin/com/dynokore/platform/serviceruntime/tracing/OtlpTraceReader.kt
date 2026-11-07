package com.dynokore.platform.serviceruntime.tracing

/** One span of an OTLP `TracesData` body: only the fields the assertions use. Attribute values are strings (ints in decimal). */
class DecodedSpan(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String,
    val kind: Int,
    val attributes: Map<String, String>,
)

class DecodedTraces(
    val resourceAttributes: Map<String, String>,
    val spans: List<DecodedSpan>,
)

/**
 * Test-only reader of the OTLP/HTTP protobuf trace body (no protobuf library). Reads TracesData.resource_spans(1) ->
 * ResourceSpans.resource(1).attributes(1) and scope_spans(2) -> ScopeSpans.spans(2) -> Span trace_id(1), span_id(2),
 * parent_span_id(4), kind(6), attributes(9). Malformed or truncated input throws [IllegalArgumentException] (fails closed).
 */
object OtlpTraceReader {
    fun decode(body: ByteArray): DecodedTraces {
        val resourceAttributes = linkedMapOf<String, String>()
        val spans = mutableListOf<DecodedSpan>()
        fields(body) { number, value ->
            if (number == 1) {
                fields(bytes(value)) { n, v ->
                    when (n) {
                        1 -> {
                            fields(bytes(v)) { rn, rv -> if (rn == 1) resourceAttributes += keyValue(bytes(rv)) }
                        }

                        2 -> {
                            fields(bytes(v)) { sn, sv ->
                                if (sn == 2) spans += span(bytes(sv))
                            }
                        }
                    }
                }
            }
        }
        return DecodedTraces(resourceAttributes, spans)
    }

    private fun span(body: ByteArray): DecodedSpan {
        var traceId = ""
        var spanId = ""
        var parent = ""
        var kind = 0
        val attributes = linkedMapOf<String, String>()
        fields(body) { n, v ->
            when (n) {
                1 -> traceId = hex(bytes(v))
                2 -> spanId = hex(bytes(v))
                4 -> parent = hex(bytes(v))
                6 -> kind = (v as Long).toInt()
                9 -> attributes += keyValue(bytes(v))
            }
        }
        return DecodedSpan(traceId, spanId, parent, kind, attributes)
    }

    private fun keyValue(body: ByteArray): Pair<String, String> {
        var key = ""
        var value = ""
        fields(body) { n, v ->
            when (n) {
                1 -> {
                    key = String(bytes(v), Charsets.UTF_8)
                }

                2 -> {
                    fields(bytes(v)) { an, av ->
                        when (an) {
                            1 -> value = String(bytes(av), Charsets.UTF_8)
                            3 -> value = (av as Long).toString()
                        }
                    }
                }
            }
        }
        return key to value
    }

    private fun bytes(value: Any): ByteArray =
        value as? ByteArray ?: throw IllegalArgumentException("wire type mismatch")

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Calls [visit] with (field number, Long for varint/fixed or ByteArray for length-delimited). */
    private fun fields(
        body: ByteArray,
        visit: (Int, Any) -> Unit,
    ) {
        var pos = 0

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < body.size && shift < 64) { "truncated varint" }
                val b = body[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (pos < body.size) {
            val tag = varint()
            val number = (tag ushr 3).toInt()
            require(number > 0) { "bad field number" }
            when ((tag and 7).toInt()) {
                0 -> {
                    visit(number, varint())
                }

                1 -> {
                    require(pos + 8 <= body.size) { "truncated fixed64" }
                    visit(number, 0L)
                    pos += 8
                }

                2 -> {
                    val len = varint()
                    require(len >= 0 && len <= body.size - pos) { "truncated length-delimited field" }
                    visit(number, body.copyOfRange(pos, pos + len.toInt()))
                    pos += len.toInt()
                }

                5 -> {
                    require(pos + 4 <= body.size) { "truncated fixed32" }
                    visit(number, 0L)
                    pos += 4
                }

                else -> {
                    throw IllegalArgumentException("unsupported wire type")
                }
            }
        }
    }
}
