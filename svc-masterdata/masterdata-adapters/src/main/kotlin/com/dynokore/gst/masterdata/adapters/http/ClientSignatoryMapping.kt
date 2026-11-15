package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.clientsignatory.RecordClientSignatoryOutcome
import com.dynokore.gst.masterdata.contract.model.ClientSignatoryRecord
import com.dynokore.gst.masterdata.contract.model.ClientSignatoryRecordLinkStatus
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/** The answer of `recordClientSignatory` (INV-T-17443): 201 with the record, or the refusal of the outcome. */
internal object ClientSignatoryMapping {
    /** A total `when` over [RecordClientSignatoryOutcome]: a new member fails to compile. */
    fun answer(outcome: RecordClientSignatoryOutcome): ResponseEntity<ClientSignatoryRecord> =
        when (outcome) {
            is RecordClientSignatoryOutcome.Recorded -> {
                ResponseEntity.status(HttpStatus.CREATED).body(record(outcome))
            }

            RecordClientSignatoryOutcome.Forbidden -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            is RecordClientSignatoryOutcome.Invalid -> {
                CounterpartyChangeMapping.invalid("${outcome.member} must not be blank")
            }

            RecordClientSignatoryOutcome.InvalidValidity -> {
                throw ApiRefusal(ClosedErrorCode.SERVICE_NOT_READY)
            }
        }

    private fun record(outcome: RecordClientSignatoryOutcome.Recorded): ClientSignatoryRecord {
        val status = outcome.signatory.linkStatus
        return ClientSignatoryRecord(
            signatoryRef = outcome.signatory.signatoryRef,
            clientRef = outcome.signatory.clientRef,
            linkStatus =
                ClientSignatoryRecordLinkStatus(
                    state = ClientSignatoryRecordLinkStatus.State.valueOf(status.state.name),
                    reasonCode = status.reasonCode?.let { ClientSignatoryRecordLinkStatus.ReasonCode.valueOf(it.name) },
                ),
        )
    }
}
