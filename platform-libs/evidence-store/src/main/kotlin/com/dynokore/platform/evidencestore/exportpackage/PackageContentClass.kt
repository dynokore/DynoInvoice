package com.dynokore.platform.evidencestore.exportpackage

/**
 * The six content classes of an audit package (INV-REQ-988 behavior 2). Each has one directory under `data/`;
 * the directory names are part of the package form and are not tunable.
 */
enum class PackageContentClass(
    val directory: String,
) {
    TRANSMITTED_XML("transmitted-xml"),
    ACKNOWLEDGEMENTS("acknowledgements"),
    DECISION_TRACES("decision-traces"),
    RECONCILIATION_WORK_PAPERS("reconciliation-work-papers"),
    SOURCE_DOCUMENT_REFERENCES("source-document-references"),
    AUDIT_LOG("audit-log"),
}
