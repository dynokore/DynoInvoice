package com.dynokore.platform.outboxandqueue

/** One consumer of one topic, with its inbox, sequence memory and offset in its own schema (INV-REQ-1222). */
interface InboxConsumer {
    /**
     * Reads the next batch from the topic and applies each record in its own transaction, with the
     * record's `tenantid` bound (ARC-21): inbox row, `docsequence` check, handler, sequence and offset
     * commit together (ARC 4.2).
     * A handler failure rolls that record back and is rethrown; the offset has not moved, so
     * the next poll redelivers it.
     */
    fun pollOnce(): PollResult
}
