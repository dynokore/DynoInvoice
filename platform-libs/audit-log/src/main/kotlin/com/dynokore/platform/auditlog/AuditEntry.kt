package com.dynokore.platform.auditlog

import com.dynokore.platform.tenancykernel.BlankText

/**
 * What an audit entry records (INV-REQ-861, INV-REQ-1376; payload of `AuditEntryRecorded`, platform-contracts
 * `service-runtime/schemas/audit-entry-recorded-payload.schema.json`).
 *
 * @property actor the principal, or a system actor for an automated change.
 * @property action the action recorded, e.g. the use case name.
 * @property scope the entity or resource the action concerns.
 * @property reason why it was taken; `null` when none was given (an override always has one).
 * @property priorStateJson the state before the change as a JSON object, or `null` when there is none to record.
 */
data class AuditEntry(
    val actor: String,
    val action: String,
    val scope: String,
    val reason: String? = null,
    val priorStateJson: String? = null,
) {
    init {
        require(BlankText.isActor(actor)) { "audit actor must not be blank or hold a control character" }
        require(action.isNotBlank()) { "audit action must not be blank" }
        require(scope.isNotBlank()) { "audit scope must not be blank" }
        require(reason == null || reason.isNotBlank()) { "audit reason, when given, must not be blank" }
    }
}
