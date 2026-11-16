package com.dynokore.gst.canonical.bootstrap

import com.dynokore.platform.serviceruntime.identity.FlowContextTestSupport
import com.dynokore.platform.serviceruntime.identity.FlowEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-23877: svc-canonical serves an operation that the contract marks `x-flow-context: required` only for a flow
 * context signed by a trusted key that names the verified caller; any other call is answered 403 SRT-004 and stores
 * nothing. The operations and their callers are read from the contract document, never written here.
 */
class FlowContextInboundTest {
    private val operations = ContractDocument.operations(ContractDocument.text())
    private val doubles = WorkloadDoubles(callersOf(operations))

    private fun header(
        caller: String,
        tenant: String = EndUserDoubles.tenantA.toString(),
        issuedAt: Long = Instant.now().epochSecond,
        expiresAt: Long = issuedAt + 300,
        issuer: com.dynokore.platform.serviceruntime.identity.FlowContextIssuer = FlowContextTestSupport.issuer(),
    ): String =
        FlowContextTestSupport.contextFor(
            tenant = tenant,
            entity = FlowEntity.entity("E-001"),
            principalKind = "HUMAN",
            callerService = caller,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            issuer = issuer,
        )

    private fun marked(): List<DocumentOperation> = operations.filter { it.flowContext }

    private fun handlerOf(
        service: ServedService,
        operation: DocumentOperation,
    ): MappedHandler =
        checkNotNull(service.handlers.singleOrNull { it.method == operation.method && it.path == operation.path }) {
            "no handler for ${operation.id}"
        }

    private fun assertRefused(
        service: ServedService,
        label: String,
        handler: MappedHandler,
        token: String,
        extra: Map<String, String>,
    ) {
        val rows = service.state()
        val reached = service.counter.reaches.size
        val answer = service.call(handler, token, EndUserDoubles.USER_A, extra, extra.isNotEmpty())
        assertThat(answer.status).describedAs("$label: ${answer.body}").isEqualTo(403)
        assertThat(answer.body).describedAs(label).contains("SRT-004")
        assertThat(service.counter.reaches.size).describedAs("$label handler calls").isEqualTo(reached)
        assertThat(service.state()).describedAs("$label stored rows").isEqualTo(rows)
    }

    @Test
    fun `INV-AC-23877 the contract document marks at least one operation x-flow-context required`() {
        assertThat(marked()).describedAs("operations with x-flow-context: required").isNotEmpty()
        assertThat(marked().map { it.id }).contains("recordDocument")
    }

    @Test
    fun `INV-AC-23877 a context for the named caller and the tenant reaches the handler once`() {
        val operation = marked().first { it.id == "recordDocument" }
        val caller = allowedCallers(operation, doubles.services).sorted().first()
        ServedService.run(doubles) { service ->
            val handler = handlerOf(service, operation)
            val before = service.counter.reaches.size
            val answer =
                service.call(
                    handler,
                    doubles.tokenOf(caller),
                    EndUserDoubles.USER_A,
                    mapOf("X-Flow-Context" to header(caller)),
                )
            assertThat(answer.status).describedAs(answer.body).isNotEqualTo(403)
            assertThat(service.counter.reaches.size).isEqualTo(before + 1)
        }
    }

    private fun refusedCases(
        caller: String,
        lookalike: String,
    ): Map<String, String?> {
        val now = Instant.now().epochSecond
        val otherTenant = UUID.nameUUIDFromBytes("tenant-b".toByteArray()).toString()
        return mapOf(
            "(b) no header" to null,
            "(c) outside issuer" to header(caller, issuer = FlowContextTestSupport.outsideIssuer()),
            "(d) other caller" to header(lookalike),
            "(e) expired" to header(caller, issuedAt = now - 600, expiresAt = now - 300),
            "(f) other tenant" to header(caller, tenant = otherTenant),
        )
    }

    @Test
    fun `INV-AC-23877 every other call of a marked operation is answered 403 SRT-004 and stores nothing`() {
        val operations = marked()
        assertThat(operations).describedAs("marked operations (fail closed)").isNotEmpty()
        ServedService.run(doubles) { service ->
            operations.forEach { operation ->
                val handler = handlerOf(service, operation)
                val caller = allowedCallers(operation, doubles.services).sorted().first()
                refusedCases(caller, doubles.lookalike).forEach { (name, value) ->
                    val extra = value?.let { mapOf("X-Flow-Context" to it) }.orEmpty()
                    val label = "${operation.id} $name"
                    assertRefused(service, label, handler, doubles.tokenOf(caller), extra)
                }
            }
        }
    }

    @Test
    fun `INV-AC-23877 an operation without the marker is answered without the header as before`() {
        val unmarked =
            operations.first {
                !it.flowContext && !isLeftAlone(it) && it.edges.isNotEmpty() && it.callerAuth == "end-user"
            }
        ServedService.run(doubles) { service ->
            val handler = handlerOf(service, unmarked)
            val caller = allowedCallers(unmarked, doubles.services).sorted().first()
            val before = service.counter.reaches.size
            val answer = service.call(handler, doubles.tokenOf(caller), EndUserDoubles.USER_A)
            assertThat(service.counter.reaches.size).describedAs("${unmarked.id}: ${answer.body}").isEqualTo(before + 1)
        }
    }
}
