package com.dynokore.gst.canonical.application.document

import java.util.UUID

/**
 * Decorates the result builder: after the inner builder has answered, it writes the document row (version 1) or the
 * next version through [writer], inside the record transaction the caller runs it in. A null answer of [source]
 * means no row exists yet for this record, so nothing is written and the inner text is returned.
 */
class RowWritingResultBuilder(
    private val inner: DocumentResultBuilder,
    private val versions: DocumentVersions,
    private val source: DocumentRowFactsSource,
    private val writer: DocumentRowWriter,
) : DocumentResultBuilder {
    override fun build(
        command: RecordDocumentCommand,
        docUuid: UUID,
    ): String {
        if (command.direction == null || command.documentNumber == null) throw RecordRefused("SRT-001")
        val version = versions.nextVersion(docUuid)
        val text = inner.build(command, docUuid)
        val facts = source.factsOf(command, docUuid, version, text) ?: return text
        writer.write(command, docUuid, version, facts)
        return text
    }
}
