package com.dynokore.gst.canonical.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** INV-AC-13669: `canonical.inbound_event.document_function` accepts the six documentFunction values only. */
class InboundEventDocumentFunctionTest {
    private val tenant = UUID.randomUUID()

    private fun insert(function: String) =
        "insert into canonical.inbound_event (tenant_id, entity_id, doc_uuid, version, event_kind, " +
            "document_function) values ('$tenant', '${UUID.randomUUID()}', '${UUID.randomUUID()}', 1, 'POSTING', " +
            "'$function')"

    private fun refusal(function: String) = DocumentSql.runtimeRefusal(tenant, insert(function))

    @Test
    fun `INV-AC-13669 each of the six documentFunction values is accepted`() {
        listOf("INVOICE", "CREDIT_NOTE", "DEBIT_NOTE", "BILL_FOR_PAYMENT", "ADJUSTMENT", "NOT_A_BILL").forEach {
            refusal(it) shouldBe null
        }
    }

    @Test
    fun `INV-AC-13669 PROFORMA, lower case adjustment and the empty string are refused by the check`() {
        listOf("PROFORMA", "adjustment", "").forEach {
            refusal(it) shouldBe "23514"
        }
    }
}
