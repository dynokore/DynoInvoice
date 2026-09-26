package com.dynokore.platform.evidencestore

/**
 * The four evidence-svc stores INV-REQ-2570 names (behaviour 6): `evidence_payload`,
 * `acknowledgement`, `decision_trace` and `audit_log`. Each is its own append-only
 * table with its own columns — this kernel module never assumes a table's shape
 * (the same reasoning [com.dynokore.platform.tenancykernel.TenantRlsMigration]
 * gives for staying schema-agnostic) — this only names which kind a given
 * [EvidenceRecord] is, for indexing and reporting.
 */
enum class EvidenceType { PAYLOAD, ACKNOWLEDGEMENT, DECISION_TRACE, AUDIT_LOG }
