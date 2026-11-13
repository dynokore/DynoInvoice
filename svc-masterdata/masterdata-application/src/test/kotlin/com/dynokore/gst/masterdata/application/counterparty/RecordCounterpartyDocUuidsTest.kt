package com.dynokore.gst.masterdata.application.counterparty

import com.dynokore.gst.masterdata.application.request.DirectTx
import com.dynokore.gst.masterdata.application.request.FakeParties
import com.dynokore.gst.masterdata.application.request.MutableClock
import com.dynokore.gst.masterdata.application.request.PARTY
import com.dynokore.gst.masterdata.application.request.Recorder
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.IdentifierInput
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.platform.partyregistry.IdentifierScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/** INV-T-21320 (INV-REQ-941): RecordCounterparty hands the command's document list to the MasterDataResolved port. */
class RecordCounterpartyDocUuidsTest {
    private val d1 = UUID.fromString("0a1b2c3d-0000-4000-8000-000000000001")
    private val d2 = UUID.fromString("0a1b2c3d-0000-4000-8000-000000000002")
    private val d3 = UUID.fromString("0a1b2c3d-0000-4000-8000-000000000003")
    private val recorder = Recorder()
    private val record = RecordCounterparty(DirectTx, FakeParties(), MutableClock(), recorder)
    private val change =
        CounterpartyChange(listOf(IdentifierInput(IdentifierScheme.UEN, "201912345K")), null, null, null, null)

    @Test
    fun `INV-AC-21319 the Recorder holds the list of the command in order and the empty list when none is set`() {
        record.handle(RecordCommand("ENT-1", PARTY, change, "EV-1", WriteOrigin.OPERATOR, listOf(d3, d1, d2)))
        record.handle(RecordCommand("ENT-1", PARTY, change, "EV-2", WriteOrigin.OPERATOR))
        assertEquals(listOf(listOf(d3, d1, d2), emptyList<UUID>()), recorder.resolvedDocUuids)
    }
}
