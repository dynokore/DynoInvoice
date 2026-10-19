package com.dynokore.platform.outboxandqueue

import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/** The columns of a consumer's offset, inbox and sequence tables ([OutboxSchema]), shared by the inbox classes. */
internal object InboxColumns {
    val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB)
    val TOPIC = DSL.field(DSL.name("topic"), SQLDataType.CLOB)
    val NEXT_OFFSET = DSL.field(DSL.name("next_offset"), SQLDataType.BIGINT)
    val ATTEMPTS = DSL.field(DSL.name("attempts"), SQLDataType.INTEGER)
    val INFRA_FAILURES = DSL.field(DSL.name("infra_failures"), SQLDataType.INTEGER)
    val FIRST_INFRA_FAILURE_AT =
        DSL.field(
            DSL.name("first_infra_failure_at"),
            SQLDataType.TIMESTAMPWITHTIMEZONE,
        )
    val RETRY_NOT_BEFORE = DSL.field(DSL.name("retry_not_before"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    val EVENT_ID = DSL.field(DSL.name("event_id"), SQLDataType.CLOB)
    val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
    val SOURCE = DSL.field(DSL.name("source"), SQLDataType.CLOB)
    val SUBJECT = DSL.field(DSL.name("subject"), SQLDataType.CLOB)
    val LAST_VALUE = DSL.field(DSL.name("last_value"), SQLDataType.BIGINT)
}
